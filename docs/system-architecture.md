# System Architecture

## Overview

Fintrack is a three-tier web application:

```
┌──────────────────────────────────────────────────────────┐
│                       Browser                            │
│   Next.js 14 (App Router + TanStack Query + Recharts)    │
└────────────────────────┬─────────────────────────────────┘
                         │ HTTP/JSON (JWT Bearer)
┌────────────────────────▼─────────────────────────────────┐
│              Spring Boot 3.3.4 (Java 21)                 │
│  SecurityFilter → Controller → Service → Repository      │
│  Rate Limiting (Bucket4j) · Swagger UI · Actuator        │
└──────────┬──────────────────────────────┬────────────────┘
           │ JDBC (Flyway migrations)      │ Spring Data MongoDB
┌──────────▼──────────────┐  ┌────────────▼───────────────┐
│      PostgreSQL 16       │  │         MongoDB             │
│  Accounts, Transactions  │  │  vault_documents collection │
│  Budgets, Audit Log      │  │  GridFS (receipt binaries)  │
│  DECIMAL(19,4) amounts   │  │  Flexible payload schema    │
└─────────────────────────┘  └────────────────────────────┘
```

## Backend Package Structure

```
com.fintrack
├── auth/           domain, repository, service (JWT + refresh tokens), web
├── account/        domain, repository, service, mapper, web
├── category/       domain, repository, service, mapper, web
├── transaction/    domain, repository, service, mapper, web (account/date/type/category/note/currency filters)
├── budget/         domain, repository, service, mapper, web (currency-scoped since V6)
├── recurring/      domain, repository, service (scheduler), mapper, web
├── analytics/      repository (aggregations), service, web (per-currency, converted balances, converted overview)
├── vault/          domain, repository, service (GridFS, import, search), parser, web
├── exchangerate/   domain, repository, provider, service, scheduler (open.er-api.com cache)
├── apitoken/       domain, repository, service, web — scoped personal access tokens for API/MCP clients
└── common/
    ├── config/     SecurityConfig, AppProperties, OpenApiConfig, RestClientConfig, SchedulerConfig
    ├── domain/     TransactionType (shared enum)
    ├── dto/        ApiError, PageResponse
    ├── exception/  GlobalExceptionHandler + typed exceptions
    ├── logging/    CorrelationIdFilter
    ├── ratelimit/  AuthRateLimitFilter (Bucket4j)
    └── security/   JwtAuthenticationFilter, PatAuthenticationFilter, PatEndpointPolicy, UserPrincipal, AuthMethod
```

## Authentication Flow

```
Register/Login → AuthService → issueTokens()
  → generate 15-min access JWT (HS256)
  → generate 64-byte SecureRandom refresh token → SHA-256 hash → DB

Request → JwtAuthenticationFilter → extract Bearer → validate JWT
  → UserPrincipal(userId) → SecurityContext

Refresh → POST /auth/refresh → validate hash in DB
  → revoke old token → issue new pair (rotation)
  → detect reuse (revoked): revoke ALL user tokens
```

## Personal Access Token (PAT) Authentication

A second, independent credential type for non-browser clients (API scripts, the `mcp-server/` MCP adapter — see below). Sits alongside JWT sessions, not instead of them.

```
POST /api/v1/tokens (JWT session only) → ApiTokenService.create()
  → generate 256-bit SecureRandom, prefix "fintrack_pat_" → SHA-256 hash → DB
  → mandatory expiry (30/90/365 days) → plaintext returned once, never stored

Request with "Bearer fintrack_pat_..." → PatAuthenticationFilter (runs before JwtAuthenticationFilter)
  → hash lookup → reject unknown/expired/revoked → touch last_used_at
  → per-token Bucket4j rate limit (app.pat.requests-per-minute, default 60/min)
  → PatEndpointPolicy.isAllowed(method, uri, scope) — deny-by-default allowlist:
      read scope  → GET on accounts/transactions/categories/budgets/recurring-transactions/analytics
      write scope → additionally POST/PUT on transactions only
      always denied, any scope → /api/v1/auth/**, /api/v1/tokens/**, all DELETE, /api/vault/**
  → UserPrincipal(user, AuthMethod.PAT, tokenId) → SecurityContext

ActivityAuditInterceptor → when AuthMethod.PAT, audit_log.meta includes {"auth":"pat","tokenId":N}
```

Kill switch: `app.pat.enabled=false` (env `PAT_ENABLED=false`) disables the filter entirely without a schema rollback.

## MCP Server (`mcp-server/`)

A standalone Node/TypeScript process, external to the backend and frontend, that exposes Fintrack to MCP clients (Claude Desktop, Claude Code). Not deployed alongside the backend/frontend — it runs wherever the MCP client launches it (typically the user's own machine), pointed at a Fintrack backend URL via `FINTRACK_API_URL`.

```
MCP client (stdio) ⇄ fintrack-mcp-server ── HTTPS Bearer fintrack_pat_... ──▶ Backend (PatAuthenticationFilter)
```

- Holds no database connection and no credential beyond the one configured PAT — every tool call is a plain REST request through the same PatAuthenticationFilter / PatEndpointPolicy path described above, so the token's scope and the deny-by-default allowlist bound it exactly as they would any other API client.
- Curated tool surface only — reads (`list_accounts`, `get_account`, `list_categories`, `list_transactions`, `get_transaction`, `list_budgets_with_progress`, `get_spending_by_category`, `get_income_vs_expense`, `get_account_balances`, `get_budget_history`), non-destructive writes gated on a `WRITE`-scoped token (`create_account`, `update_account`, `create_category`, `update_category`, `create_budget`, `update_budget`, `create_transaction`, `create_transactions_batch`, `update_transaction`) — no raw-request passthrough tool, no delete tool for any resource, no token/session management, no vault access.
- `create_transactions_batch` lets an agent enter up to 100 transactions in one call instead of hammering the per-token rate limit; each row independently reports `CREATED` / `SKIPPED_DUPLICATE` / `FAILED` so a batch with one bad row still commits the rest.
- 429 responses carry retry guidance (`Retry-After` / `retryAfterSeconds`) which the MCP layer maps into a credential-safe "retry after N seconds" message instead of a bare rate-limit error.
- Mutation results may carry non-blocking warnings (e.g. `account_balance_negative`, `possible_duplicate_transaction`) — the operation still succeeds; warnings are visibility, not a second validation gate.
- Errors are mapped to short, credential-safe messages (never the token, headers, or a stack trace); tool descriptions tell the model returned financial data is data, not instructions.
- The full agent workflow (multi-currency setup, batch entry, correction, historical budget review) is covered end to end by `mcp-server/src/test/backend-integration.test.ts` against a real backend, run in CI's `mcp-integration` job.
- See `mcp-server/README.md` for setup and the full security posture.

## Balance Maintenance

Every transaction mutation calls `AccountService.adjustBalance(accountId, delta)`:
- INCOME: `balance += amount`
- EXPENSE: `balance -= amount`
- TRANSFER: `from -= amount`, `to += amount`

`POST /accounts/{id}/recompute-balance` recomputes from transaction history as a safety-net.

## Recurring Transaction Scheduler

Daily at 01:00 UTC (`@Scheduled(cron="0 0 1 * * *")`):
1. `RecurringTransactionScheduler` fetches all active definitions where `next_run_date <= today`
2. Delegates each definition to `RecurringOccurrenceProcessor.process()` (separate bean with `@Transactional` boundary)
   - Pre-check `existsByRecurringIdAndOccurrenceDate()` before insert — avoids PostgreSQL aborted-connection state that catching `DataIntegrityViolationException` would produce
   - Apply balance delta only on a new insert (no double-apply)
   - Advance `next_run_date` by `frequency × interval`
   - Deactivate if `end_date` passed or `max_occurrences` reached

**Design note**: Per-occurrence processing isolated in a separate injectable bean so the Spring AOP proxy applies the `@Transactional` boundary correctly (cross-bean delegation, not self-invocation).

## Receipt & Statement Vault

The vault is a polyglot persistence layer that stores financial documents alongside the relational core.

**Receipt flow:**
1. User uploads image/PDF → `POST /api/v1/vault/upload?type=RECEIPT`
2. Binary stored in GridFS; `VaultDocument` created in MongoDB (status: `ACTIVE`)
3. User links to an existing transaction → `PATCH /api/v1/vault/{id}/link?transactionId=...`
4. Transaction row gains `source_document_id`; `VaultDocument.transactionId` is set (bidirectional)

**Statement import flow:**
1. User uploads CSV or OFX file → `POST /api/v1/vault/import/upload?accountId=...`
2. File stored in GridFS; parser runs (`CsvStatementParser` or `OfxStatementParser`)
3. Parsed rows stored in MongoDB as a `STAGED` `VaultDocument` payload for user review
4. User selects rows → `POST /api/v1/vault/import/{id}/confirm`
5. Each selected row inserted as a PostgreSQL transaction via `TransactionService` with a SHA-256 `importDedupKey`
6. Partial unique index on `transactions.import_dedup_key WHERE NOT NULL` silently skips duplicates on re-import
7. MongoDB document promoted to `ACTIVE`

**Cross-user isolation:** All repository queries include `userId` (Mongo: `findByIdAndUserId`; GridFS: metadata field).

## Audit Log

Every successful authenticated mutation (POST/PUT/DELETE returning 2xx) writes one row to the `audit_log` table in PostgreSQL. The write happens in a dedicated `REQUIRES_NEW` transaction immediately after the business response is committed, ensuring the business operation is never affected by an audit failure.

`audit_log` schema: `id`, `user_id`, `action` (e.g. `accounts.created`), `ts`, `correlation_id`, `meta` (JSONB, action-specific fields). Indexed on `(user_id, ts DESC)` for paginated history queries.

The activity-history endpoint (`GET /api/v1/activity`) reads directly from this table, scoped to the requesting user.

## Data Stores

**PostgreSQL** is the sole system of record for all financial data and audit history. User isolation is enforced at the service layer: every query filters by `user_id` extracted from the JWT. PostgreSQL row-level security (RLS) is **not** used.

**MongoDB** backs the Receipt & Statement Vault (`vault_documents` collection + GridFS). The flexible document model accommodates heterogeneous payloads from different statement sources (CSV, OFX, manual receipts) without schema migrations. Nested line-item search uses a MongoDB aggregation pipeline to filter across arbitrarily-shaped `payload.lineItems` arrays. Raw binaries (images, CSV/OFX files) are stored in GridFS with `userId` metadata to enforce per-user access scoping. Confirmed statement rows are written to PostgreSQL via `TransactionService` so all balance math remains in the SQL layer; MongoDB holds the provenance, PostgreSQL holds the truth.

## Database Schema

Key design decisions:
- `DECIMAL(19,4)` for all `amount`/`balance` columns — no floating point
- `user_id` FK on every domain table — enforced at service layer (not PostgreSQL row-level security)
- Compound index on `audit_log(user_id, ts DESC)` for history queries
- Soft index on `transactions(user_id, transaction_date)` for range queries
- Unique `(user_id, name)` on accounts and categories
- Unique `(recurring_id, occurrence_date)` on transactions for idempotency
- Partial unique index on `transactions.import_dedup_key WHERE NOT NULL` — prevents duplicate rows on statement re-import
- `vault_documents` MongoDB collection indexed on `(userId, capturedAt DESC)` and `(userId, transactionId)` for common access patterns
- `exchange_rates(base_code, quote_code)` UNIQUE + `CHECK(rate > 0)` — `DECIMAL(19,10)` for sub-cent precision (VND); refreshed at most daily via ShedLock scheduler
- `budgets.currency VARCHAR(10) NOT NULL` — unique constraint is `(user_id, category_id, period, currency)` since V6; per-currency budget isolation

## Exchange Rate Service

Rates are fetched from `open.er-api.com/v6/latest/{base}` (free, no key) and cached in PostgreSQL.

```
ExchangeRateProvider (interface)
  └── OpenErApiExchangeRateProvider  →  GET open.er-api.com/v6/latest/USD
ExchangeRateService
  ├── convert(amount, from, to)  →  cache-backed cross-rate via USD base
  ├── refresh(base)              →  ON CONFLICT upsert + single-flight ReentrantLock
  └── supportedCurrencies()      →  cached quote codes + seed fallback
ExchangeRateRefreshScheduler  @Scheduled(01:30 UTC) + @SchedulerLock("exchangeRateRefresh")
```

- **No per-request external calls** — all conversions served from the `exchange_rates` cache.
- **ShedLock** (`shedlock` table, V8) ensures exactly one Railway replica runs the daily refresh.
- `stale` flag when `fetched_at` > 48 h; `ratesUnavailable` flag when a currency has no cached rate.
- `ExchangeRateUnavailableException` → HTTP 503 for the raw rates endpoint; caught internally by converted analytics/balance summary flows so dashboard views can degrade gracefully.

## Multi-Currency Analytics

`GET /api/v1/analytics/balances` returns native account-balance buckets grouped by currency. When called with `targetCurrency`, it returns a superset summary:

- `buckets`: unchanged native per-currency balances.
- `convertedTotal`: grand total converted into the requested target currency by `AnalyticsService` using `ExchangeRateService.convert()`.
- `rates`, `asOf`, `stale`, `ratesUnavailable`, and `excludedCurrencies`: conversion metadata for the dashboard's featured balance card.

`GET /api/v1/analytics/overview?targetCurrency=USD&from=...&to=...` remains available for converted trend/spending analytics:

- **Sum-then-convert** per source currency (not convert-then-sum) to prevent VND row truncation at scale 4.
- Missing-rate currencies reported in `excludedCurrencies` with native amounts — never silently dropped.
- `asOf` timestamp from the provider's `time_last_update_unix`; `stale=true` if cache is days old.
- Per-currency endpoints (`/analytics/spending-by-category`, `/analytics/income-vs-expense`, `/analytics/budget-progress`, `/analytics/balances`) remain the default source for the Overview sections.

The Overview frontend is currency-centric: `frontend/src/app/dashboard/page.tsx` builds one section per currency found in balances, trend rows, spending rows, or budget progress. Each section contains horizontal cash flow, spending donut, recent transactions, account boxes, and budget limits in that native currency. Recent transaction lists call `GET /api/v1/transactions` with `currency`, `type`, pagination, and date sorting so filtering happens before pagination instead of client-side.
