package xyz.froquefy.playercount;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Owns the MySQL connection pool and maintains the live online-player table.
 *
 * <p>One table is kept, holding exactly the players currently online somewhere on
 * the network:
 * <ul>
 *   <li>{@code <prefix>players} — one row per online player: an auto-increment
 *       {@code id}, the player's {@code nick}, and the {@code server} (backend) they
 *       are on. A player who disconnects is deleted; counts are derived downstream
 *       (e.g. {@code COUNT(*)} globally, {@code GROUP BY server} per backend).</li>
 * </ul>
 *
 * <p>All writes are funnelled through a single background thread, so they never run
 * on Velocity's event threads and stay strictly ordered (an upsert always lands
 * before the disconnect that follows it). Every database interaction is best-effort:
 * a down or unreachable MySQL is logged once and the operation dropped, so a database
 * outage cannot disturb the proxy or its players. Drift from a dropped write is
 * repaired by the periodic full {@link #reconcile(Map) reconcile}.
 */
final class Database implements AutoCloseable {

    private final Logger logger;
    private final HikariDataSource dataSource;
    private final String playersTable;

    /** Serialises every write off the event threads and preserves ordering. */
    private final ExecutorService writer;

    /** Set once the table exists. Touched only on the {@link #writer} thread. */
    private boolean schemaReady = false;
    /** Tracks the previous write's outcome so a persistent outage logs once, not per event. */
    private boolean lastWriteFailed = false;

    Database(PluginConfig config, Logger logger) {
        this.logger = logger;
        this.playersTable = config.tablePrefix() + "players";

        HikariConfig hc = new HikariConfig();
        hc.setPoolName("playercount-mysql");
        hc.setJdbcUrl(buildJdbcUrl(config));
        hc.setUsername(config.username());
        hc.setPassword(config.password());
        hc.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hc.setMaximumPoolSize(config.poolSize());
        hc.setMinimumIdle(0);
        hc.setConnectionTimeout(config.connectionTimeoutMs());
        hc.setIdleTimeout(60_000);
        hc.setMaxLifetime(1_800_000);          // 30 min, comfortably under MySQL's default wait_timeout
        hc.setLeakDetectionThreshold(30_000);  // log the stack of any connection held > 30s
        // The one place we deliberately differ from the fail-loud-on-open convention:
        // a negative initialization-fail-timeout makes the pool start immediately and
        // connect lazily, so an unreachable MySQL at boot can neither block nor fail
        // proxy startup. A proxy must not be taken down by a database that is merely
        // slow to come up; ongoing write failures are still caught and retried.
        hc.setInitializationFailTimeout(-1);

        // HikariCP's recommended Connector/J property bag (mc_dev PERSISTENCE.md §8).
        // cachePrepStmts is the master switch for the rest.
        hc.addDataSourceProperty("cachePrepStmts", "true");
        hc.addDataSourceProperty("prepStmtCacheSize", "250");
        hc.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hc.addDataSourceProperty("useServerPrepStmts", "true");
        hc.addDataSourceProperty("useLocalSessionState", "true");
        hc.addDataSourceProperty("rewriteBatchedStatements", "true");
        hc.addDataSourceProperty("cacheResultSetMetadata", "true");
        hc.addDataSourceProperty("cacheServerConfiguration", "true");
        hc.addDataSourceProperty("elideSetAutoCommits", "true");
        hc.addDataSourceProperty("maintainTimeStats", "false");

        this.dataSource = new HikariDataSource(hc);
        this.writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "playercount-db");
            t.setDaemon(true);
            return t;
        });
    }

    private static String buildJdbcUrl(PluginConfig c) {
        // The performance property bag rides on the Hikari pool (see the constructor),
        // matching the house convention; the URL carries only how to reach and secure
        // the connection, plus bounded connect/socket timeouts so a wedged network
        // link can never hang a write indefinitely.
        return "jdbc:mysql://" + c.host() + ":" + c.port() + "/" + c.database()
                + "?useSSL=" + c.useSsl()
                // Required for caching_sha2_password auth over a plaintext connection.
                + "&allowPublicKeyRetrieval=" + (!c.useSsl())
                + "&connectTimeout=" + c.connectionTimeoutMs()
                + "&socketTimeout=15000";
    }

    /**
     * Records (or moves) a player: inserts the row, or updates the backend they are
     * on if they are already present. Runs asynchronously and best-effort.
     */
    void upsert(String nick, String server) {
        submit(() -> exec(conn -> {
            String sql = "INSERT INTO `" + playersTable + "` (nick, server) VALUES (?, ?) "
                    + "ON DUPLICATE KEY UPDATE server = VALUES(server)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, nick);
                ps.setString(2, server);
                ps.executeUpdate();
            }
        }));
    }

    /** Removes a player that has gone offline. Runs asynchronously and best-effort. */
    void remove(String nick) {
        submit(() -> exec(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM `" + playersTable + "` WHERE nick = ?")) {
                ps.setString(1, nick);
                ps.executeUpdate();
            }
        }));
    }

    /**
     * Rebuilds the table to match the given online set exactly, in one transaction.
     * This is the safety net: it repairs any drift left by a write that failed while
     * MySQL was down, and clears phantom rows after an unclean shutdown. An empty map
     * empties the table (e.g. on proxy shutdown). Runs asynchronously and best-effort.
     *
     * @param online nick → backend-server name for every currently online player
     */
    void reconcile(Map<String, String> online) {
        submit(() -> exec(conn -> {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("DELETE FROM `" + playersTable + "`");
                }
                if (!online.isEmpty()) {
                    String sql = "INSERT INTO `" + playersTable + "` (nick, server) VALUES (?, ?)";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        for (Map.Entry<String, String> entry : online.entrySet()) {
                            ps.setString(1, entry.getKey());
                            ps.setString(2, entry.getValue());
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        }));
    }

    private void submit(Runnable op) {
        try {
            writer.execute(op);
        } catch (RejectedExecutionException ignored) {
            // The pool is shutting down (proxy stop / reload); dropping the write is fine.
        }
    }

    /**
     * Runs one unit of work on a pooled connection, ensuring the schema first and
     * applying the log-once-on-failure / log-once-on-recovery policy. Always called
     * on the {@link #writer} thread.
     */
    private void exec(SqlWork work) {
        try (Connection conn = dataSource.getConnection()) {
            ensureSchema(conn);
            work.run(conn);
            if (lastWriteFailed) {
                logger.info("MySQL write recovered - the online player list is being recorded again.");
                lastWriteFailed = false;
            }
        } catch (SQLException e) {
            if (!lastWriteFailed) {
                logger.warn("Could not write the online player list to MySQL: {}. The proxy is "
                        + "unaffected; the next event or reconcile will retry.", e.getMessage());
                lastWriteFailed = true;
            }
        }
    }

    private void ensureSchema(Connection conn) throws SQLException {
        if (schemaReady) {
            return;
        }
        try (Statement st = conn.createStatement()) {
            // nick is UNIQUE: a player can be online only once, so it doubles as the
            // upsert key. VARCHAR(32) leaves headroom for Geyser/Bedrock prefixed names.
            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + playersTable + "` ("
                    + "id INT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                    + "nick VARCHAR(32) NOT NULL UNIQUE, "
                    + "server VARCHAR(64) NOT NULL"
                    + ")");
        }
        schemaReady = true;
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            // Drain any queued writes (e.g. the shutdown reconcile that empties the table)
            // before tearing the pool down.
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
        dataSource.close();
    }

    /** A unit of database work that may fail with {@link SQLException}. */
    @FunctionalInterface
    private interface SqlWork {
        void run(Connection conn) throws SQLException;
    }
}
