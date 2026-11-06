# Code Standards

## Backend (Java / Spring Boot)

### Naming
- **Classes**: PascalCase — `AccountService`, `TransactionController`
- **Methods/variables**: camelCase — `findByIdAndUserId`, `currentBalance`
- **Constants**: UPPER_SNAKE_CASE — `DEFAULT_PAGE_SIZE`
- **Packages**: lowercase kebab-style — `com.fintrack.account.service`
- **DB columns**: snake_case — `transaction_date`, `current_balance`

### Layer Responsibilities
| Layer | Responsibility |
|---|---|
| `domain/` | JPA entities, enums — no business logic |
| `repository/` | Spring Data JPA interfaces — query methods + JPQL only |
| `service/` | Business logic, ownership checks, transaction boundaries |
| `controller/` | REST controllers: validate input → call service → return DTO |
| `controller/dto/` | Request/response bodies (see DTO naming below) |
| `mapper/` | MapStruct interfaces — compile-time DTO ↔ entity mapping |

### DTO Naming
- `*Request` — body the client sends in (`CreateAccountRequest`)
- `*Response` — body the API returns, including nested items and error bodies (`AccountResponse`, `ApiErrorResponse`)
- `*Dto` — only for a type used in both directions (`ProposalDto`: returned in a run, sent back edited)
- DTO class names become OpenAPI schema names — renaming one changes the contract; refresh `mcp-server/openapi.json` and regenerate its types

### Key Rules
- **Never** return JPA entities from controllers — always map to DTO
- **Always** enforce `userId` ownership at service layer — unowned → `ResourceNotFoundException` (404)
- **Never** use `float`/`double` for money — always `BigDecimal`, stored as `DECIMAL(19,4)`
- `@Transactional` on service methods; `readOnly = true` for queries
- Files > 200 lines: split by logical concern

### Error Handling
- `GlobalExceptionHandler` maps domain exceptions to RFC 7807-style `ApiErrorResponse`
- Custom exceptions: `ResourceNotFoundException` (404), `ConflictException` (409), `ForbiddenException` (403), `RateLimitException` (429)
- Never expose stack traces in HTTP responses

## Frontend (Next.js / TypeScript)

### Naming
- **Files/directories**: kebab-case — `account-service.ts`, `auth-guard.tsx`
- **Components**: PascalCase — `Sidebar`, `AuthGuard`
- **Hooks/utils**: camelCase — `useAuth`, `formatCurrency`

### Data Fetching
- All server communication through typed service modules (`src/services/`)
- TanStack Query for all async state (`useQuery`, `useMutation`)
- Axios instance in `src/lib/api-client.ts` — auto-attaches Bearer token, auto-refreshes on 401

### Component Rules
- Prefer small, focused components (< 200 lines)
- `"use client"` only where interactive (forms, hooks) — default to server components
- Form validation with `react-hook-form` + `zod` schemas

## Git Commit Format

```
feat: add recurring transaction pause/resume
fix: correct balance delta sign for TRANSFER type
refactor: extract periodBounds() into BudgetService
test: add Testcontainers integration tests for auth flow
chore: upgrade Spring Boot to 3.3.4
```

No AI references in commit messages. Keep commits focused on one logical change.
