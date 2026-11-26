# Ledger invariants

## Amounts, journals, and balances

- Represent money as integer minor units. Banking uses USD cents. Ledger amounts are positive signed 64-bit integers and balances are signed 64-bit. Aggregate debit and credit totals use Java `BigInteger` and PostgreSQL `numeric` so totals can exceed the per-entry range. The dashboard decodes wire integers into `bigint` before arithmetic or formatting.
- A committed journal has at least two entries, one currency, and equal debit and credit totals. Java accepts up to 1,000 entries in a posting. PostgreSQL enforces balance and currency at commit, prevents mutation of posted journals and entries, and prevents appending entries in a later transaction.
- Accounts start at zero. An entry on the account's normal side increases its balance and the opposite side decreases it. PostgreSQL maintains balances through the entry trigger and rejects overdrafts unless allowed by account policy. The ledger locks affected accounts in ascending ID order and applies increases before decreases.
- A successful posting commits the journal, entries, and balance updates together. Failure rolls them back together. A journal result returned inside a caller transaction remains provisional until the caller commits.

## Retries and banking

- Raw ledger journals and transfers share a database-wide journal key space. Replaying the same key and typed request returns the original journal. Changing the request with that key produces a conflict. Original entry order participates in the fingerprint, even when the stored entry order differs. After an uncertain response, retry the identical request with the same key.
- Outgoing transfers have database-wide keys within `outgoing_transfers`. Beneficiary keys are scoped to a bank account. Simulated funding hashes the user key into a `bank:fund:` journal key. Reservation and settlement keys derive from the transfer ID. When a transaction that first claims a key rolls back, its claim rolls back as well.
- Through the supported banking flows, funding increases posted and available balances, reservation moves available to reserved without moving posted funds, and settlement reduces posted and reserved. For those flows, posted equals available plus reserved. Raw ledger routes can post directly to backing accounts, and PostgreSQL enforces the journal rules without enforcing this product-level relationship.
- Reservation commits with transfer creation. Local settlement commits with the settled status. The simulated bank commits one immutable receipt per transfer in a separate transaction, so a local settlement failure leaves the receipt available for retry. The retry checks the receipt's destination and amount. The next attempt time survives a settlement rollback and the worker can continue with other due transfers. These guarantees apply to the simulator.

## Column sandbox cards

- The card demo has its own USD balance at Column. Card holds, captures, reversals, and refunds surface as
  provider activity. The supported card paths use the local database for resource mappings and issuance
  state. Wizardric’s simulated checking balance and journals describe its own funding and outgoing payments.

Enforcement is in [LedgerStore](src/main/java/bankledger/ledger/LedgerStore.java), [BankingStore](src/main/java/bankledger/banking/BankingStore.java), [SettlementStore](src/main/java/bankledger/banking/SettlementStore.java), and the [SQL migrations](src/main/resources/db/migrations). See [TESTING.md](TESTING.md) for evidence and [DATABASE.md](DATABASE.md) for transaction design.
