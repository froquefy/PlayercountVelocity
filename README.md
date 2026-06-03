# PlayerCount

A small **Velocity** proxy plugin that records the live network player count — and a
per-backend-server breakdown — into **MySQL**, so an external consumer (a website,
dashboard, Discord bot, …) can read the numbers straight from the database.

The counts come from the proxy's own in-memory state (the same data `/glist` shows),
so **no backend (Paper/Spigot) plugins are required** — install it on the proxy and
you're done.

## How it works

Every `poll-interval-seconds` the plugin writes a **snapshot** (latest values only,
no history) to two tables:

| Table | Rows | Purpose |
| --- | --- | --- |
| `<prefix>network` | exactly one (`id = 1`) | the network-wide total |
| `<prefix>servers` | one per registered backend | per-server breakdown |

Default `<prefix>` is `playercount_`, so the tables are `playercount_network` and
`playercount_servers`. The plugin creates them automatically on first successful
write — you don't run any SQL by hand.

## Reliability (why a DB outage won't hurt your proxy)

Rebooting a proxy kicks everyone, so this plugin is built to **never need a restart
for database problems**:

- The connection pool starts lazily — an **unreachable MySQL at boot cannot block or
  fail proxy startup**.
- Every write is best-effort and wrapped: a DB error is **caught, logged once, and
  retried on the next interval**. The proxy and its players are unaffected.
- When the database comes back, recording **resumes on its own** — no restart, no
  command. A single `MySQL write recovered` line confirms it.

A restart is only needed to pick up **config changes** or a **new plugin jar**.

## Requirements

- **Velocity** 3.x (built and boot-tested against **3.4.0**).
- **Java 17+** (whatever your Velocity runs on).
- **MySQL 8.0+** reachable from the proxy. (MariaDB 10.x also works — the SQL avoids
  MySQL-only constructs.)

## Install

1. Build the jar (see [Building](#building)) or grab
   `velocity-playercount-<version>.jar`.
2. Drop it into your proxy's `plugins/` folder.
3. Start the proxy once. The plugin writes a default config to
   `plugins/playercount/config.properties` and (harmlessly) logs that it can't reach
   MySQL yet.
4. Edit `plugins/playercount/config.properties` with your database details.
5. Restart the proxy. You should see `PlayerCount enabled - recording counts every …`
   and, within one interval, rows appearing in your database.

## Configuration

`plugins/playercount/config.properties`:

| Key | Default | Meaning |
| --- | --- | --- |
| `mysql-host` | `localhost` | MySQL host |
| `mysql-port` | `3306` | MySQL port |
| `mysql-database` | `minecraft` | Database (schema) name; must already exist |
| `mysql-username` | `root` | MySQL user |
| `mysql-password` | *(empty)* | MySQL password |
| `mysql-use-ssl` | `false` | Set `true` if your server requires TLS |
| `poll-interval-seconds` | `30` | How often to write counts (minimum 5) |
| `table-prefix` | `playercount_` | Prefix for the two tables (sanitised to `[A-Za-z0-9_]`) |
| `pool-max-size` | `2` | JDBC connection-pool size |

The database named by `mysql-database` must exist; the **tables** are created
automatically. The MySQL user needs `CREATE`, `INSERT`, `UPDATE`, `DELETE`, and
`SELECT` on that database.

## Reading the data from your website

```sql
-- Network-wide total (single row) + when it was last refreshed.
SELECT total, updated_at FROM playercount_network WHERE id = 1;

-- Per-server breakdown.
SELECT server, players FROM playercount_servers ORDER BY players DESC;
```

Tips:

- `updated_at` lets you detect a **stale/offline proxy**: if it's older than a couple
  of intervals, the proxy probably isn't running — show `0` or "offline" instead of a
  stale number.
- The authoritative network total is `playercount_network.total` (the proxy's own
  count). The per-server rows are the breakdown; their sum can differ by a player or
  two during server transfers.
- Servers removed from `velocity.toml` are pruned from `playercount_servers` on the
  next write, so the table never shows phantom servers.

## Schema (for reference)

```sql
CREATE TABLE playercount_network (
    id         TINYINT   NOT NULL PRIMARY KEY,   -- always 1
    total      INT       NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE playercount_servers (
    server     VARCHAR(64) NOT NULL PRIMARY KEY, -- backend name from velocity.toml
    players    INT         NOT NULL,
    updated_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

## Building

```bash
./gradlew shadowJar        # -> build/libs/velocity-playercount-<version>.jar
./gradlew test             # config-parsing / sanitisation unit tests
```

Targets Java 17 bytecode so the jar runs on any Velocity-supported JVM.

## Local dev server

`run/boot-test.ps1` downloads Velocity, boots it with the freshly built plugin,
captures the log, and shuts it down cleanly — useful for verifying a change without
touching production. The downloaded proxy, generated config, and logs all live under
`run/` (git-ignored).

## Troubleshooting

- **`Could not write player counts to MySQL: …`** — the plugin can't reach the
  database. Check host/port/credentials and that `mysql-database` exists. The proxy
  keeps running; fix the DB and recording resumes automatically.
- **No rows appear** — confirm the configured user can `CREATE TABLE` in the database,
  and that you restarted after editing the config.
- **`Access denied` / auth plugin errors** — for MySQL 8's default
  `caching_sha2_password` over a non-TLS connection the plugin already sets
  `allowPublicKeyRetrieval=true`; make sure the credentials are correct.
