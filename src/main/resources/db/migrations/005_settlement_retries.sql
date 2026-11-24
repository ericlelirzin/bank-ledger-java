-- Existing pending transfers are immediately eligible on upgrade.
ALTER TABLE outgoing_transfers
 ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT '-infinity';
ALTER TABLE outgoing_transfers
 ALTER COLUMN next_attempt_at SET DEFAULT clock_timestamp();

CREATE INDEX outgoing_due ON outgoing_transfers(next_attempt_at, id) WHERE status = 'pending';
