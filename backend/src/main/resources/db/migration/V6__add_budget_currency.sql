-- V6: Add currency dimension to budgets so spending is tracked per-currency.
-- A (Shopping, MONTHLY, USD) budget only counts USD-account expenses.

-- Recoverable snapshot before the destructive wipe
CREATE TABLE budgets_pre_v6_backup AS SELECT * FROM budgets;

-- Wipe existing rows so NOT NULL column addition is safe
DELETE FROM budgets;

-- Add mandatory currency column
ALTER TABLE budgets ADD COLUMN currency VARCHAR(10) NOT NULL;

-- Replace the unique constraint to include currency
ALTER TABLE budgets DROP CONSTRAINT uq_budget_user_category_period;
ALTER TABLE budgets ADD CONSTRAINT uq_budget_user_category_period_currency
    UNIQUE (user_id, category_id, period, currency);
