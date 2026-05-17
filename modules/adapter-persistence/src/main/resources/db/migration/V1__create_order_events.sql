-- Event store for the Trading bounded context.
-- Append-only — no UPDATE or DELETE ever runs on this table.
-- Events are the source of truth; projections are derived from this log.

CREATE TABLE order_events
(
    id             BIGSERIAL PRIMARY KEY,
    symbol         VARCHAR(20)              NOT NULL,
    order_id       UUID                     NOT NULL,
    sequence       BIGINT,
    event_type     VARCHAR(50)              NOT NULL,
    payload        JSONB                    NOT NULL,
    occurred_at    TIMESTAMPTZ              NOT NULL,
    created_at     TIMESTAMPTZ              NOT NULL DEFAULT NOW()
);

-- Fast lookup for event replay (reconstruct a single order's history)
CREATE INDEX idx_order_events_order_id
    ON order_events (order_id);

-- Fast lookup for book replay and time-range queries per instrument
CREATE INDEX idx_order_events_symbol_occurred
    ON order_events (symbol, occurred_at);

-- Payload search (e.g., find all trades above a price threshold)
CREATE INDEX idx_order_events_payload_gin
    ON order_events USING GIN (payload);
