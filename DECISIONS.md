# Engineering decisions

## Preserve Go database and replay compatibility

The migration uses the PostgreSQL schema, migration history, and journal fingerprint format from [bank-ledger-go at `1c90c25`](https://github.com/ericlelirzin/bank-ledger-go/tree/1c90c258b3c2f9ad4d871c479f30fc3dbe61f302). Existing data and retries keep their original identities when Java takes over. Changing the serialized fields, their order, or escaping in [PostingFingerprint](src/main/java/bankledger/ledger/PostingFingerprint.java) can invalidate a replay of a committed request. Keep the legacy fingerprint tests when changing posting code. See [DATABASE.md](DATABASE.md) for the migration strategy.

## Keep money movement in explicit JDBC transactions

The stores use `JdbcTemplate` and `TransactionTemplate` so row locks, SQL call order, and transaction participation stay visible. PostgreSQL enforces the ledger rules at commit, even when a test exercises the database directly. Preserve the distinction between Java request validation and storage enforcement when changing this path. See [LEDGER_INVARIANTS.md](LEDGER_INVARIANTS.md).

## Commit the simulated bank receipt independently

A durable simulator receipt models the case where the bank accepts a payment and local settlement then fails. Keeping it outside the settlement transaction lets the worker resume from that receipt. The tradeoff is a second connection and a visible interval when the bank has confirmed a transfer that is still locally pending. See [DATABASE.md](DATABASE.md#transaction-boundaries).

## Commit retry eligibility before attempting settlement

A short claim transaction advances the transfer's retry time. If a worker exits after claiming or settlement fails at commit, the time still advances and other due transfers can proceed. A second row lock guards the actual settlement. This adds one short transaction per attempt and allows a five-second wait before retrying an interrupted claim. See [DATABASE.md](DATABASE.md#transaction-boundaries).

## Preserve integer JSON across the dashboard boundary

The API retains the numeric wire format inherited from Go. The dashboard uses [lossless-json](web/package.json) to decode integers before they pass through JavaScript `Number` and to write exact numeric tokens on requests and in browser storage. The existing storage keys still work. This also preserves the exact payment payload and key when a response is uncertain.


## Reconcile Column card issuance through a durable claim

The published Column idempotency list omits card and card-account creation. Wizardric sends a card create
only from a newly committed claim and includes the claim reference in the card description. A later check
adopts a result only when exactly one card has that reference under the mapped account and matches the
person and program. This protects against duplicate issuance after a lost response at the cost of operator
review if the original request never reached Column. [Column idempotency documentation](https://docs.column.com/working-with-the-api/idempotency/)

Column owns the sandbox checking balance, holds, captures, reversals, and refunds for a card. Wizardric
reads those facts through its mapping and uses its existing PostgreSQL journals for simulated checking
and payments. Full card reveal, shared balances, and live issuing are deferred.
