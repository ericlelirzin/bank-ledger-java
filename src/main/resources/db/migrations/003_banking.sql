-- Product accounts map to three private ledger accounts. All amounts are USD cents.
CREATE TABLE bank_accounts (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 name text NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
 currency text NOT NULL DEFAULT 'USD' CHECK (currency = 'USD'),
 cash_id bigint NOT NULL UNIQUE REFERENCES accounts(id),
 available_id bigint NOT NULL UNIQUE REFERENCES accounts(id),
 reserved_id bigint NOT NULL UNIQUE REFERENCES accounts(id),
 CHECK (cash_id <> available_id AND cash_id <> reserved_id AND available_id <> reserved_id)
);
CREATE TABLE outgoing_transfers (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 idempotency_key text NOT NULL UNIQUE CHECK (length(idempotency_key) BETWEEN 1 AND 200),
 account_id bigint NOT NULL REFERENCES bank_accounts(id),
 destination text NOT NULL CHECK (length(destination) BETWEEN 1 AND 100),
 amount bigint NOT NULL CHECK (amount > 0),
 status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','settled')),
 reserve_journal_id bigint REFERENCES journals(id),
 settlement_journal_id bigint REFERENCES journals(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 settled_at timestamptz,
 CHECK ((status = 'pending' AND settlement_journal_id IS NULL AND settled_at IS NULL)
     OR (status = 'settled' AND settlement_journal_id IS NOT NULL AND settled_at IS NOT NULL))
);
CREATE INDEX outgoing_pending ON outgoing_transfers(id) WHERE status = 'pending';
-- A durable fake external bank. Receipts commit independently of local settlement.
CREATE TABLE simulated_bank_receipts (
 transfer_id bigint PRIMARY KEY,
 destination text NOT NULL,
 amount bigint NOT NULL CHECK (amount > 0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER simulated_receipts_immutable BEFORE UPDATE OR DELETE ON simulated_bank_receipts
 FOR EACH ROW EXECUTE FUNCTION immutable_row();
