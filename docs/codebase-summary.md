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
- `V3__rename_other_default_categories.sql` — renames "Other Income"/"Other Expense" defaults to "Other" (type field distinguishes them)

### Feature Modules

| Module | Entry | Notes |
|---|---|---|
| auth | `AuthController` → `AuthService` | JWT issue, rotation, SHA-256 refresh token hashing; `PUT /auth/me/language` persists `preferredLanguage` for cross-device UI language sync |
| account | `AccountController` → `AccountService` | `adjustBalance()` + `recomputeBalance()` |
| category | `CategoryController` → `CategoryService` | System categories read-only; delete **removes budgets** + reassigns transactions/recurring to "Uncategorized"; type is editable (INCOME/EXPENSE only — TRANSFER rejected) |
| transaction | `TransactionController` → `TransactionService` | Paginated + filtered list, including account-currency filtering; balance delta on CRUD |
| budget | `BudgetController` → `BudgetService` | Real-time progress via `sumSpentInPeriod()` |
| recurring | `RecurringTransactionController` + `RecurringTransactionScheduler` + `RecurringOccurrenceProcessor` | Daily scheduler, per-occurrence `@Transactional` boundary, idempotent via unique constraint |
| analytics | `AnalyticsController` → `AnalyticsService` | Per-currency analytics, converted balance summaries, budget progress; JPQL queries in `AnalyticsRepository` |

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
| `src/app/providers.tsx` | QueryClient + AuthProvider + Toaster (top-center) |
| `src/components/auth-guard.tsx` | Redirect unauthenticated users to /login |
| `src/components/sidebar.tsx` | Navigation sidebar (no separate Budgets entry) |
| `src/services/*.ts` | Typed API service modules per feature |
| `src/app/dashboard/page.tsx` | Overview: featured converted total, range toggle, per-currency sections |
| `src/components/accounts/featured-balance-card.tsx` | Main-currency converted grand total with native per-currency balances |
| `src/components/overview/currency-section.tsx` | One Overview section per currency: charts, recent transactions, accounts, budgets |
| `src/components/accounts/balance-breakdown.tsx` | Overview account creation entry point plus reusable per-currency account boxes |
| `src/components/accounts/recent-transactions-list.tsx` | Compact recent income/expense lists scoped by account currency |
| `src/components/charts/budget-progress-manager.tsx` | Inline budget limit management, scoped by currency on Overview |
| `src/app/dashboard/categories/page.tsx` | "Categories & Limit" — categories + inline spending-limit management |
| `src/app/dashboard/categories/category-row.tsx` | CategoryRow: type dropdown (INCOME/EXPENSE), inline limit form, LimitBar |
| `src/i18n/config.ts` | Supported locale registry (`en`/`vi`/`de`/`zh`), default locale, cookie name, native names |
| `src/i18n/request.ts` | next-intl server config: cookie → Accept-Language negotiation → en fallback; falls back to English messages if a locale file is missing |
| `src/components/language-switcher.tsx` | Language `<select>`; sets the locale cookie + `router.refresh()`, optionally syncs to `PUT /auth/me/language` |
| `src/lib/locale-preference.ts` | Read/write the `NEXT_LOCALE` cookie client-side |
| `src/app/dashboard/settings/page.tsx` | Settings landing page: language switcher (backend-synced) + link to API Tokens |
| `messages/{en,vi,de,zh}.json` | Translation message files, one per locale, namespaced by feature |

## Test Coverage

### Unit Tests (Mockito, no Spring context)
| Test | Scope |
|---|---|
| `AuthServiceTest` | register, login, refresh, bad credentials |
| `TransactionServiceTest` | balance deltas (INCOME/EXPENSE/TRANSFER), update/delete reversal, TRANSFER null-dest guard |
| `BudgetServiceTest` | spent/remaining/percent math, zero-limit, over-budget, exactly-at-limit edge cases |
| `RecurringTransactionServiceTest` | `computeNextRunDate` for all frequencies + intervals, pause/resume re-anchoring |
| `RecurringOccurrenceProcessorTest` | transaction generation, idempotent duplicate skip, deactivation on end-date/max-occurrences |
| `JwtServiceTest` | token round-trip, expired token, tampered signature, wrong user rejection |

### Integration Tests
| Test | Type | Scope |
|---|---|---|
| `AccountRepositoryTest` | @DataJpaTest (H2) | user-scoped queries |
| `AuthControllerTest` | @WebMvcTest | register/login request validation |
| `FintrackIntegrationTest` | Testcontainers (PostgreSQL) | register → create account → list accounts → 401 |

### Coverage Enforcement
- **JaCoCo** builds coverage report on every `mvn verify` (HTML: `backend/target/site/jacoco/`)
- **Coverage gate**: CI fails if service-layer line coverage drops below 60% (configured in `pom.xml`)
