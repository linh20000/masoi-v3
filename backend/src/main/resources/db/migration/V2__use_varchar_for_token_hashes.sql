-- Keep PostgreSQL storage aligned with Hibernate's String/VARCHAR mapping.
-- Existing SHA-256 hex digests are 64 characters and are preserved by this cast.
ALTER TABLE rooms
  ALTER COLUMN host_token_hash TYPE VARCHAR(64) USING host_token_hash::VARCHAR(64);

ALTER TABLE players
  ALTER COLUMN token_hash TYPE VARCHAR(64) USING token_hash::VARCHAR(64);
