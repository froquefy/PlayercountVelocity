# PlayerCount

A small **Velocity** proxy plugin that keeps a **MySQL table of the players currently
online** across the network — each player's name and the backend server they're on — so
an external consumer (a website, dashboard, Discord bot, …) can read the live roster
straight from the database and compute whatever counts it needs.

The data comes from the proxy's own in-memory state (the same data `/glist` shows),
so **no backend (Paper/Spigot) plugins are required** — install it on the proxy and
you're done.

## How it works

The plugin is **event-driven**: it updates the table the instant a player's state
changes, so the table always holds exactly the players who are online right now.

| When | What happens |
| --- | --- |
| Player joins a backend / switches servers | Row inserted (or its `server` updated) |
| Player disconnects from the proxy | Row deleted |
| Proxy shuts down | Table emptied (everyone is offline) |

There is **one table**:

| Table | Rows | Columns |
| --- | --- | --- |
| `<prefix>players` | one per online player | `id`, `nick`, `server` |

Default `<prefix>` is `playercount_`, so the table is `playercount_players`. The plugin
creates it automatically on first successful write — you don't run any SQL by hand.

On top of the events, a periodic **reconcile** (every `reconcile-interval-seconds`)
rebuilds the table from the proxy's live player list. It's a safety net: it repairs any
drift left by a write that failed while MySQL was briefly down, and clears phantom rows
after an unclean shutdown. Normal operation doesn't wait for it — joins and quits are
reflected immediately.

## Reliability (why a DB outage won't hurt your proxy)

Rebooting a proxy kicks everyone, so this plugin is built to **never need a restart for
database problems**:

- The connection pool starts lazily — an **unreachable MySQL at boot cannot block or
  fail proxy startup**.
- Every write is best-effort and off the event threads: a DB error is **caught, logged
  once, and dropped**; the next event or reconcile retries. The proxy and its players
  are unaffected.
- When the database comes back, recording **resumes on its own** and the next reconcile
  restores the full list — no restart, no command. A single `MySQL write recovered` line
  confirms it.

Config changes no longer need a restart either — see [Commands](#commands-and-permissions).

## Requirements

- **Velocity** 3.x (built and boot-tested against **3.4.0**).
- **Java 17+** (whatever your Velocity runs on).
- **MySQL 8.0+** reachable from the proxy. (MariaDB 10.x also works — the SQL avoids
  MySQL-only constructs.)

## Install

1. Build the jar (see [Building](#building)) or grab `velocity-playercount-<version>.jar`.
2. Drop it into your proxy's `plugins/` folder.
3. Start the proxy once. The plugin writes a default config to
   `plugins/playercount/config.properties` and (harmlessly) logs that it can't reach
   MySQL yet.
4. Edit `plugins/playercount/config.properties` with your database details.
5. Run `/playercount reload` in the console (or restart). You should see
   `PlayerCount enabled - tracking the online player list …` and rows appearing as
   players move around.

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
| `reconcile-interval-seconds` | `30` | Safety-net full-reconcile interval (minimum 5). Joins/quits update instantly regardless. |
| `table-prefix` | `playercount_` | Prefix for the table (sanitised to `[A-Za-z0-9_]`) |
| `pool-size` | `2` | JDBC connection-pool size |
| `connection-timeout-ms` | `10000` | Wait for a DB connection before giving up a write (minimum 1000) |

> Configs written by 1.x used `poll-interval-seconds`; that key is still read as a
> fallback, so an old config keeps working.

The database named by `mysql-database` must exist; the **table** is created
automatically. The MySQL user needs `CREATE`, `INSERT`, `UPDATE`, `DELETE`, and `SELECT`
on that database.

## Commands and permissions

| Command | Permission | Effect |
| --- | --- | --- |
| `/playercount reload` | `playercount.reload` | Re-reads `config.properties` and re-applies it (reconnects MySQL, reschedules the reconcile) with no proxy restart. The console always has permission. |

If the config fails to parse on reload, the previous config stays active and the error
is reported in the console — a typo can't take recording down.

## Reading the data from your website

Everything is derived from the one table, so you compute counts yourself:

```sql
-- Network-wide online total.
SELECT COUNT(*) AS total FROM playercount_players;

-- Per-server breakdown.
SELECT server, COUNT(*) AS players
FROM playercount_players
GROUP BY server
ORDER BY players DESC;

-- Who is online, and where.
SELECT nick, server FROM playercount_players ORDER BY server, nick;

-- Is a specific player online? (empty result = offline)
SELECT server FROM playercount_players WHERE nick = ?;
```

Notes:

- The table holds **online players only**; a player not in it is offline. There is no
  history and no timestamp — it's a live snapshot by design.
- If the proxy **crashes** (no clean shutdown) it can't empty the table, so stale rows
  may linger until it restarts (the first reconcile then clears them). If your site must
  distinguish "live" from "stale proxy", gate on something external (e.g. a proxy
  heartbeat) rather than assuming the table is always current.

## Schema (for reference)

```sql
CREATE TABLE playercount_players (
    id     INT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    nick   VARCHAR(32) NOT NULL UNIQUE,   -- one row per online player
    server VARCHAR(64) NOT NULL           -- backend name from velocity.toml
);
```

## Building

```bash
./gradlew shadowJar        # -> build/libs/velocity-playercount-<version>.jar
./gradlew test             # config-parsing / sanitisation unit tests
```

Targets Java 17 bytecode so the jar runs on any Velocity-supported JVM.

## Troubleshooting

- **`Could not write the online player list to MySQL: …`** — the plugin can't reach the
  database. Check host/port/credentials and that `mysql-database` exists. The proxy keeps
  running; fix the DB and recording resumes automatically.
- **No rows appear** — confirm the configured user can `CREATE TABLE` in the database,
  and that you ran `/playercount reload` (or restarted) after editing the config.
- **Stale rows after a crash** — expected; the next startup reconcile clears them. See the
  note under [Reading the data](#reading-the-data-from-your-website).
- **`Access denied` / auth plugin errors** — for MySQL 8's default `caching_sha2_password`
  over a non-TLS connection the plugin already sets `allowPublicKeyRetrieval=true`; make
  sure the credentials are correct.
