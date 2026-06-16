-- V2: Seed default roles and system categories

-- ─── Roles ───────────────────────────────────────────────────────────────────
INSERT INTO roles (name) VALUES ('ROLE_USER'), ('ROLE_ADMIN');

-- ─── System default EXPENSE categories ──────────────────────────────────────
-- user_id = NULL marks these as system-owned (non-editable by users)
INSERT INTO categories (user_id, name, transaction_type, is_system) VALUES
    (NULL, 'Uncategorized',     'EXPENSE', TRUE),
    (NULL, 'Food & Dining',     'EXPENSE', TRUE),
    (NULL, 'Transportation',    'EXPENSE', TRUE),
    (NULL, 'Housing',           'EXPENSE', TRUE),
    (NULL, 'Utilities',         'EXPENSE', TRUE),
    (NULL, 'Healthcare',        'EXPENSE', TRUE),
    (NULL, 'Entertainment',     'EXPENSE', TRUE),
    (NULL, 'Shopping',          'EXPENSE', TRUE),
    (NULL, 'Education',         'EXPENSE', TRUE),
    (NULL, 'Travel',            'EXPENSE', TRUE),
    (NULL, 'Insurance',         'EXPENSE', TRUE),
    (NULL, 'Personal Care',     'EXPENSE', TRUE),
    (NULL, 'Other Expense',     'EXPENSE', TRUE);

-- ─── System default INCOME categories ───────────────────────────────────────
INSERT INTO categories (user_id, name, transaction_type, is_system) VALUES
    (NULL, 'Uncategorized',     'INCOME', TRUE),
    (NULL, 'Salary',            'INCOME', TRUE),
    (NULL, 'Freelance',         'INCOME', TRUE),
    (NULL, 'Investment',        'INCOME', TRUE),
    (NULL, 'Gift',              'INCOME', TRUE),
    (NULL, 'Refund',            'INCOME', TRUE),
    (NULL, 'Other Income',      'INCOME', TRUE);
