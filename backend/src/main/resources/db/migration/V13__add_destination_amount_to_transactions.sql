ALTER TABLE transactions
    ADD COLUMN destination_amount DECIMAL(19,4);

ALTER TABLE transactions
    ADD CONSTRAINT chk_destination_amount_positive CHECK (destination_amount IS NULL OR destination_amount > 0);

COMMENT ON COLUMN transactions.destination_amount IS
    'Amount received in the destination account currency for a cross-currency TRANSFER. NULL means same-currency transfer (destination receives amount).';
