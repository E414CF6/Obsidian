# Obsidian Stats Dashboard (`web/`)

A Next.js (`16.4.6`) web dashboard for **Obsidian** that visualizes player and server telemetry served by the plugin's [
`stats` module](../src/main/kotlin/obsidian/modules/stats/StatsModule.kt).

## Architecture

1. **Obsidian Plugin (`stats` module)**
    - Exposes an embedded HTTP REST server (default `0.0.0.0:4000`, configured via `stats.http.port` in `config.yml`).
    - Auto-generates a 2048-bit RSA keypair at `plugins/Obsidian/keys/` (`public_key.pem` and `private_key.pem`) on
      first startup.
    - Manageable in-game/console with `/serve status`, `/serve reload`, `/serve genkey`, and `/serve refresh` (aliases:
      `/statsapi`, `/stats`).
2. **Next.js API Routes (`/api/stats`, `/api/players/[id]`)**
    - Acts as a secure backend-for-frontend proxy to `REMOTE_API_URL`.
    - Signs every upstream request with `x-api-timestamp` (Unix ms) and `x-api-signature` (`SHA256withRSA` over
      `METHOD\nPATH\nTIMESTAMP`) via [`getRsaAuthHeaders`](src/lib/rsa-auth.ts), plus optional `x-api-key` verification.
    - Supports `?q=<query>` filtering on `/api/stats` across username, stripped nickname, and UUID.

## Prerequisites

- **Node.js 22+** and `npm`
- A running Paper server with **Obsidian** (`modules.stats: true` in `config.yml`)

## Local Development

```bash
cd web
cp .env.example .env.local
npm install
npm run dev
```

Open `http://localhost:3000` in your browser. By default, `.env.example` targets `http://127.0.0.1:4000` and points
`RSA_PRIVATE_KEY_PATH` at `../run/plugins/Obsidian/keys/private_key.pem`.

### Private Key Resolution Order

`src/lib/rsa-auth.ts` resolves the RSA private key in the following order:

1. `RSA_PRIVATE_KEY` environment variable (inline PEM string; literal `\n` escapes are normalized)
2. `RSA_PRIVATE_KEY_PATH` environment variable (absolute path or relative to `web/`)
3. Automatic fallback paths:
    - `web/keys/private_key.pem`
    - `../run/plugins/Obsidian/keys/private_key.pem`
    - `../plugins/Obsidian/keys/private_key.pem`
    - `../keys/private_key.pem`

## Environment Variables

Configure these in `web/.env.local` (or your hosting provider's environment settings):

| Variable | Required | Description |
| :--- | :---: | :--- |
| `REMOTE_API_URL` | Required | Base URL of the Obsidian `stats` HTTP API (e.g., `http://127.0.0.1:4000` or your HTTPS tunnel URL) |
| `RSA_PRIVATE_KEY_PATH` | Optional | Path to Obsidian's `private_key.pem` (for local/VM deployments) |
| `RSA_PRIVATE_KEY` | Optional | Inline PEM private key string (recommended for Vercel/cloud deployments) |
| `API_SECRET_KEY` | Optional | Shared secret sent in `x-api-key` when `stats.auth.api-key` is configured in Obsidian |
| `REQUIRE_API_KEY` | Optional | Set to `true` to also enforce `x-api-key` on Next.js `/api/*` routes |
| `NEXT_PUBLIC_SERVER_NAME` | Optional | Optional fallback title before/outside the API `meta.serverName` payload |

## Scripts

```bash
npm run dev        # Start Next.js development server
npm run typecheck  # Run TypeScript type check (tsc --noEmit)
npm run lint       # Run Next.js ESLint checks
npm run build      # Build production bundle
npm run start      # Serve production build
npm run gen-keys   # Generate standalone RSA keypair helper (scripts/generate-keys.mjs)
```

## Cloud Deployment (e.g., Vercel)

1. Expose your Paper server's `stats` HTTP port (`4000`) securely (for example via Cloudflare Tunnel or reverse proxy).
2. Set the project **Root Directory** to `web`.
3. Configure environment variables:
    - `REMOTE_API_URL`: Your public tunnel/reverse-proxy URL (e.g., `https://stats-api.example.com`)
    - `RSA_PRIVATE_KEY`: Contents of `plugins/Obsidian/keys/private_key.pem` with newlines escaped as `\n`
    - `API_SECRET_KEY` *(optional)*: Must match `stats.auth.api-key` in Obsidian's `config.yml` if enabled.
