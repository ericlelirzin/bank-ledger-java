CREATE TABLE column_card_accounts (
    account_id bigint PRIMARY KEY REFERENCES bank_accounts(id),
    reference text NOT NULL UNIQUE,
    provisioning_step text NOT NULL CHECK (provisioning_step IN
        ('program', 'person', 'bank_account', 'funding', 'card_account', 'ready')),
    program_id text UNIQUE,
    person_id text UNIQUE,
    bank_account_id text UNIQUE,
    card_account_id text UNIQUE,
    CHECK (provisioning_step <> 'ready' OR
        (program_id IS NOT NULL AND person_id IS NOT NULL AND bank_account_id IS NOT NULL AND card_account_id IS NOT NULL))
);

CREATE TABLE column_card_issuance (
    account_id bigint PRIMARY KEY REFERENCES column_card_accounts(account_id),
    reference text NOT NULL UNIQUE,
    state text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'review', 'issued')),
    card_id text UNIQUE,
    CHECK ((state = 'issued') = (card_id IS NOT NULL))
);
