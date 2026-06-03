package com.froquefy.playercount;

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
        hc.setPoolName("PlayerCount-Hikari");
        hc.setJdbcUrl(buildJdbcUrl(config));
        hc.setUsername(config.username());
        hc.setPassword(config.password());
        hc.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hc.setMaximumPoolSize(config.poolMaxSize());
        hc.setMinimumIdle(0);
        hc.setConnectionTimeout(10_000);
        hc.setIdleTimeout(60_000);
        hc.setMaxLifetime(600_000);
        // The single most important setting for this plugin: never validate a
        // connection at pool construction. A negative value makes the pool start
        // immediately and connect lazily, so an unreachable MySQL at boot can
        // neither block nor fail proxy startup.
        hc.setInitializationFailTimeout(-1);

        this.dataSource = new HikariDataSource(hc);
    }

    private static String buildJdbcUrl(PluginConfig c) {
        return "jdbc:mysql://" + c.host() + ":" + c.port() + "/" + c.database()
                + "?useSSL=" + c.useSsl()
                // Required for caching_sha2_password auth over a plaintext connection.
                + "&allowPublicKeyRetrieval=" + (!c.useSsl())
                + "&characterEncoding=utf8"
                + "&connectTimeout=10000"
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
        // Bind the count twice (insert value + update value) rather than using
        // the deprecated VALUES() function, keeping the statement portable.
        String sql = "INSERT INTO `" + networkTable + "` (id, total, updated_at) "
                + "VALUES (1, ?, CURRENT_TIMESTAMP) "
                + "ON DUPLICATE KEY UPDATE total = ?, updated_at = CURRENT_TIMESTAMP";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, total);
            ps.setInt(2, total);
            ps.executeUpdate();
        }
    }

    private void upsertServers(Connection conn, Map<String, Integer> perServer) throws SQLException {
        if (perServer.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO `" + serversTable + "` (server, players, updated_at) "
                + "VALUES (?, ?, CURRENT_TIMESTAMP) "
                + "ON DUPLICATE KEY UPDATE players = ?, updated_at = CURRENT_TIMESTAMP";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map.Entry<String, Integer> entry : perServer.entrySet()) {
                ps.setString(1, entry.getKey());
                ps.setInt(2, entry.getValue());
                ps.setInt(3, entry.getValue());
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
