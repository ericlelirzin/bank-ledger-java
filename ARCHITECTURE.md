# Architecture

Wizardric is a local banking demo. React and TypeScript provide the dashboard. Java 21, Spring Boot MVC, and JDBC expose the API. PostgreSQL holds the ledger and banking state. The API and settlement worker run as separate processes against the same database.

| Responsibility | Start here |
| --- | --- |
| Dashboard, payment form, and transaction details | [App.tsx](web/src/App.tsx) |
| API calls, demo seeding, and history pagination | [bank.ts](web/src/bank.ts) |
| Column sandbox cards | [Cards.tsx](web/src/Cards.tsx), [CardController](src/main/java/bankledger/cards/CardController.java), and [CardService](src/main/java/bankledger/cards/CardService.java) |
| Exact USD parsing and formatting | [money.ts](web/src/money.ts) |
| Routes, strict JSON decoding, and error responses | [ApiController](src/main/java/bankledger/http/ApiController.java), [ApiJson](src/main/java/bankledger/http/ApiJson.java), and [ApiErrors](src/main/java/bankledger/http/ApiErrors.java) |
| Banking accounts, funding, beneficiaries, transfers, and timelines | [BankingStore](src/main/java/bankledger/banking/BankingStore.java) |
| Journal posting, balances, and internal ledger transfers | [LedgerStore](src/main/java/bankledger/ledger/LedgerStore.java) |
| Settlement polling and processing | [SettlementWorker](src/main/java/bankledger/banking/SettlementWorker.java) and [SettlementStore](src/main/java/bankledger/banking/SettlementStore.java) |
| Process modes and migrations | [BankLedgerApplication](src/main/java/bankledger/BankLedgerApplication.java) and [ApiDatabaseStartup](src/main/java/bankledger/ApiDatabaseStartup.java). See [DATABASE.md](DATABASE.md) for the runner. |

## Follow a payment

1. The dashboard sends `POST /bank/transfers` through the Vite `/api` proxy. It retains the payload and idempotency key in session storage while the result is uncertain. The account reference used to seed the demo lives in local storage.
2. `BankingStore.createTransfer` resolves the account-scoped beneficiary or uses a direct destination. It stores the destination on the transfer and posts the reservation through `LedgerStore` before returning the pending transfer. See [transaction boundaries](DATABASE.md#transaction-boundaries).
3. The worker polls every 500 ms. `SettlementStore` schedules the next attempt before it records a durable simulated-bank receipt, posts settlement, and marks the transfer settled. A failed attempt leaves the next due transfer eligible and can reuse its receipt later. See [transaction boundaries](DATABASE.md#transaction-boundaries).
4. While activity is pending, the dashboard refreshes history and balances. Transfer details derive a timeline from committed transfer, journal, and receipt rows in a single query. Journal details come from `/journals/{id}`. A confirmed receipt can appear while the transfer is still pending.

History pages use descending transfer or beneficiary IDs and an exclusive `before_id` cursor. Each request reads its own current data. The API surface starts at `ApiController`, and [STATE.md](STATE.md) records current verification and limitations.


## Follow a sandbox card

1. The operator runs `column-provision` for an existing Wizardric account. [ColumnProvisioning](src/main/java/bankledger/cards/ColumnProvisioning.java)
   checks for the platform root and creates the fictional cardholder and funding resources. [CardStore](src/main/java/bankledger/cards/CardStore.java)
   saves the mapping. API startup and dashboard reads leave setup to this command.
2. The Cards page requests issuance for the local account. The service takes an account-scoped command lock
   and commits a unique claim before sending a card create to Column. The card description carries the claim
   reference. With an uncertain response or after restart, a check lists cards under the mapped card account
   and adopts a single verified match. An absent, ambiguous, or unverified match needs review.
3. Pause and resume use the mapped card ID and the same command lock. A failed or uncertain status response
   triggers a fresh provider read. The UI displays the last four digits and the status confirmed by Column.
4. Column accepts one activity filter, so the list query specifies the mapped card ID. The detail query
   uses a transaction ID and returns its events, including any decline reason. The Java service verifies
   the card, card account, and program on every list row and detail before returning them. The sandbox
   balance uses the mapped Column USD bank account.
