CREATE TABLE app_user (
    id            UUID PRIMARY KEY,
    username      VARCHAR(80)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);

CREATE TABLE cardholder (
    id         UUID PRIMARY KEY,
    name       VARCHAR(120) NOT NULL,
    cpf        VARCHAR(11)  NOT NULL UNIQUE,
    birth_date DATE         NOT NULL,
    status     VARCHAR(20)  NOT NULL,
    product_id UUID         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);
