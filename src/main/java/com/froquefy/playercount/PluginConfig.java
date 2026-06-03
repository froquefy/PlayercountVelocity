package com.froquefy.playercount;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Plugin configuration, loaded from {@code config.properties} in the plugin's
 * data directory. On first run a commented default file is written, so an
 * operator never has to author it by hand.
 */
final class PluginConfig {

    private static final String FILE_NAME = "config.properties";

    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final boolean useSsl;
    private final int intervalSeconds;
    private final String tablePrefix;
    private final int poolSize;
    private final int connectionTimeoutMs;

    private PluginConfig(Properties p) {
        this.host = p.getProperty("mysql-host", "localhost").trim();
        this.port = parseInt(p.getProperty("mysql-port", "3306"), 3306);
        this.database = p.getProperty("mysql-database", "minecraft").trim();
        this.username = p.getProperty("mysql-username", "root").trim();
        this.password = p.getProperty("mysql-password", "");
        this.useSsl = Boolean.parseBoolean(p.getProperty("mysql-use-ssl", "false").trim());
        this.intervalSeconds = Math.max(5, parseInt(p.getProperty("poll-interval-seconds", "30"), 30));
        this.tablePrefix = sanitizeIdentifier(p.getProperty("table-prefix", "playercount_"), "playercount_");
        this.poolSize = Math.max(1, parseInt(p.getProperty("pool-size", "2"), 2));
        this.connectionTimeoutMs = Math.max(1000, parseInt(p.getProperty("connection-timeout-ms", "10000"), 10000));
    }

    /**
     * Loads the config from {@code <dataDirectory>/config.properties}, writing
     * the commented default first if the file does not yet exist.
     *
     * @throws UncheckedIOException if the file cannot be read or created
     */
    static PluginConfig load(Path dataDirectory) {
        try {
            Files.createDirectories(dataDirectory);
            Path file = dataDirectory.resolve(FILE_NAME);
            if (Files.notExists(file)) {
                Files.writeString(file, DEFAULT_TEMPLATE, StandardCharsets.UTF_8);
            }
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            }
            return new PluginConfig(p);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + FILE_NAME, e);
        }
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }

    String database() {
        return database;
    }

    String username() {
        return username;
    }

    String password() {
        return password;
    }

    boolean useSsl() {
        return useSsl;
    }

    int intervalSeconds() {
        return intervalSeconds;
    }

    String tablePrefix() {
        return tablePrefix;
    }

    int poolSize() {
        return poolSize;
    }

    int connectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * Strips everything but {@code [A-Za-z0-9_]} from a value that will be
     * interpolated into a SQL identifier (table name) where a bind parameter is
     * not possible — closing the only config-driven injection vector.
     */
    private static String sanitizeIdentifier(String raw, String fallback) {
        String cleaned = raw == null ? "" : raw.trim().replaceAll("[^A-Za-z0-9_]", "");
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private static final String DEFAULT_TEMPLATE = """
            # ============================================================
            #  PlayerCount  -  live network player counts  ->  MySQL
            # ============================================================
            # Edit the values below, then restart the proxy to apply them.
            #
            # A missing or unreachable database NEVER stops the proxy from
            # starting and never crashes it: counts simply aren't recorded
            # until MySQL is reachable again, at which point recording
            # resumes on its own with no restart needed.

            # --- MySQL connection ---
            mysql-host=localhost
            mysql-port=3306
            mysql-database=minecraft
            mysql-username=root
            mysql-password=
            # Set true only if your MySQL server requires TLS.
            mysql-use-ssl=false

            # --- Behaviour ---
            # How often (in seconds) to write counts. Minimum 5.
            poll-interval-seconds=30
            # Tables used / created:  <prefix>network  and  <prefix>servers
            # Only letters, digits and underscore are kept from this value.
            table-prefix=playercount_
            # JDBC connection-pool size. 2 is plenty for periodic writes.
            pool-size=2
            # How long (ms) to wait for a database connection before giving up a
            # write (it retries next interval). Minimum 1000.
            connection-timeout-ms=10000
            """;
}
