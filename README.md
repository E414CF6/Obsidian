# Obsidian

**Obsidian** is an all-in-one server management plugin for **Paper `26.3`** (with **Folia**-compatible region
scheduling), written from the ground up in **Kotlin**. It unifies essential survival/SMP gameplay systems, economy,
moderation, block-level audit & rollback protection, proxy messaging, and an embedded RSA-authenticated web statistics
API in a single modular JAR.

## Tech Stack & Requirements

- **Server Software:** Paper `26.3` (Folia `folia-supported: true`, `load: POSTWORLD`)
- **Java Toolchain:** Java `25`
- **Language & Build:** Kotlin `2.4.21`, Kotlin Coroutines `1.10.2`, Gradle `9.9.0` (`Shadow` plugin)
- **Database:** HikariCP `6.3.0` supporting **SQLite** (default), **MySQL**, and **MariaDB**
- **Soft Dependencies:** `Vault`, `PlaceholderAPI`, `LuckPerms`
- **Optional Web Dashboard (`web/`):** Node.js & Next.js (`16.4.6`) consuming Obsidian's `stats` HTTP API

## Modules & Features

All **16 modules** can be individually toggled under `modules:` in `config.yml`, and root command aliases can be toggled
via `commands.register-standalone-aliases`.

| Module ID | Description | Primary Commands |
| :--- | :--- | :--- |
| `teleport` | Teleportation, TPA requests, back/death-back, spawn, and safe async random teleport | `/tp`, `/tphere`, `/tppos`, `/tpa`, `/tpahere`, `/back`, `/dback`, `/top`, `/jump`, `/spawn`, `/setspawn`, `/rt` |
| `home` | Player personal homes with permission-based limits and GUI | `/sethome`, `/home`, `/homes`, `/removehome` |
| `warp` | Server warps with permission checks and GUI | `/setwarp`, `/warp`, `/warps`, `/removewarp` |
| `utility` | Core player and administrative utility commands | `/gm`, `/fly`, `/god`, `/heal`, `/feed`, `/speed`, `/walkspeed`, `/flyspeed`, `/repair`, `/workbench` (`/wb`, `/craft`), `/ender`, `/hat`, `/clear`, `/ptime`, `/pweather`, `/give`, `/item`, `/sudo` |
| `economy` | Player balances, physical banknote cheques, item worth/selling, and Vault Economy provider | `/balance` (`/bal`), `/pay`, `/money`, `/baltop`, `/cheque`, `/worth`, `/setworth`, `/sell` |
| `kit` | Configurable kits with cooldowns, economy costs, max-use limits, GUI, and editor | `/kit` (`/kits`), `/kiteditor` |
| `playtime` | Session & daily playtime tracking, rank progression, and milestone rewards | `/playtime`, `/playtimetop`, `/rankup`, `/rankinfo`, `/prewards` |
| `moderation` | Bans/tempbans/IP-bans, mutes, warnings, jails, vanish, cuffs, inventory inspection/snapshots, and player info | `/ban`, `/tempban`, `/ipban`, `/unban`, `/checkban`, `/kick`, `/mute`, `/unmute`, `/warn`, `/warnings`, `/clearwarnings`, `/setjail`, `/deljail`, `/jail`, `/unjail`, `/cuff`, `/vanish`, `/inv`, `/invsave`, `/invload`, `/seen`, `/info`, `/sameip` |
| `chat` | Chat formatting, anti-spam/caps/word filter, private messages, nicknames, TabList, announcements, and custom text | `/msg`, `/reply`, `/msgtoggle`, `/socialspy`, `/nick`, `/realname`, `/mutechat`, `/clearchat`, `/staffmsg`, `/helpop`, `/broadcast`, `/titlemsg`, `/actionbarmsg`, `/ctext` |
| `visual` | Cuboid selections, teleport portals, TextDisplay holograms, ArmorStand editor, and sign/dynamic sign editors | `/pos1`, `/pos2`, `/sel`, `/setportal`, `/removeportal`, `/portals`, `/hologram`, `/astand`, `/signedit`, `/dsign` |
| `interaction` | Sitting, riding, player glow/scale customization, interactive block/entity commands, and item-attached PDC commands | `/sit`, `/ride`, `/shakeitoff`, `/glow`, `/scale`, `/ic`, `/attachcommand` |
| `automation` | Scheduled tasks, lifecycle event actions, AFK detection/kick, server status, entity/item cleanup, and conditional commands | `/schedule`, `/afk`, `/status` (`/tps`), `/groundclean`, `/killall`, `/checkperm`, `/ifonline`, `/ifoffline` |
| `proxy` | BungeeCord / Velocity plugin channel messaging (`bungeecord:main`) | `/server`, `/sendall`, `/bbroadcast` |
| `protect` | Block, container, entity, explosion, liquid, and chat/command logging with inspector mode, lookup, rollback/restore previews, undo, and purge | `/co` (aliases: `/core`, `/coreprotect`) — subcommands: `inspect`, `lookup` (`l`), `near`, `page`, `rollback` (`rb`), `restore` (`rs`), `apply`, `cancel`, `undo`, `purge`, `status`, `consumer`, `teleport` (`tp`), `reload` |
| `stats` | Native player/server telemetry aggregator (DB + vanilla `stats/` & `advancements/` JSON) and embedded RSA-SHA256 authenticated HTTP REST API | `/serve` (aliases: `/statsapi`, `/stats`) — subcommands: `status`, `reload`, `genkey`, `refresh` |
| `mobfarm` | Per-entity/group chunk limits, auto-cleaner, dynamic hopper throttling, redstone activity tracker, chunk block limits, and wolf control | `/mfm` (`clean`, `list`, `limits`, `hoppers`, `redstone`, `reload`), `/mobclean`, `/mobreport` |

Base administrative commands: `/obsidian [info|version|reload|modules]`.

## Core Architecture Highlights

- **Async & Folia-Safe Execution:** I/O and database queries run off the region/main thread via HikariCP and Kotlin
  coroutines, while world/entity actions dispatch through `ObsidianScheduler` (`GlobalRegionScheduler`,
  `RegionScheduler`, `EntityScheduler`, and `AsyncScheduler`).
- **Paper Brigadier Commands:** Commands are registered through Paper's `LifecycleEvents.COMMANDS` with shared cooldowns
  (`commands.cooldowns`) and movement-aware warmups (`commands.warmups`).
- **MiniMessage & Bilingual Locales:** Full Kyori Adventure MiniMessage support with built-in English
  (`messages_en.yml`) and Korean (`messages_ko.yml`) translations selectable via `language:` in `config.yml`.
- **Integrations:**
    - **Vault:** Registers Obsidian as a Vault `Economy` provider when `economy` is enabled.
    - **PlaceholderAPI:** Registers `%obsidian_*%` placeholders (balance, formatted balance, playtime, rank, AFK/vanish
      status, online count, etc.).
    - **LuckPerms:** Soft reflection hook to read primary group, prefix, and suffix without hard coupling.
    - **Discord Webhook:** Configurable webhook alerts for moderation or event actions.

## Configuration Files

On first startup, Obsidian extracts default configuration files into `plugins/Obsidian/`:

- `config.yml` — Language (`en`/`ko`), database storage (`sqlite` / `mysql` / `mariadb`), module toggles,
  cooldowns/warmups, chat/TabList/death messages, AFK, Protect logging toggles, and Stats HTTP server settings
- `kits.yml` — Starter/VIP kit definitions, cooldowns, costs, and items
- `ranks.yml` — Playtime-based rank progression tiers and milestone rewards
- `worth.yml` — Material sell/buy values for `/worth` and `/sell`
- `customtext.yml` — Custom multi-line pages served via `/ctext <name>`
- `schedules.yml` — Recurring automated command tasks
- `eventactions.yml` — Event-driven commands (e.g., join/quit/first-join hooks)
- `mobfarm.yml` — MobFarm entity/group limits, spawn reasons, auto-cleaner, hopper throttling, redstone tracking, and block limits
- `messages_en.yml` / `messages_ko.yml` — Localized MiniMessage strings

## Embedded Stats HTTP API & Web Dashboard

When the `stats` module is enabled, Obsidian starts an embedded HTTP REST server (default `0.0.0.0:4000`) and
automatically generates a 2048-bit RSA keypair under `plugins/Obsidian/keys/` (`public_key.pem`, `private_key.pem`):

- `GET /health` — Unauthenticated server health check
- `GET /api/stats[?q=<query>&refresh=true]` — Full server overview and player summaries
- `GET /api/overview` — Server-wide aggregated totals and rank distribution
- `GET /api/players/{uuidOrUsername}` — Single player breakdown (economy, rank, playtime, vanilla stats, advancements,
  biomes)

Protected endpoints require `x-api-timestamp` (Unix seconds or milliseconds) and `x-api-signature` (`SHA256withRSA`
Base64 signature over `METHOD\nPATH\nTIMESTAMP`), plus an optional `x-api-key` when `stats.auth.api-key` is configured.

A companion Next.js dashboard is included in `web/`. To run it locally against an active Obsidian server:

```bash
cd web
cp .env.example .env.local
npm install
npm run dev
```

See [web/README.md](web/README.md) for local and cloud proxy configuration details.

## Building & Testing

### Prerequisites

- **JDK 25** installed and available to Gradle Toolchains
- **Gradle 9.9.0** installed on your `PATH` (configured in `gradle/wrapper/gradle-wrapper.properties`)

### Build the Plugin JAR

```bash
gradle clean build
```

This compiles Kotlin sources, runs the JUnit 5 test suite, and packages the shaded plugin JAR (with `HikariCP` relocated
to `obsidian.libs.hikari`) at:

```text
build/libs/Obsidian-1.0-SNAPSHOT.jar
```

Drop `build/libs/Obsidian-1.0-SNAPSHOT.jar` into your Paper `26.3` server's `plugins/` folder.

### Run Unit & Integration Tests

```bash
gradle test
```

Test suites live under `src/test/kotlin/obsidian/` covering database repositories, helpers/parsers, protect
inspection/rollback queries, and the stats HTTP API / RSA auth pipeline.

### Launch a Local Paper `26.3` Test Server

```bash
gradle runServer
```

`runServer` (powered by `xyz.jpenilla.run-paper`) downloads/launches Paper `26.3` inside `run/` with Obsidian preloaded.

## Project Layout

```text
Obsidian/
├── build.gradle.kts            # Dependencies, Java 25 toolchain, Shadow & runPaper config
├── settings.gradle.kts         # Gradle root project settings
├── gradle.properties           # Version & Gradle performance flags
├── src/
│   ├── main/
│   │   ├── kotlin/obsidian/
│   │   │   ├── ObsidianPlugin.kt
│   │   │   ├── core/           # Config, commands, database, GUI, locale, scheduler, text, users
│   │   │   ├── integration/    # Vault, PlaceholderAPI, LuckPerms, Discord hooks
│   │   │   └── modules/        # 15 feature modules (teleport .. protect, stats)
│   │   └── resources/          # plugin.yml, config.yml, messages_*.yml, module defaults
│   └── test/kotlin/obsidian/   # JUnit 5 unit & SQLite/HTTP integration tests
└── web/                        # Companion Next.js statistics dashboard
```
