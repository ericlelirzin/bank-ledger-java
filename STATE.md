# Current state

## Verified baseline

The Java API and settlement worker serve the React dashboard against PostgreSQL. The backend is entirely Java. The port preserves the database and replay formats from the [pinned Go baseline](DECISIONS.md#preserve-go-database-and-replay-compatibility).

- Java sources and tests use the shorter `bankledger` package root. The [README](README.md#browse-the-code) links directly to the main code areas.
- Migration 005 makes settlement retries durable. A persistently failing payment leaves later payments eligible. The dashboard preserves exact amounts and IDs through requests, responses, and browser storage.
- At the settlement retry milestone, Chromium verified demo seeding, exact balances and a payment above JavaScript's safe integer limit, a lost response and retry after reload, a single transfer, settlement, both journals, and the final balances. See [TESTING.md](TESTING.md).
- At the API milestone, a shared-database smoke test confirmed that Java replayed Go-written funding, a beneficiary, and a transfer, then settled that transfer. The two APIs returned matching balances, history, timelines, and journal data in that run.

## Column sandbox cards

- Migration 006 adds account mappings and durable issuance claims. The private Cards page supports
  virtual Visa debit issuance, confirmed status, pause and resume, and Column balance and activity reads.
- Maven passed all 28 tests on each of PostgreSQL 17 and 18. Both frontend tests and the production
  build passed.
- Chromium exercised the Java API and PostgreSQL with a local Column stub. It verified one issuance,
  last four digits, pause and resume, reload, and transaction details for a hold, decline, final and partial
  captures, a reversal, and an applied refund. Displayed balances matched the fixture at each stage.
  Wizardric’s backing account balances, journals, entries, transfers, and simulator receipts were identical
  before and after the card operations. The API refused startup with cards enabled on a non-loopback bind.
- The Dashboard-created sandbox root is verified. The earlier API creation attempt saved the fictional
  **Wizardric Sandbox Demo LLC** as a non-root business. The explicit setup command then created and
  linked a Visa debit program with just-in-time funding disabled, a fictional non-root cardholder, a
  funded Column USD bank account, and a card account. The first balance read raced the simulated wire.
  Setup resumed from the existing funded account and created the card account.
- Chromium verified actual Column issuance, last four digits, pause, resume, reload, and the authorization
  hold, decline, final capture, partial capture followed by final capture, and reversal. Column's simulator
  injected the authorization decisions. An initial authorization without an override declined because
  the sandbox has no decision webhook. The UI and Column agreed on balances and activity at each
  step. Wizardric's backing accounts, journals, entries, transfers, and simulated receipts were identical
  before and after the card operations.
- Column approved a $3.00 refund and reported it as a pending credit. The dashboard showed $978.00
  available and $0.00 on hold with the refund in transaction details. An applied-refund UI fixture
  showed $3.00 settled and $981.00 available. Live applied-refund verification
  awaits Column's `credit_applied` event. See [verification](TESTING.md#column-sandbox-checks) and
  [Column's refund event definitions](https://docs.column.com/card-issuing/card-transactions/#refunds-and-credits).

## Open issues and next step

- The API is intended for local demo use. Before a shared preview, design account authorization and visitor isolation and restrict raw ledger routes. The [ledger invariants](LEDGER_INVARIANTS.md) describe why raw writes matter for product balances.
- Java uses separate HTTP and database timeouts. The Go whole-handler `REQUEST_TIMEOUT` behavior remains open.

Confirm the applied refund after Column settles it. Preview authorization and isolation remain the next deployment design step.
