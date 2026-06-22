CREATE TABLE exchange_rates (
    id          BIGSERIAL PRIMARY KEY,
    base_code   VARCHAR(10)    NOT NULL,
    quote_code  VARCHAR(10)    NOT NULL,
    rate        DECIMAL(19,10) NOT NULL,
    as_of       TIMESTAMPTZ    NOT NULL,
    fetched_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_exchange_rate_pair UNIQUE (base_code, quote_code),
    CONSTRAINT chk_exchange_rate_positive CHECK (rate > 0)
);
CREATE INDEX idx_exchange_rates_base ON exchange_rates(base_code);
