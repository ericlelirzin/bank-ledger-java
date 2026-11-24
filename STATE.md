# Current state

## Verified baseline

The Java API and settlement worker serve the React dashboard against PostgreSQL. The backend is entirely Java. The port preserves the database and replay formats from the [pinned Go baseline](DECISIONS.md#preserve-go-database-and-replay-compatibility).

- Java sources and tests use the shorter `bankledger` package root. The [README](README.md#browse-the-code) links directly to the main code areas.
- Migration 005 makes settlement retries durable. A persistently failing payment leaves later payments eligible. The dashboard preserves exact amounts and IDs through requests, responses, and browser storage.
- The Maven suite passed all 20 tests on each of PostgreSQL 17 and 18. Both dashboard tests and the production build passed. Chromium verified demo seeding, exact balances and a payment above JavaScript's safe integer limit, a lost response and retry after reload, a single transfer, settlement, both journals, and the final balances. See [TESTING.md](TESTING.md).
- At the API milestone, a shared-database smoke test confirmed that Java replayed Go-written funding, a beneficiary, and a transfer, then settled that transfer. The two APIs returned matching balances, history, timelines, and journal data in that run.

## Open issues and next step

- The API is intended for local demo use. Before a shared preview, design account authorization and visitor isolation and restrict raw ledger routes. The [ledger invariants](LEDGER_INVARIANTS.md) describe why raw writes matter for product balances.
- Java uses separate HTTP and database timeouts. The Go whole-handler `REQUEST_TIMEOUT` behavior remains open.

The next proposed step is to agree on the preview authorization and isolation design before starting shared deployment work.
