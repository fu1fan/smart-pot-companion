CREATE TABLE IF NOT EXISTS focus_sessions (
    id UUID PRIMARY KEY,
    pot_id UUID NOT NULL REFERENCES pots(id) ON DELETE CASCADE,
    completed_at TIMESTAMPTZ NOT NULL,
    data JSONB NOT NULL
);

CREATE INDEX IF NOT EXISTS focus_sessions_pot_completed_idx ON focus_sessions(pot_id, completed_at);
