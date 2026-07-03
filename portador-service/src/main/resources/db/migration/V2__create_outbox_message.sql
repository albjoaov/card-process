CREATE TABLE outbox_message (
    id              UUID PRIMARY KEY,
    correlation_id  UUID         NOT NULL UNIQUE,
    payload         TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    attempts        INT          NOT NULL,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    last_error      VARCHAR(500),
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_outbox_message_pending
    ON outbox_message (next_attempt_at, created_at)
    WHERE status = 'PENDING';
