CREATE TABLE IF NOT EXISTS forge_reference (
    kind text NOT NULL,
    code text NOT NULL,
    position integer NOT NULL,
    body jsonb NOT NULL,
    PRIMARY KEY (kind, code)
);
CREATE TABLE IF NOT EXISTS forge_asset (
    path text PRIMARY KEY,
    content_type text NOT NULL,
    digest text NOT NULL,
    content bytea NOT NULL
);
CREATE TABLE IF NOT EXISTS forge_campaign (
    id integer PRIMARY KEY CHECK (id = 1),
    active_party text
);
CREATE TABLE IF NOT EXISTS forge_party (
    id text PRIMARY KEY,
    position integer NOT NULL,
    body jsonb NOT NULL
);

CREATE TABLE IF NOT EXISTS demo.forge_party_template (
    code text PRIMARY KEY,
    body jsonb NOT NULL
);

-- Move the saved templates before seeding defaults. A conflicting body for the
-- same code raises a unique-key error and rolls back the entire migration.
WITH moved AS (
    DELETE FROM forge_reference WHERE kind = 'demo' RETURNING code, body
)
INSERT INTO demo.forge_party_template (code, body)
SELECT moved.code, moved.body FROM moved
WHERE NOT EXISTS (
    SELECT 1 FROM demo.forge_party_template dest
    WHERE dest.code = moved.code AND dest.body = moved.body
);
