# Fintrack — Personal Finance Management

A full-stack personal finance management application.

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.3.4, Spring Security, Flyway |
| Database (SQL) | PostgreSQL 16, DECIMAL(19,4) for all monetary values — system of record |
| Database (NoSQL) | MongoDB — reserved for Receipt & Statement Vault (currently idle) |
| Auth | JWT (JJWT 0.12.6), rotating refresh tokens, SHA-256 hashed storage |
| Frontend | Next.js 14 (App Router), TypeScript, Tailwind CSS, Recharts |
| API Client | Axios with auto-refresh interceptor, TanStack Query |
| Testing | JUnit 5, Mockito (unit tests), Testcontainers (integration), JaCoCo coverage gate |
| Infrastructure | Docker, Docker Compose, GitHub Actions CI/CD |

## Architecture Highlights

- **Package-by-feature** — each domain (`auth`, `account`, `transaction`, `budget`, `recurring`, `analytics`) owns its `domain/`, `repository/`, `service/`, and `web/` layers
- **Per-user data isolation** — unowned resources return 404, not 403, to avoid enumeration attacks
- **Balance consistency** — `current_balance` maintained via `adjustBalance(delta)` on every transaction mutation; `recomputeBalance()` available as a safety-net
- **Idempotent recurring generation** — unique constraint `(recurring_id, occurrence_date)` prevents duplicate transactions on scheduler retry
- **Rate limiting** — Bucket4j per-IP limit on all `/auth/**` endpoints
- **Structured logging** — Logstash JSON encoder in prod/test; correlation ID on every request

## Why Two Databases

PostgreSQL is the sole system of record for all financial data and audit history. Accounts, transactions, balances, budgets, and the audit log all benefit from ACID guarantees and exact `DECIMAL(19,4)` arithmetic. Per-user data isolation is enforced at the service layer (every query filters by `user_id`); PostgreSQL row-level security is not used.

The audit log (`audit_log` table) captures every authenticated mutation durably in a dedicated `REQUIRES_NEW` transaction — committed to PostgreSQL immediately after the business write, never lost if downstream infrastructure is unavailable.

MongoDB is present as a dependency but currently idle. It is reserved for the planned Receipt & Statement Vault feature, where heterogeneous, document-shaped financial documents (bank statements, receipts, invoices) are a better fit than relational tables.

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

> **MongoDB note:** The MongoDB dependency is present but idle. `SPRING_DATA_MONGODB_URI` is not required for the application to start or for audit logging to work. When adding Receipt & Statement Vault, set this to `mongodb://<USER>:<PASS>@<HOST>:27017/fintrack_vault?authSource=admin` — Railway's `MONGO_URL` omits the database name, so you must append it manually.

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
npm run type-check && npm run lint && npm test
```

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
