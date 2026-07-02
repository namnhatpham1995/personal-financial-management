-- V9: Seed default categories for loan/credit-card money and loan/mortgage payments
-- Net worth / assets / liabilities tracking was removed; these categories let users
-- record loan or credit-card receipts and loan/mortgage payments as ordinary transactions.

INSERT INTO categories (user_id, name, transaction_type, is_system) VALUES
    (NULL, 'Loans & Credit',         'INCOME',  TRUE),
    (NULL, 'Loan & Mortgage Payment', 'EXPENSE', TRUE);
