-- V1: Baseline schema for Fintrack
-- All monetary amounts use DECIMAL(19,4) — no floating point.

-- ─── Users & Roles ───────────────────────────────────────────────────────────

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE roles (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE  -- e.g. ROLE_USER, ROLE_ADMIN
);

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

-- ─── Refresh Tokens ──────────────────────────────────────────────────────────

CREATE TABLE refresh_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(255) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);

-- ─── Accounts ────────────────────────────────────────────────────────────────

CREATE TABLE accounts (
    id              BIGSERIAL    PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            VARCHAR(255) NOT NULL,
    account_type    VARCHAR(50)  NOT NULL,  -- CASH, BANK, CREDIT_CARD, SAVINGS, OTHER
    currency        VARCHAR(10)  NOT NULL DEFAULT 'USD',
    initial_balance DECIMAL(19,4) NOT NULL DEFAULT 0,
    current_balance DECIMAL(19,4) NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_accounts_user_id ON accounts(user_id);

-- ─── Categories ──────────────────────────────────────────────────────────────

CREATE TABLE categories (
    id               BIGSERIAL    PRIMARY KEY,
    user_id          BIGINT       REFERENCES users(id) ON DELETE CASCADE,  -- NULL = system default
    name             VARCHAR(100) NOT NULL,
    transaction_type VARCHAR(50)  NOT NULL,  -- INCOME, EXPENSE
    is_system        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- user-defined: unique name+type per user; system: globally unique by name+type
    CONSTRAINT uq_category_user_name_type UNIQUE NULLS NOT DISTINCT (user_id, name, transaction_type)
);

CREATE INDEX idx_categories_user_id ON categories(user_id);

-- ─── Transactions ────────────────────────────────────────────────────────────

CREATE TABLE transactions (
    id                    BIGSERIAL    PRIMARY KEY,
    user_id               BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    account_id            BIGINT       NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    -- For TRANSFER: destination account
    transfer_account_id   BIGINT       REFERENCES accounts(id) ON DELETE RESTRICT,
    category_id           BIGINT       REFERENCES categories(id) ON DELETE SET NULL,
    transaction_type      VARCHAR(50)  NOT NULL,  -- INCOME, EXPENSE, TRANSFER
    amount                DECIMAL(19,4) NOT NULL,
    transaction_date      DATE         NOT NULL,
    note                  TEXT,
    -- Link to recurring definition that generated this transaction
    recurring_id          BIGINT,
    -- Idempotency key for recurring generation: (recurring_id, occurrence_date)
    occurrence_date       DATE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_amount_positive CHECK (amount > 0),
    CONSTRAINT uq_recurring_occurrence UNIQUE (recurring_id, occurrence_date)
);

CREATE INDEX idx_transactions_user_id        ON transactions(user_id);
CREATE INDEX idx_transactions_account_id     ON transactions(account_id);
CREATE INDEX idx_transactions_category_id    ON transactions(category_id);
CREATE INDEX idx_transactions_date           ON transactions(transaction_date);
CREATE INDEX idx_transactions_type           ON transactions(transaction_type);

-- ─── Budgets ─────────────────────────────────────────────────────────────────

CREATE TABLE budgets (
    id            BIGSERIAL    PRIMARY KEY,
    user_id       BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category_id   BIGINT       NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    amount_limit  DECIMAL(19,4) NOT NULL,
    period        VARCHAR(20)  NOT NULL,  -- MONTHLY, YEARLY
    start_date    DATE         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- One budget per category per period per user
    CONSTRAINT uq_budget_user_category_period UNIQUE (user_id, category_id, period)
);

CREATE INDEX idx_budgets_user_id     ON budgets(user_id);
CREATE INDEX idx_budgets_category_id ON budgets(category_id);

-- ─── Recurring Transactions ───────────────────────────────────────────────────

CREATE TABLE recurring_transactions (
    id               BIGSERIAL    PRIMARY KEY,
    user_id          BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    account_id       BIGINT       NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    category_id      BIGINT       REFERENCES categories(id) ON DELETE SET NULL,
    transaction_type VARCHAR(50)  NOT NULL,
    amount           DECIMAL(19,4) NOT NULL,
    note             TEXT,
    frequency        VARCHAR(20)  NOT NULL,  -- DAILY, WEEKLY, MONTHLY, YEARLY
    interval_value   INT          NOT NULL DEFAULT 1,
    start_date       DATE         NOT NULL,
    end_date         DATE,
    max_occurrences  INT,
    occurrences_count INT         NOT NULL DEFAULT 0,
    next_run_date    DATE,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_interval_positive CHECK (interval_value > 0)
);

CREATE INDEX idx_recurring_user_id       ON recurring_transactions(user_id);
CREATE INDEX idx_recurring_next_run_date ON recurring_transactions(next_run_date) WHERE active = TRUE;
