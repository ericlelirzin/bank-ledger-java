# Wizardric Bank Ledger in Java

Wizardric is a simulated banking demo. The React dashboard uses the Java API for
accounts, simulated funding, saved recipients, payments, and transaction history.
A separate Java worker settles payments against a simulated bank. Transaction
details show the settlement timeline and ledger entries.

## Browse the code

- [Banking](src/main/java/bankledger/banking) for accounts, payments, and settlement
- [Ledger](src/main/java/bankledger/ledger) for journals and balances
- [Cards](src/main/java/bankledger/cards) for Column sandbox provisioning, issuance, and activity
- [HTTP API](src/main/java/bankledger/http) for routes and request handling
- [Database migrations](src/main/resources/db/migrations) and [integration tests](src/test/java/bankledger)
- [Engineering handoff](AGENTS.md), [current state](STATE.md), and [architecture](ARCHITECTURE.md)

## Run locally

Install JDK 21, Docker, and Node.js 22. Start a development database or use an
existing Go demo database. Wait for `pg_isready` to report that the database is
accepting connections before starting the API.

```sh
docker run --rm -d --name wizardric-postgres -p 127.0.0.1:5432:5432 \
  -e POSTGRES_DB=ledger -e POSTGRES_USER=ledger -e POSTGRES_PASSWORD=ledger \
  postgres:17-bookworm
docker exec wizardric-postgres pg_isready -U ledger -d ledger
./mvnw package -DskipTests
SERVER_ADDRESS=127.0.0.1 java -jar target/bank-ledger-java-0.1.0-SNAPSHOT.jar serve
```

In a second terminal, start the worker against the same database.

```sh
java -jar target/bank-ledger-java-0.1.0-SNAPSHOT.jar worker
```

In a third terminal, run `npm ci` and `npm run dev` from `web/`, then open
<http://localhost:5173>. The dashboard seeds a fictional account on first load.
Send a payment to a saved recipient and open it in Transactions to see the
timeline and ledger entries. Vite proxies `/api` to `http://127.0.0.1:8080`.
Set `BANK_API_URL` before starting Vite if the API is at a different address.

The API and worker apply migrations on startup. To connect to a different
database, set the Spring datasource variables for each process. The packaged JAR
also provides a `migrate` command for applying migrations on their own.

```sh
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger \
SPRING_DATASOURCE_USERNAME=ledger \
SPRING_DATASOURCE_PASSWORD=ledger \
java -jar target/bank-ledger-java-0.1.0-SNAPSHOT.jar migrate
```

## Enable Column sandbox cards

Use a private loopback API and dashboard. In the Column Dashboard, switch to sandbox and configure the
platform root under **Entities → Root Entity**. Set the lowercase environment variable `column` to your sandbox key
for the setup command and the API process. The Cards page shows the local demo account ID before setup.

With the same datasource as the running API, run the explicit setup command for that account.

```sh
java -jar target/bank-ledger-java-0.1.0-SNAPSHOT.jar column-provision ACCOUNT_ID
```

Setup creates a Visa debit program with just-in-time funding disabled, a fictional non-root person,
a USD bank account funded with a simulated $1,000 wire, and a card account. If setup stops after a write,
it records the step and known provider IDs in PostgreSQL. When the simulated wire is still settling,
rerun setup to check the existing balance. Other incomplete steps require provider review before
retrying. A repeat after successful setup reports the existing mapping.

Start or restart the API with cards enabled, then run Vite on loopback.

```sh
SERVER_ADDRESS=127.0.0.1 WIZARDRIC_CARDS_ENABLED=true java -jar target/bank-ledger-java-0.1.0-SNAPSHOT.jar serve
cd web
npm run dev -- --host 127.0.0.1
```

In Cards, issue the virtual card, pause or resume it, and refresh after simulating transactions.
**Check issuance** looks for the result of an existing claim. The Column balance and card activity
come from the sandbox. The checking balance and payment journals come from Wizardric’s simulator.
[Column sandbox card guide](https://docs.column.com/card-issuing/card-sandbox-simulation/)

## Develop and verify

Start with [AGENTS.md](AGENTS.md) for engineering conventions or [TESTING.md](TESTING.md)
for the build and verification commands. The original [banking API](https://github.com/ericlelirzin/bank-ledger-go/blob/1c90c258b3c2f9ad4d871c479f30fc3dbe61f302/docs/banking.md)
and [ledger API](https://github.com/ericlelirzin/bank-ledger-go/blob/1c90c258b3c2f9ad4d871c479f30fc3dbe61f302/docs/ledger-reference.md)
describe the inherited wire format. See [STATE.md](STATE.md) for the Java demo's
current limits.

Licensed under the [MIT License](LICENSE).
