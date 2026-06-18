# Fintrack — Personal Finance Management

A full-stack personal finance management application.

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.3.4, Spring Security, Flyway |
| Database (SQL) | PostgreSQL 16, DECIMAL(19,4) for all monetary values — system of record |
| Database (NoSQL) | MongoDB — append-only audit/activity log only |
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

## Why Two Databases (Polyglot Persistence)

This app deliberately uses **PostgreSQL and MongoDB side by side**. The point is not "use NoSQL" — it is matching each datastore to the workload it is actually good at, and being clear about where the boundary sits.

- **PostgreSQL is the system of record.** All business data — accounts, transactions, balances, budgets, categories — stays relational. Money requires ACID transactions and exact `DECIMAL(19,4)` arithmetic; balances are maintained transactionally. None of this is moved to MongoDB.
- **MongoDB holds only the audit/activity log.** Recording *who did what, when* is the opposite workload: append-only, never joined, queried by `(user, time)`, growing without bound, and **schema-varies-per-event** (a login event carries an IP; a budget edit carries before/after values). Forcing that into a relational table means a wide, NULL-heavy table or a `JSONB` column — i.e. documents with extra steps. It is a textbook document-store fit.

Capture is **additive and best-effort**: a single request interceptor writes the event after the business write succeeds, so existing services are untouched and a MongoDB outage never fails or rolls back the underlying operation.

**Tradeoff:** two datastores cost more to run, back up, and monitor. For a small product, Postgres `JSONB` could serve the same need with one database; the split pays off at scale and under audit/retention requirements. It is included here to demonstrate polyglot persistence and the judgment of *where not to use NoSQL* (transactions stay in SQL).

## Getting Started

### Prerequisites
- Java 21 JDK
- Node.js 20
- Docker & Docker Compose

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
npm run type-check && npm run lint
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
