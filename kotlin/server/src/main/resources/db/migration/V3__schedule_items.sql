CREATE TABLE IF NOT EXISTS schedule_items (
    id UUID PRIMARY KEY,
    pot_id UUID NOT NULL REFERENCES pots(id) ON DELETE CASCADE,
    due_at TIMESTAMPTZ,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL,
    data JSONB NOT NULL
);

CREATE INDEX IF NOT EXISTS schedule_items_pot_due_idx ON schedule_items(pot_id, completed, due_at, updated_at);
