-- Rename "Other Income" and "Other Expense" defaults to "Other".
-- Both rows remain distinguishable by their transaction_type column.
-- The unique constraint (user_id, name, transaction_type) is not violated because
-- NULL user_id + "Other" + INCOME differs from NULL user_id + "Other" + EXPENSE.
UPDATE categories
SET    name = 'Other'
WHERE  is_system = TRUE
  AND  name IN ('Other Income', 'Other Expense');
