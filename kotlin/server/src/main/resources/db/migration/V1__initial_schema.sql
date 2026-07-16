CREATE TABLE IF NOT EXISTS plant_species (
    id TEXT PRIMARY KEY,
    data JSONB NOT NULL
);

CREATE TABLE IF NOT EXISTS pots (
    id UUID PRIMARY KEY,
    device_id TEXT NOT NULL UNIQUE,
    data JSONB NOT NULL
);

CREATE TABLE IF NOT EXISTS device_state (
    device_id TEXT PRIMARY KEY,
    reported JSONB,
    desired JSONB,
    online BOOLEAN NOT NULL DEFAULT FALSE,
    last_seen_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS telemetry_minute (
    pot_id UUID NOT NULL REFERENCES pots(id) ON DELETE CASCADE,
    bucket TIMESTAMPTZ NOT NULL,
    data JSONB NOT NULL,
    PRIMARY KEY (pot_id, bucket)
);

CREATE TABLE IF NOT EXISTS alerts (
    id UUID PRIMARY KEY,
    pot_id UUID NOT NULL REFERENCES pots(id) ON DELETE CASCADE,
    type TEXT NOT NULL,
    status TEXT NOT NULL,
    data JSONB NOT NULL
);

CREATE INDEX IF NOT EXISTS alerts_pot_status_idx ON alerts(pot_id, status);

CREATE TABLE IF NOT EXISTS care_logs (
    id UUID PRIMARY KEY,
    pot_id UUID NOT NULL REFERENCES pots(id) ON DELETE CASCADE,
    occurred_at TIMESTAMPTZ NOT NULL,
    data JSONB NOT NULL
);

CREATE TABLE IF NOT EXISTS reminders (
    id UUID PRIMARY KEY,
    pot_id UUID NOT NULL REFERENCES pots(id) ON DELETE CASCADE,
    due_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL,
    data JSONB NOT NULL
);

CREATE TABLE IF NOT EXISTS memories (
    id UUID PRIMARY KEY,
    pot_id UUID NOT NULL REFERENCES pots(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    data JSONB NOT NULL
);

CREATE TABLE IF NOT EXISTS chat_messages (
    id UUID PRIMARY KEY,
    pot_id UUID NOT NULL REFERENCES pots(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    data JSONB NOT NULL
);

CREATE TABLE IF NOT EXISTS affinity (
    pot_id UUID PRIMARY KEY REFERENCES pots(id) ON DELETE CASCADE,
    data JSONB NOT NULL
);

CREATE TABLE IF NOT EXISTS affinity_events (
    id UUID PRIMARY KEY,
    pot_id UUID NOT NULL REFERENCES pots(id) ON DELETE CASCADE,
    event_key TEXT NOT NULL,
    points INTEGER NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    UNIQUE (pot_id, event_key)
);

CREATE TABLE IF NOT EXISTS diaries (
    id UUID PRIMARY KEY,
    pot_id UUID NOT NULL REFERENCES pots(id) ON DELETE CASCADE,
    diary_date DATE NOT NULL,
    data JSONB NOT NULL,
    UNIQUE (pot_id, diary_date)
);

CREATE TABLE IF NOT EXISTS share_codes (
    code TEXT PRIMARY KEY,
    pot_id UUID NOT NULL REFERENCES pots(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    redeemed_by TEXT
);
