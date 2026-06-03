package xyz.froquefy.playercount;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Owns the MySQL connection pool and writes the latest player-count snapshot.
 *
 * <p>Two tables are maintained, both holding only the current values:
 * <ul>
 *   <li>{@code <prefix>network} — a single row (id = 1) with the network total;</li>
 *   <li>{@code <prefix>servers} — one row per registered backend server.</li>
 * </ul>
 *
 * <p>Every database interaction is best-effort: {@link #writeSnapshot} never
 * throws. A down or unreachable MySQL is logged once and retried on the next
 * interval, so a database outage cannot disturb the proxy or its players.
 */
final class Database implements AutoCloseable {

    private final Logger logger;
    private final HikariDataSource dataSource;
    private final String networkTable;
    private final String serversTable;

    /** Set once the tables exist; reset implicitly by re-running idempotent DDL. */
    private volatile boolean schemaReady = false;
    /** Tracks the previous tick's outcome so a persistent outage logs once, not every interval. */
    private boolean lastTickFailed = false;

    Database(PluginConfig config, Logger logger) {
        this.logger = logger;
        this.networkTable = config.tablePrefix() + "network";
        this.serversTable = config.tablePrefix() + "servers";

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
        // rewriteBatchedStatements collapses the per-server executeBatch() into one
        // multi-row statement; cachePrepStmts is the master switch for the rest.
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
     * Writes the current snapshot, ensuring the schema first. Swallows and logs
     * any {@link SQLException}: a database problem is reported once and retried
     * next interval rather than propagated.
     *
     * @param total     the network-wide player count
     * @param perServer player count per registered backend server
     */
    void writeSnapshot(int total, Map<String, Integer> perServer) {
        try (Connection conn = dataSource.getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                if (!schemaReady) {
                    createSchema(conn);
                    schemaReady = true;
                }
                upsertNetwork(conn, total);
                upsertServers(conn, perServer);
                pruneStaleServers(conn, perServer.keySet());
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
            if (lastTickFailed) {
                logger.info("MySQL write recovered - player counts are being recorded again.");
                lastTickFailed = false;
            }
        } catch (SQLException e) {
            if (!lastTickFailed) {
                logger.warn("Could not write player counts to MySQL: {}. The proxy is unaffected; "
                        + "will keep retrying every interval.", e.getMessage());
                lastTickFailed = true;
            }
        }
    }

    private void createSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + networkTable + "` ("
                    + "id TINYINT NOT NULL PRIMARY KEY, "
                    + "total INT NOT NULL, "
                    + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"
                    + ")");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + serversTable + "` ("
                    + "server VARCHAR(64) NOT NULL PRIMARY KEY, "
                    + "players INT NOT NULL, "
                    + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"
                    + ")");
        }
    }

    private void upsertNetwork(Connection conn, int total) throws SQLException {
        String sql = "INSERT INTO `" + networkTable + "` (id, total, updated_at) "
                + "VALUES (1, ?, CURRENT_TIMESTAMP) "
                + "ON DUPLICATE KEY UPDATE total = VALUES(total), updated_at = CURRENT_TIMESTAMP";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, total);
            ps.executeUpdate();
        }
    }

    private void upsertServers(Connection conn, Map<String, Integer> perServer) throws SQLException {
        if (perServer.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO `" + serversTable + "` (server, players, updated_at) "
                + "VALUES (?, ?, CURRENT_TIMESTAMP) "
                + "ON DUPLICATE KEY UPDATE players = VALUES(players), updated_at = CURRENT_TIMESTAMP";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map.Entry<String, Integer> entry : perServer.entrySet()) {
                ps.setString(1, entry.getKey());
                ps.setInt(2, entry.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Deletes rows for servers no longer registered, so the table stays a faithful snapshot. */
    private void pruneStaleServers(Connection conn, Set<String> current) throws SQLException {
        if (current.isEmpty()) {
            return;
        }
        StringJoiner placeholders = new StringJoiner(",");
        for (int i = 0; i < current.size(); i++) {
            placeholders.add("?");
        }
        String sql = "DELETE FROM `" + serversTable + "` WHERE server NOT IN (" + placeholders + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int index = 1;
            for (String server : current) {
                ps.setString(index++, server);
            }
            ps.executeUpdate();
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
