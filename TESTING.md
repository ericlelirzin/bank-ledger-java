# Testing

Use JDK 21 and Docker for the Maven suite. JUnit and Testcontainers exercise the actual PostgreSQL migrations, constraints, and transactions in disposable databases.

```sh
./mvnw --batch-mode verify
POSTGRES_MAJOR=18 ./mvnw --batch-mode verify
```

The default is PostgreSQL 17. Use `./mvnw -Dtest=BankingStoreTest test` to focus on one suite. The [GitHub Actions workflow](.github/workflows/test.yml) runs both database versions, then tests and builds the dashboard with Node.js 22.

| Suite | Coverage |
| --- | --- |
| [MigrationRunnerTest](src/test/java/bankledger/MigrationRunnerTest.java) | Fresh and repeated setup, populated schema versions 3, 4, and 5, concurrent migration, rollback, and a database commit guard |
| [LedgerStoreTest](src/test/java/bankledger/LedgerStoreTest.java) | Go fingerprint replay, entry ordering, 64-bit balance bounds, input validation, deferred rollback, and concurrent retries and overspending |
| [BankingStoreTest](src/test/java/bankledger/BankingStoreTest.java) | Funding, reserve and settle, beneficiary scoping and replay, competing reserves, receipt recovery after a forced settlement commit failure, and queue progress during a persistent failure |
| [CardServiceTest](src/test/java/bankledger/cards/CardServiceTest.java) | Explicit sandbox provisioning, root preflight, delayed and resumed funding, concurrent issuance, dropped create response and restart recovery, zero/multiple/unverified matches, mapped activity, and overlapping status commands with a timed-out pause |
| [ApiIntegrationTest](src/test/java/bankledger/ApiIntegrationTest.java) | Live HTTP routes and database startup, banking lifecycle, cursors, ledger writes, and strict JSON including signed 64-bit integers |

For a financial change, exercise the affected path through its normal entry point and assert committed balances, records, and retry behavior. Include rollback and concurrency when the change can affect them. Migration changes should exercise existing populated databases as well as fresh setup.

## Dashboard checks

With Node.js 22, run `npm ci`, `npm test`, and `npm run build` from `web/`. The [dashboard tests](web/test/money.test.mjs) cover exact cents and IDs, payment round trips, and existing browser storage. For API or dashboard behavior changes, run the full demo using [README.md](README.md). Open the seeded account and confirm the balance and recipients. Send a payment, see it in Transactions, and check the timeline and journals after the worker settles it. Reload and verify that the same account and activity return.

For Go compatibility changes, use a disposable PostgreSQL database with the pinned Go revision linked from [DECISIONS.md](DECISIONS.md). Create records through Go and replay the same payloads and keys through Java. Confirm the original IDs and compare balances, history, timelines, and journals after Java settlement. Record the checks actually performed in [STATE.md](STATE.md) at meaningful milestones.

## Column sandbox checks

Use the [local setup](README.md#enable-column-sandbox-cards) and keep the dashboard and API on loopback.
Verify issuance, last four digits, pause and resume, reload, and transaction detail. Use the simulator’s
`realtime_decision` override to exercise authorization without a webhook configured. Exercise an authorization
hold and decline, a final capture, a partial capture followed by a final capture, a reversal, and an applied
refund. Confirm the refund first appears as a pending credit and wait for Column's `credit_applied` event
before checking the credited balance. Refresh after each action and compare the activity, available balance,
and held amount with Column.
Compare Wizardric’s balances and journal records before and after card activity.
[Column sandbox simulation endpoints](https://docs.column.com/card-issuing/card-sandbox-simulation/)

The PostgreSQL test suite uses an HTTP stub for the Column failure paths. `STATE.md` records the last
browser and sandbox results.
