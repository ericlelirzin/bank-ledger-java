# Database

PostgreSQL 17 and 18 are covered by the test matrix. The [migrations](src/main/resources/db/migrations) define the schema. [Application properties](src/main/resources/application.properties) define datasource defaults and lock, statement, idle-in-transaction, and pool timeouts.

| Migration | Owns |
| --- | --- |
| [001 ledger](src/main/resources/db/migrations/001_ledger.sql) | Accounts, journals, entries, balance maintenance, and immutability |
| [002 reliability](src/main/resources/db/migrations/002_reliability.sql) | Deferred validation queue for journals |
| [003 banking](src/main/resources/db/migrations/003_banking.sql) | Product accounts, outgoing transfers, and simulator receipts |
| [004 beneficiaries](src/main/resources/db/migrations/004_beneficiaries.sql) | Immutable beneficiaries, account-scoped transfer reference, and transfer history index |
| [005 settlement retries](src/main/resources/db/migrations/005_settlement_retries.sql) | Durable retry times and the due-transfer index |

## Migrations

[MigrationRunner](src/main/java/bankledger/MigrationRunner.java) shares the Go `schema_migrations` table and transaction-scoped advisory lock `74209183`. It applies all pending files and records their versions in one read-committed transaction. A failure rolls back that run. `serve`, `worker`, and `migrate` all apply pending migrations at startup.

Preserve applied files and their order. For a schema change, add a forward SQL migration and append it to the runner's ordered list. Verify fresh setup and upgrade from a populated prior schema. See [README.md](README.md) for the migration command.

## Transaction boundaries

- Raw ledger posting opens a read-committed transaction. Banking writes open their own read-committed transactions and use `postInTransaction` to include each journal and its balance changes in the caller's work.
- Each product account points to three backing accounts in `accounts`. Cash is an asset with debit normal side. Available and reserved are liabilities with credit normal side. All three disallow overdraft. The product account read joins all three balances in one query.
- A short transaction claims the next due pending transfer by retry time and ID with `FOR UPDATE SKIP LOCKED`, then advances its retry time by five seconds. Rows already pending at upgrade are due immediately. A separate settlement transaction rechecks and locks the pending row. While it holds that lock, the simulated bank receipt uses its own transaction and connection. Budget two connections per active settlement. The settlement transaction posts the journal and records the final status together.
- Transfers keep a destination snapshot. The composite foreign key constrains a saved beneficiary to the originating bank account. The nullable reference supports direct-destination transfers and rows from before migration 004.
