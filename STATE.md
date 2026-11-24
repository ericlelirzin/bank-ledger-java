# Current state

## Verified baseline

The Java API and settlement worker serve the existing React dashboard against PostgreSQL. The current review branch is `feat/java-banking-api`, based on `feat/java-banking-worker` at `92ea68f`. The API implementation is at `1fd370b`. The engineering handoff docs are maintained on the same branch. [Review the branch](https://github.com/ericlelirzin/bank-ledger-java/compare/feat/java-banking-worker...feat/java-banking-api).

- Rechecked during the documentation handoff. The Maven suite passed all 19 tests on PostgreSQL 17 and again on PostgreSQL 18. The React production build passed. Chromium verified demo seeding, recipient selection, payment submission, pending-to-settled refresh, both payment journals, and reload using the local setup in [README.md](README.md). See [TESTING.md](TESTING.md) for commands and coverage.
- At the API milestone, a shared-database smoke test confirmed that Java replayed Go-written funding, a beneficiary, and a transfer, then settled that transfer. The two APIs returned matching balances, history, timelines, and journal data in that run.

## Open issues and next step

- The API is intended for local demo use. Before a shared preview, design account authorization and visitor isolation and restrict raw ledger routes. The [ledger invariants](LEDGER_INVARIANTS.md) describe why raw writes matter for product balances.
- Java uses separate HTTP and database timeouts. The Go whole-handler `REQUEST_TIMEOUT` behavior remains open.
- The dashboard decodes API integers as JavaScript `number`. Some valid 64-bit amounts and IDs lose precision above `Number.MAX_SAFE_INTEGER`.
- A permanently failing oldest transfer can be selected again on each poll by a lone worker. That follows from the worker query and retry loop. Recovery from a temporary commit failure is covered, and behavior under a persistent failure needs a focused test.

The next proposed step is to review the migration branch, then agree on the preview architecture before starting shared deployment work.
