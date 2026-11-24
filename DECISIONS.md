# Engineering decisions

## Preserve Go database and replay compatibility

The migration uses the PostgreSQL schema, migration history, and journal fingerprint format from [bank-ledger-go at `1c90c25`](https://github.com/ericlelirzin/bank-ledger-go/tree/1c90c258b3c2f9ad4d871c479f30fc3dbe61f302). Existing data and retries keep their original identities when Java takes over. Changing the serialized fields, their order, or escaping in [PostingFingerprint](src/main/java/dev/lelirzin/bankledger/ledger/PostingFingerprint.java) can invalidate a replay of a committed request. Keep the legacy fingerprint tests when changing posting code. See [DATABASE.md](DATABASE.md) for the migration strategy.

## Keep money movement in explicit JDBC transactions

The stores use `JdbcTemplate` and `TransactionTemplate` so row locks, SQL call order, and transaction participation stay visible. PostgreSQL enforces the ledger rules at commit, even when a test exercises the database directly. Preserve the distinction between Java request validation and storage enforcement when changing this path. See [LEDGER_INVARIANTS.md](LEDGER_INVARIANTS.md).

## Commit the simulated bank receipt independently

A durable simulator receipt models the case where the bank accepts a payment and local settlement then fails. Keeping it outside the settlement transaction lets the worker resume from that receipt. The tradeoff is a second connection and a visible interval when the bank has confirmed a transfer that is still locally pending. See [DATABASE.md](DATABASE.md#transaction-boundaries).
