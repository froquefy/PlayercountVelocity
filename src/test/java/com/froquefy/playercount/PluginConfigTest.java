package com.froquefy.playercount;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginConfigTest {

    @Test
    void writesDefaultTemplateOnFirstLoad(@TempDir Path dir) {
        PluginConfig config = PluginConfig.load(dir);

        assertTrue(Files.exists(dir.resolve("config.properties")), "default config should be created");
        assertEquals("localhost", config.host());
        assertEquals(3306, config.port());
        assertEquals(30, config.intervalSeconds());
        assertEquals("playercount_", config.tablePrefix());
        assertFalse(config.useSsl());
    }

    @Test
    void parsesCustomValues(@TempDir Path dir) throws IOException {
        write(dir, """
                mysql-host=db.example.com
                mysql-port=3307
                mysql-database=web
                mysql-username=app
                mysql-password=s3cret
                mysql-use-ssl=true
                poll-interval-seconds=15
                table-prefix=site_
                pool-size=4
                connection-timeout-ms=20000
                """);

        PluginConfig config = PluginConfig.load(dir);

        assertEquals("db.example.com", config.host());
        assertEquals(3307, config.port());
        assertEquals("web", config.database());
        assertEquals("app", config.username());
        assertEquals("s3cret", config.password());
        assertTrue(config.useSsl());
        assertEquals(15, config.intervalSeconds());
        assertEquals("site_", config.tablePrefix());
        assertEquals(4, config.poolSize());
        assertEquals(20000, config.connectionTimeoutMs());
    }

    @Test
    void sanitizesTablePrefixToIdentifierChars(@TempDir Path dir) throws IOException {
        write(dir, "table-prefix=foo`; DROP TABLE x;--bar\n");

        PluginConfig config = PluginConfig.load(dir);

        // Backticks, spaces, semicolons and dashes are stripped, defusing injection.
        assertEquals("fooDROPTABLExbar", config.tablePrefix());
    }

    @Test
    void fallsBackWhenPrefixSanitizesToEmpty(@TempDir Path dir) throws IOException {
        write(dir, "table-prefix=!!!\n");

        assertEquals("playercount_", PluginConfig.load(dir).tablePrefix());
    }

    @Test
    void clampsIntervalAndPoolToSafeMinimums(@TempDir Path dir) throws IOException {
        write(dir, """
                poll-interval-seconds=1
                pool-size=0
                connection-timeout-ms=10
                """);

        PluginConfig config = PluginConfig.load(dir);

        assertEquals(5, config.intervalSeconds());
        assertEquals(1, config.poolSize());
        assertEquals(1000, config.connectionTimeoutMs());
    }

    @Test
    void recoversFromGarbageNumbers(@TempDir Path dir) throws IOException {
        write(dir, """
                mysql-port=not-a-number
                poll-interval-seconds=abc
                """);

        PluginConfig config = PluginConfig.load(dir);

        assertEquals(3306, config.port());
        assertEquals(30, config.intervalSeconds());
    }

    private static void write(Path dir, String contents) throws IOException {
        Files.writeString(dir.resolve("config.properties"), contents, StandardCharsets.UTF_8);
    }
}
