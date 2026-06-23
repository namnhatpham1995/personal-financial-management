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
| Testing | JUnit 5, Mockito (unit), Testcontainers (integration), JaCoCo coverage gate; Vitest (frontend) |
| Infrastructure | Docker, Docker Compose, GitHub Actions CI/CD |

## Architecture Highlights

- **Package-by-feature** — each domain (`auth`, `account`, `transaction`, `budget`, `recurring`, `analytics`) owns its `domain/`, `repository/`, `service/`, and `web/` layers
- **Per-user data isolation** — unowned resources return 404, not 403, to avoid enumeration attacks
- **Balance consistency** — `current_balance` maintained via `adjustBalance(delta)` on every transaction mutation; `recomputeBalance()` available as a safety-net
- **Idempotent recurring generation** — unique constraint `(recurring_id, occurrence_date)` prevents duplicate transactions on scheduler retry
- **Rate limiting** — Bucket4j per-IP limit on all `/auth/**` endpoints
- **Structured logging** — Logstash JSON encoder in prod/test; correlation ID on every request
- **Durable audit log** — every authenticated mutation written to `audit_log` (PostgreSQL) in a dedicated `REQUIRES_NEW` transaction; committed immediately after the business write, never rolled back with it

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

> **MongoDB note:** `SPRING_DATA_MONGODB_URI` is required for the Receipt & Statement Vault. Set it to `mongodb://<USER>:<PASS>@<HOST>:27017/fintrack_vault?authSource=admin` — Railway's `MONGO_URL` omits the database name, so you must append `/fintrack_vault` manually.

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

GitHub Actions runs four sequential jobs on every push to `main` (PRs run backend + frontend only):

| Job | What it does |
|---|---|
| **Backend** | `mvn clean verify` with a live PostgreSQL 16 service; uploads Surefire + JaCoCo reports |
| **Frontend** | Type-check → lint → Vitest → design-token gate (no raw palette classes) → `next build` |
| **Docker** | `docker compose build --no-cache` — verifies the full image stack compiles |
| **Deploy** | `railway up` to Railway — gated on all three prior jobs passing on `main` |

The design-token gate fails the build if any `.tsx`/`.ts` file uses raw Tailwind palette classes (`text-slate-*`, `bg-gray-*`, etc.) instead of token-backed utilities.

## API Overview

| Resource | Endpoints |
|---|---|
| Auth | POST /auth/register, /auth/login, /auth/refresh, /auth/logout, GET /auth/me |
| Accounts | CRUD + POST /{id}/recompute-balance |
| Categories | CRUD (system categories read-only) |
| Transactions | CRUD + paginated list with filters |
| Budgets | CRUD with real-time progress (spent/remaining/%) |
| Recurring | CRUD + POST /{id}/pause, /{id}/resume |
| Analytics | GET spending-by-category, income-vs-expense, budget-progress, net-worth |
| Vault | POST /vault/upload, GET /vault, GET /vault/{id}, GET /vault/{id}/download, PATCH /vault/{id}/link, POST /vault/search, DELETE /vault/{id} |
| Statement Import | POST /vault/import/upload, GET /vault/import/{id}/rows, POST /vault/import/{id}/confirm |

Full interactive docs available at `/swagger-ui.html` when the backend is running.

## Project Structure

```
fintrack/
├── backend/                  # Spring Boot application
│   ├── src/main/java/com/fintrack/
│   │   ├── FintrackApplication.java
│   │   ├── auth/             # Authentication & JWT
│   │   ├── account/          # Account management
│   │   ├── category/         # Transaction categories
│   │   ├── transaction/      # Transaction CRUD
│   │   ├── budget/           # Budget tracking
│   │   ├── recurring/        # Recurring transaction scheduler
│   │   ├── analytics/        # Dashboard aggregations
│   │   ├── vault/            # Receipt & Statement Vault (MongoDB + GridFS)
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
├── docker-compose.yml
└── .github/workflows/ci.yml
```
