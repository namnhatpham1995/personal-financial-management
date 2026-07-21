# Fintrack — Personal Finance Management

A full-stack personal finance management application.

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.3.4, Spring Security, Flyway |
| Database (SQL) | PostgreSQL 16, DECIMAL(19,4) for all monetary values — system of record |
| Database (NoSQL) | MongoDB — Receipt & Statement Vault (active): vault documents, GridFS binaries |
| Auth | JWT (JJWT 0.12.6), rotating refresh tokens, SHA-256 hashed storage |
| Frontend | Next.js 14 (App Router), TypeScript, Tailwind CSS, Recharts |
| API Client | Axios with auto-refresh interceptor, TanStack Query |
| Receipt Ingestion Agent | LangGraph.js, `@langchain/anthropic` — see [Receipt Ingestion Agent](#receipt-ingestion-agent) |
| Testing | JUnit 5, Mockito (unit), Testcontainers (integration), JaCoCo coverage gate; Vitest (frontend, agent-service) |
| Infrastructure | Docker, Docker Compose, GitHub Actions CI/CD |

## Architecture Highlights

- **Package-by-feature** — each domain (`auth`, `account`, `transaction`, `budget`, `recurring`, `analytics`) owns its `domain/`, `repository/`, `service/`, and `web/` layers
- **Per-user data isolation** — unowned resources return 404, not 403, to avoid enumeration attacks
- **Balance consistency** — `current_balance` maintained via `adjustBalance(delta)` on every transaction mutation; `recomputeBalance()` available as a safety-net
- **Idempotent recurring generation** — unique constraint `(recurring_id, occurrence_date)` prevents duplicate transactions on scheduler retry
- **Rate limiting** — Bucket4j per-IP limit on all `/auth/**` endpoints
- **Structured logging** — Logstash JSON encoder in prod/test; correlation ID on every request
- **Durable audit log** — every authenticated mutation written to `audit_log` (PostgreSQL) in a dedicated `REQUIRES_NEW` transaction; committed immediately after the business write, never rolled back with it
- **Multi-language UI** — English, Vietnamese, German, Chinese (Simplified); browser-language default, remembered once changed, synced across devices for signed-in users. See [`docs/system-architecture.md`](docs/system-architecture.md#internationalization-i18n) for how it works and how to add a language.
- **What's New changelog** — a login/app-open notification for user-visible updates, plus a permanent review page; per-user seen-state synced across devices. See [`docs/system-architecture.md`](docs/system-architecture.md#whats-new-changelog).

## Why Two Databases

PostgreSQL is the sole system of record for all financial data and audit history. Accounts, transactions, balances, budgets, and the audit log all benefit from ACID guarantees and exact `DECIMAL(19,4)` arithmetic. Per-user data isolation is enforced at the service layer (every query filters by `user_id`); PostgreSQL row-level security is not used.

The audit log (`audit_log` table) captures every authenticated mutation durably in a dedicated `REQUIRES_NEW` transaction — committed to PostgreSQL immediately after the business write, never lost if downstream infrastructure is unavailable.

MongoDB backs the Receipt & Statement Vault. Vault documents (`vault_documents` collection) hold structured payloads of arbitrary shape — line items from receipts, parsed statement rows, merchant metadata — without requiring relational schema migrations for each new source format. Raw binaries (receipt images, CSV/OFX files) are stored in GridFS. Only the audit trail, account balances, and confirmed transactions live in PostgreSQL.

## Getting Started

### Prerequisites
- Java 21 JDK
- Node.js 20
- Docker & Docker Compose

### Railway Deployment

Set these environment variables on the **backend service**:

| Variable | Value |
|---|---|
| `DB_URL` | Railway PostgreSQL `DATABASE_URL` (jdbc format) |
| `JWT_SECRET` | 32+ char random secret |
| `CORS_ALLOWED_ORIGINS` | Your frontend URL |
| `SPRING_DATA_MONGODB_URI` | *(optional)* MongoDB URI — only required when Receipt & Statement Vault is enabled |
| `SPRING_DATA_REDIS_URL` | Redis URL from Railway Redis plugin: `${{Redis.REDIS_URL}}` |
| `APP_AGENT_SERVICE_URL` | *(optional)* Base URL of the **agent-service** Railway deployment, e.g. `https://agent-service.up.railway.app`. Leave unset to keep the Receipt Ingestion Agent dark — every `/agent-runs` endpoint reports the feature unavailable and nothing else is affected. |

> **MongoDB note:** `SPRING_DATA_MONGODB_URI` is required for the Receipt & Statement Vault. Set it to `mongodb://<USER>:<PASS>@<HOST>:27017/fintrack_vault?authSource=admin` — Railway's `MONGO_URL` omits the database name, so you must append `/fintrack_vault` manually.

Set these on a **separate `agent-service` Railway service** (only needed if you want the Receipt Ingestion Agent enabled):

| Variable | Value |
|---|---|
| `BACKEND_API_URL` | The backend service's Railway URL |
| `LLM_PROVIDER` | `anthropic` to call the real model, or `stub` (default) for deterministic fixtures — never set `stub` in a real deployment users interact with |
| `ANTHROPIC_API_KEY` | Required when `LLM_PROVIDER=anthropic` |
| `ANTHROPIC_MODEL` | *(optional)* defaults to `claude-sonnet-4-5` |
| `CHECKPOINTER_DB_URL` | Same Postgres `DATABASE_URL` the backend uses — the agent's LangGraph checkpointer lives in its own schema (`agent_checkpoints`) in that instance, no separate database needed |
| `PORT` | `8081` (or whatever Railway assigns — the service binds to `process.env.PORT`) |

### Quick Start (Docker)

```bash
cp .env.example .env
# Set a real JWT_SECRET in .env
docker compose up
```

- Frontend: http://localhost:3000
- Backend API: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html

### Local Development

**Backend:**
```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=local"
```

**Frontend:**
```bash
cd frontend
npm install
npm run dev
```

### Running Tests

**Backend:**
```bash
cd backend
mvn clean verify
```

**Frontend:**
```bash
cd frontend
npm run type-check && npm run lint && npm test   # one-shot (same as CI)
npm run test:watch                               # watch mode during development
```

## CI/CD Pipeline

GitHub Actions runs six jobs on every push to `main` (PRs run backend + frontend + MCP server + agent-service only):

| Job | What it does |
|---|---|
| **Backend** | `mvn clean verify` with a live PostgreSQL 16 service; checks `mcp-server/openapi.json` matches the live OpenAPI contract; uploads Surefire + JaCoCo reports |
| **Frontend** | Type-check → lint → Vitest → design-token gate (no raw palette classes) → `next build` |
| **MCP Server** | `npm install` → type-check → Vitest → checks generated API types are up to date → `npm run build` in `mcp-server/` |
| **Agent Service** | `npm install` → type-check → Vitest (unit, graph-level via a real Postgres Testcontainer, contract tests — all against `LLM_PROVIDER=stub`, no live model call in CI) → `npm run build` in `agent-service/` |
| **Docker** | `docker compose build --no-cache` — verifies the full image stack compiles |
| **Deploy** | `railway up` to Railway — gated on all prior jobs passing on `main` |

The design-token gate fails the build if any `.tsx`/`.ts` file uses raw Tailwind palette classes (`text-slate-*`, `bg-gray-*`, etc.) instead of token-backed utilities.

## API Overview

| Resource | Endpoints |
|---|---|
| Auth | POST /auth/register, /auth/login, /auth/refresh, /auth/logout, GET /auth/me, PUT /auth/me/language, PUT /auth/me/changelog-seen |
| Accounts | CRUD + POST /{id}/recompute-balance |
| Categories | CRUD (system categories read-only) |
| Transactions | CRUD + paginated list with filters |
| Budgets | CRUD with real-time progress (spent/remaining/%) |
| Recurring | CRUD + POST /{id}/pause, /{id}/resume |
| Analytics | GET spending-by-category, incoming-transfer-total, income-vs-expense, budget-progress, balances, overview |
| API Tokens | POST/GET /tokens, DELETE /tokens/{id} — scoped personal access tokens for API/MCP clients (JWT session only) |
| Vault | POST /vault/upload, GET /vault, GET /vault/{id}, GET /vault/{id}/download, PATCH /vault/{id}/link, POST /vault/search, DELETE /vault/{id} |
| Statement Import | POST /vault/import/upload, GET /vault/import/{id}/rows, POST /vault/import/{id}/confirm |
| Agent Runs | POST /agent-runs, GET /agent-runs, GET /agent-runs/{id}, POST /agent-runs/{id}/decision — see [Receipt Ingestion Agent](#receipt-ingestion-agent) |

Full interactive docs available at `/swagger-ui.html` when the backend is running.

### Idempotent Mutations

Every protected create/mutate endpoint (accounts, categories, budgets, recurring transactions, transactions, transaction batches, API tokens, vault/statement uploads, statement confirmation) accepts a caller-supplied `Idempotency-Key` header. Retrying with the same key and the same payload replays the original response (`Idempotency-Replayed: true` header, no side effect repeated); the same key with a different payload gets a typed `409` conflict; a key still owned by a concurrent in-flight request returns a `409` with `Retry-After`. Completed claims are retained for **seven days**, after which a repeated key is treated as new.

PAT creation and refresh-token rotation are protected the same way, but their responses carry a secret that is never persisted — a same-key retry always gets a typed `409` conflict with non-secret recovery guidance instead of a replayed body, rather than risk re-exposing (or silently withholding) a credential.

Whether the header is *required* is controlled by `app.idempotency.mode` (`ACCEPT | OBSERVE | ENFORCE`), defaulting to `OBSERVE` — a missing key is still allowed through, but recorded via metrics/logs so an operator can confirm real clients already send it before flipping to `ENFORCE` (which rejects a missing key with `400` before any side effect). See [`docs/system-architecture.md`](docs/system-architecture.md#idempotency--replay-safety) for the full claim-store design, retention/cleanup jobs, and observability details.

## Receipt Ingestion Agent

An LLM-driven agent (`agent-service/`, TypeScript + LangGraph.js) turns a Vault receipt into
proposed transactions: `extract → categorize → validate → interrupt (human review) → commit`.
No transaction is ever created without the user reviewing and explicitly approving the
proposals in the [Receipts](frontend/src/app/dashboard/receipts) review UI — the graph pauses
(`AWAITING_REVIEW`) and stays paused, surviving an agent-service restart via a Postgres-backed
checkpointer, until a decision is made.

The backend (not the agent) is the system of record for run status and remains authoritative
for every proposal: deterministic checks (category/account ownership, totals reconciliation,
currency, date plausibility) run again server-side regardless of what the agent submits, so a
compromised or buggy agent process can't slip bad data into a transaction.

**Dark by default** — with no `APP_AGENT_SERVICE_URL` configured on the backend, every
`/agent-runs` endpoint reports the feature unavailable and no other capability is affected.
Locally, `docker compose up` starts `agent-service` with `LLM_PROVIDER=stub` (deterministic
fixtures, no external calls) unless you set `ANTHROPIC_API_KEY` — see the Railway env var
tables above for enabling it against the real Anthropic API in a deployed environment.

More detail: [`docs/system-architecture.md`](docs/system-architecture.md#receipt-ingestion-agent).

## Project Structure

```
fintrack/
├── backend/                  # Spring Boot application
│   ├── src/main/java/com/fintrack/
│   │   ├── FintrackApplication.java
│   │   ├── auth/             # Authentication & JWT
│   │   ├── apitoken/         # Personal access tokens (API/MCP client auth)
│   │   ├── account/          # Account management
│   │   ├── category/         # Transaction categories
│   │   ├── transaction/      # Transaction CRUD
│   │   ├── budget/           # Budget tracking
│   │   ├── recurring/        # Recurring transaction scheduler
│   │   ├── analytics/        # Dashboard aggregations
│   │   ├── vault/            # Receipt & Statement Vault (MongoDB + GridFS)
│   │   ├── agent/            # Receipt ingestion run lifecycle (agent_run table, agent-token auth)
│   │   └── common/           # Security, config, exception handling
│   └── src/main/resources/
│       ├── db/migration/     # Flyway SQL migrations
│       └── application.yml
├── frontend/                 # Next.js application
│   └── src/
│       ├── app/              # App Router pages
│       ├── components/       # Shared UI components
│       ├── services/         # API service layer
│       └── lib/              # Auth context, API client, utils
├── mcp-server/                # MCP server (AI clients via a scoped API token — see mcp-server/README.md)
├── agent-service/             # Receipt Ingestion Agent (LangGraph.js) — see "Receipt Ingestion Agent" above
│   └── src/
│       ├── graph/             # extract -> categorize -> validate -> interrupt -> commit
│       ├── llm/                # LlmProvider interface + anthropic/stub implementations
│       └── schemas.ts          # zod schemas shared (via contract fixtures) with the backend DTOs
├── docker-compose.yml
└── .github/workflows/ci.yml
```
