# Codebase Summary

## Backend — Key Files

### Entry Point
- `FintrackApplication.java` — `@SpringBootApplication` + `@EnableScheduling`

### Configuration
- `common/config/AppProperties.java` — typed `@ConfigurationProperties("app")` for JWT, CORS, rate limit
- `common/config/SecurityConfig.java` — stateless JWT, CORS, security headers (HSTS, CSP, Referrer-Policy)
- `common/config/OpenApiConfig.java` — bearerAuth scheme for Swagger UI
- `resources/application.yml` — base config; profile overlays: `local`, `test`, `prod`
- `resources/logback-spring.xml` — plain console (local), Logstash JSON (prod/test)

### Database Migrations
- `V1__baseline_schema.sql` — full schema: users, roles, accounts, categories, transactions, budgets, recurring_transactions
- `V2__seed_roles_and_default_categories.sql` — ROLE_USER, ROLE_ADMIN, 13 expense + 7 income system categories

### Feature Modules

| Module | Entry | Notes |
|---|---|---|
| auth | `AuthController` → `AuthService` | JWT issue, rotation, SHA-256 refresh token hashing |
| account | `AccountController` → `AccountService` | `adjustBalance()` + `recomputeBalance()` |
| category | `CategoryController` → `CategoryService` | System categories read-only; delete reassigns to "Uncategorized" |
| transaction | `TransactionController` → `TransactionService` | Paginated + filtered list; balance delta on CRUD |
| budget | `BudgetController` → `BudgetService` | Real-time progress via `sumSpentInPeriod()` |
| recurring | `RecurringTransactionController` + `RecurringTransactionScheduler` | Daily scheduler, idempotent via unique constraint |
| analytics | `AnalyticsController` → `AnalyticsService` | 4 aggregation endpoints; JPQL queries in `AnalyticsRepository` |

### Common Infrastructure
- `JwtAuthenticationFilter` — extract Bearer, validate, set SecurityContext
- `AuthRateLimitFilter` — Bucket4j per-IP on `/api/v1/auth/**`
- `CorrelationIdFilter` — `X-Correlation-Id` header → MDC for all log lines
- `GlobalExceptionHandler` — maps typed exceptions to `ApiError` JSON

## Frontend — Key Files

| Path | Purpose |
|---|---|
| `src/lib/api-client.ts` | Axios instance with auto-refresh interceptor |
| `src/lib/auth-context.tsx` | Auth state (user, login, register, logout) |
| `src/app/providers.tsx` | QueryClient + AuthProvider + Toaster |
| `src/components/auth-guard.tsx` | Redirect unauthenticated users to /login |
| `src/components/sidebar.tsx` | Navigation sidebar |
| `src/services/*.ts` | Typed API service modules per feature |
| `src/app/dashboard/page.tsx` | Overview: net worth, accounts, budget alerts |
| `src/app/dashboard/analytics/page.tsx` | Recharts: bar (income/expense), pie (spending), budget bars |

## Test Coverage

| Test | Type | Scope |
|---|---|---|
| `AuthServiceTest` | Unit (Mockito) | register, login, refresh, bad credentials |
| `AccountRepositoryTest` | @DataJpaTest (H2) | user-scoped queries |
| `AuthControllerTest` | @WebMvcTest | register/login request validation |
| `FintrackIntegrationTest` | Testcontainers (PostgreSQL) | register → create account → list accounts → 401 |
