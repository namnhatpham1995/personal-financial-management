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
└────────────────────────┬─────────────────────────────────┘
                         │ JDBC (Flyway migrations)
┌────────────────────────▼─────────────────────────────────┐
│                  PostgreSQL 16                            │
│   DECIMAL(19,4) monetary fields · per-user row security  │
└──────────────────────────────────────────────────────────┘
```

## Backend Package Structure

```
com.fintrack
├── auth/           domain, repository, service (JWT + refresh tokens), web
├── account/        domain, repository, service, mapper, web
├── category/       domain, repository, service, mapper, web
├── transaction/    domain, repository, service, mapper, web
├── budget/         domain, repository, service, mapper, web
├── recurring/      domain, repository, service (scheduler), mapper, web
├── analytics/      repository (aggregations), service, web
└── common/
    ├── config/     SecurityConfig, AppProperties, OpenApiConfig
    ├── domain/     TransactionType (shared enum)
    ├── dto/        ApiError, PageResponse
    ├── exception/  GlobalExceptionHandler + typed exceptions
    ├── logging/    CorrelationIdFilter
    ├── ratelimit/  AuthRateLimitFilter (Bucket4j)
    └── security/   JwtAuthenticationFilter, UserPrincipal
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

## Balance Maintenance

Every transaction mutation calls `AccountService.adjustBalance(accountId, delta)`:
- INCOME: `balance += amount`
- EXPENSE: `balance -= amount`
- TRANSFER: `from -= amount`, `to += amount`

`POST /accounts/{id}/recompute-balance` recomputes from transaction history as a safety-net.

## Recurring Transaction Scheduler

Daily at 01:00 UTC (`@Scheduled(cron="0 0 1 * * *")`):
1. Fetch all active definitions where `next_run_date <= today`
2. Insert transaction — unique constraint `(recurring_id, occurrence_date)` prevents duplicates
3. Advance `next_run_date` by `frequency × interval`
4. Deactivate if `end_date` passed or `max_occurrences` reached

## Database Schema

Key design decisions:
- `DECIMAL(19,4)` for all `amount`/`balance` columns — no floating point
- `user_id` FK on every domain table — enforced at service layer (not row-level security)
- Soft index on `transactions(user_id, transaction_date)` for range queries
- Unique `(user_id, name)` on accounts and categories
- Unique `(recurring_id, occurrence_date)` on transactions for idempotency
