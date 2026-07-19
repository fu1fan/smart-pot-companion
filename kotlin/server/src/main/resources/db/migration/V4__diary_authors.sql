ALTER TABLE diaries
    ADD COLUMN IF NOT EXISTS author TEXT NOT NULL DEFAULT 'WHEAT';

ALTER TABLE diaries
    DROP CONSTRAINT IF EXISTS diaries_pot_id_diary_date_key;

CREATE UNIQUE INDEX IF NOT EXISTS diaries_pot_date_author_unique
    ON diaries (pot_id, diary_date, author);
