CREATE TABLE beneficiaries (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 account_id bigint NOT NULL REFERENCES bank_accounts(id),
 idempotency_key text NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 200),
 name text NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
 destination text NOT NULL CHECK (length(destination) BETWEEN 1 AND 100),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(account_id,idempotency_key),
 UNIQUE(account_id,id)
);
-- Destinations are immutable in this slice. Transfers also retain their own
-- destination snapshot so historical payment instructions remain explicit.
CREATE TRIGGER beneficiaries_immutable BEFORE UPDATE OR DELETE ON beneficiaries
 FOR EACH ROW EXECUTE FUNCTION immutable_row();
ALTER TABLE outgoing_transfers ADD COLUMN beneficiary_id bigint;
ALTER TABLE outgoing_transfers ADD CONSTRAINT transfer_beneficiary_account
 FOREIGN KEY(account_id,beneficiary_id) REFERENCES beneficiaries(account_id,id);
CREATE INDEX outgoing_account_history ON outgoing_transfers(account_id,id DESC);
