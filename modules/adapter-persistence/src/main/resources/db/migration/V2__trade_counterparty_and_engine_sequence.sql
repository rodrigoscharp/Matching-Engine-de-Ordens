-- A trade is a two-sided fact. V1 recorded only the buy order in `order_id`, so a seller's
-- execution history was unreachable through the by-order query. Store the other side explicitly
-- rather than burying it in the JSON payload where no index can reach it.
ALTER TABLE order_events
    ADD COLUMN counterparty_order_id UUID;

CREATE INDEX idx_order_events_counterparty
    ON order_events (counterparty_order_id)
    WHERE counterparty_order_id IS NOT NULL;

-- Replay needs the order in which the matching thread produced events, which is NOT the order in
-- which they land in this table: persistence runs on virtual threads and races. `id` reflects
-- write order; `engine_sequence` reflects decision order, and only the latter reconstructs a book.
ALTER TABLE order_events
    ADD COLUMN engine_sequence BIGINT;

CREATE INDEX idx_order_events_engine_sequence
    ON order_events (engine_sequence);
