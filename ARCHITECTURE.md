# Architecture

Wizardric is a local banking demo. React and TypeScript provide the dashboard. Java 21, Spring Boot MVC, and JDBC expose the API. PostgreSQL holds the ledger and banking state. The API and settlement worker run as separate processes against the same database.

| Responsibility | Start here |
| --- | --- |
| Dashboard, payment form, and transaction details | [App.tsx](web/src/App.tsx) |
| API calls, demo seeding, and history pagination | [bank.ts](web/src/bank.ts) |
| Routes, strict JSON decoding, and error responses | [ApiController](src/main/java/bankledger/http/ApiController.java), [ApiJson](src/main/java/bankledger/http/ApiJson.java), and [ApiErrors](src/main/java/bankledger/http/ApiErrors.java) |
| Banking accounts, funding, beneficiaries, transfers, and timelines | [BankingStore](src/main/java/bankledger/banking/BankingStore.java) |
| Journal posting, balances, and internal ledger transfers | [LedgerStore](src/main/java/bankledger/ledger/LedgerStore.java) |
| Pending transfer polling | [SettlementWorker](src/main/java/bankledger/banking/SettlementWorker.java) |
| Process modes and migrations | [BankLedgerApplication](src/main/java/bankledger/BankLedgerApplication.java) and [ApiDatabaseStartup](src/main/java/bankledger/ApiDatabaseStartup.java). See [DATABASE.md](DATABASE.md) for the runner. |

## Follow a payment

1. The dashboard sends `POST /bank/transfers` through the Vite `/api` proxy. It retains the payload and idempotency key in session storage while the result is uncertain. The account reference used to seed the demo lives in local storage.
2. `BankingStore.createTransfer` resolves the account-scoped beneficiary or uses a direct destination. It stores the destination on the transfer and posts the reservation through `LedgerStore` before returning the pending transfer. See [transaction boundaries](DATABASE.md#transaction-boundaries).
3. The worker polls every 500 ms and invokes `processOne`. The worker records a durable simulated-bank receipt, posts settlement, and marks the transfer settled. A retry can reuse a receipt after an aborted local settlement.
4. While activity is pending, the dashboard refreshes history and balances. Transfer details derive a timeline from committed transfer, journal, and receipt rows in a single query. Journal details come from `/journals/{id}`. A confirmed receipt can appear while the transfer is still pending.

History pages use descending transfer or beneficiary IDs and an exclusive `before_id` cursor. Each request reads its own current data. The API surface starts at `ApiController`, and [STATE.md](STATE.md) records current verification and limitations.
