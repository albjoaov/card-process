CREATE TABLE card (
    id             UUID PRIMARY KEY,
    cardholder_id  UUID         NOT NULL UNIQUE,
    product_id     UUID         NOT NULL,
    masked_number  VARCHAR(19)  NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    correlation_id UUID         NOT NULL UNIQUE,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL
);
