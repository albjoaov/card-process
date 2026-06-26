CREATE TABLE product (
    id         UUID PRIMARY KEY,
    name       VARCHAR(120) NOT NULL,
    status     VARCHAR(20)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);
