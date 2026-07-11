# fintrack-mcp-server

An [MCP](https://modelcontextprotocol.io) server that lets AI clients (Claude Desktop, Claude Code, and other MCP hosts) read Fintrack data and, with a write-scoped token, create or update transactions, accounts, categories, and budgets.

This package is a thin adapter: it holds no database connection and no session credentials of its own. Every tool call is a plain HTTPS request to your Fintrack backend's existing REST API, authenticated with a single Personal Access Token (PAT) you create from **Settings -> API Tokens**. All of the app's existing security - per-user data isolation, request validation, rate limiting, audit logging - applies to every call the AI makes, exactly as it would to any other API client.

## Security posture

- **Least privilege by default.** Tokens are scoped `read` (default) or `write`. `read` can only call curated `GET` endpoints; `write` additionally allows creating and updating transactions, accounts, user-defined categories, and budgets. Neither scope can ever delete anything, manage tokens or sessions, touch the document vault, or make arbitrary REST requests. Those operations are hard-denied by the backend regardless of which tools this server exposes.
- **No AI-triggerable destructive actions exist.** There is no delete tool for any resource. This is enforced at the API layer, not just left out of this server's tool list.
- **Tokens expire and are revocable.** Every token has a mandatory 30/90/365-day expiry. Revoke a token instantly from Settings -> API Tokens; the very next request using it gets rejected.
- **Full audit trail.** Every mutation made through a token is recorded in your Fintrack activity log, tagged with the token that made it, so AI-driven changes are as visible as your own.
- **Credentials never leak into tool output.** Errors are mapped to short, generic messages (e.g. "the token is invalid or expired") - the token value, request headers, and upstream stack traces are never included.
- **Financial data is data, not instructions.** Tool descriptions tell the model that returned content (transaction notes, merchant names, category names) is user data, never a command - this server does not interpret or act on the contents of what it returns, it only relays it as structured JSON.
- **Residual risk:** a `write`-scoped token that leaks lets an attacker (or a successfully prompt-injected agent) create or modify transactions, accounts, user-defined categories, and budgets until you revoke it. Use `read` scope unless the AI needs to maintain those resources, and review your activity log periodically.

## Setup

1. In Fintrack, go to **Settings -> API Tokens -> New Token**, choose a scope and expiry, and copy the plaintext token shown once.
2. Note your backend's base URL (e.g. `http://localhost:8080` locally, or your deployed Railway URL).
3. Build this package:

   ```bash
   cd mcp-server
   npm install
   npm run build
   ```

### Claude Desktop

Add to your Claude Desktop config (`claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "fintrack": {
      "command": "node",
      "args": ["/absolute/path/to/mcp-server/dist/index.js"],
      "env": {
        "FINTRACK_API_URL": "http://localhost:8080",
        "FINTRACK_API_TOKEN": "fintrack_pat_..."
      }
    }
  }
}
```

### Claude Code

```bash
claude mcp add fintrack \
  --env FINTRACK_API_URL=http://localhost:8080 \
  --env FINTRACK_API_TOKEN=fintrack_pat_... \
  -- node /absolute/path/to/mcp-server/dist/index.js
```

## Tools

| Tool | Scope required | Maps to |
|---|---|---|
| `list_accounts` | read | `GET /accounts` |
| `get_account` | read | `GET /accounts/{id}` |
| `list_categories` | read | `GET /categories` (optional transaction-type filter) |
| `list_transactions` | read | `GET /transactions` (filters + pagination) |
| `get_transaction` | read | `GET /transactions/{id}` |
| `list_budgets_with_progress` | read | `GET /budgets` (current-period operational progress) |
| `get_budget_history` | read | `GET /analytics/budget-history` (period-by-period history for a date range) |
| `get_spending_by_category` | read | `GET /analytics/spending-by-category` |
| `get_income_vs_expense` | read | `GET /analytics/income-vs-expense` |
| `get_account_balances` | read | `GET /analytics/balances` (net-worth-style view) |
| `create_transaction` | write | `POST /transactions` |
| `update_transaction` | write | `PUT /transactions/{id}` |
| `create_account` | write | `POST /accounts` |
| `update_account` | write | `PUT /accounts/{id}` |
| `create_category` | write | `POST /categories` |
| `update_category` | write | `PUT /categories/{id}` |
| `create_budget` | write | `POST /budgets` |
| `update_budget` | write | `PUT /budgets/{id}` |

No delete, token/session-management, vault, or raw-request tool is available. The backend independently rejects those operations for every PAT scope.

## Development

```bash
npm run dev         # run against source with tsx
npm run type-check  # tsc --noEmit
npm test            # vitest
npm run build       # compile to dist/
```
