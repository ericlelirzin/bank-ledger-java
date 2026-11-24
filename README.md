# Wizardric Bank Ledger in Java

Wizardric is a simulated banking demo. The Java port starts from
[bank-ledger-go at `1c90c25`](https://github.com/ericlelirzin/bank-ledger-go/tree/1c90c258b3c2f9ad4d871c479f30fc3dbe61f302).
The Java backend serves the React dashboard's banking and ledger API. It supports
simulated funding, saved recipients, outgoing transfers, and transfer timelines.
A separate Java worker settles payments against the simulated bank. The React
dashboard and PostgreSQL schema come from the Go repository.

## Verify the build

Install JDK 21 and start Docker. The Maven Wrapper builds the Java app, and
Testcontainers runs the migration, ledger, and banking suites against disposable
PostgreSQL databases.

```sh
./mvnw verify
POSTGRES_MAJOR=18 ./mvnw verify
```

PostgreSQL 17 is the default for local tests. CI tests both versions.
For the dashboard build, use Node.js 22 and run `npm ci` and `npm run build` in `web/`.

## Run the dashboard locally

With Docker running, start a development database. If you already have a Go demo
database running, you can use it in place of a new one.

```sh
docker run --rm -d --name wizardric-postgres -p 127.0.0.1:5432:5432 \
  -e POSTGRES_DB=ledger -e POSTGRES_USER=ledger -e POSTGRES_PASSWORD=ledger \
  postgres:17-bookworm
docker exec wizardric-postgres pg_isready -U ledger -d ledger
./mvnw package -DskipTests
SERVER_ADDRESS=127.0.0.1 java -jar target/bank-ledger-java-0.1.0-SNAPSHOT.jar serve
```

In a second terminal, run the settlement worker against the same database.

```sh
java -jar target/bank-ledger-java-0.1.0-SNAPSHOT.jar worker
```

In a third terminal, run `npm ci` and `npm run dev` from `web/`, then open
<http://localhost:5173>. Vite proxies `/api` to Java at `http://127.0.0.1:8080`.
Use `BANK_API_URL` when the API runs at a different address. The dashboard seeds a
fictional account on first load. Send a payment to a saved recipient, then open
it in Transactions to see the settlement timeline and ledger entries.

The API and worker apply migrations on startup. Use the datasource variables
below to connect either process to a different database. The worker polls every
500 ms and needs two database connections per active worker. It can retry local
settlement using a receipt already recorded by the simulated bank.

This API is for the local demo. A public deployment needs account authorization,
isolated visitor sessions, and private raw ledger routes.

## Run the migrations

With a development PostgreSQL 17 or 18 database available, run the migration
command from the packaged JAR.

```sh
./mvnw package
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger \
SPRING_DATASOURCE_USERNAME=ledger \
SPRING_DATASOURCE_PASSWORD=ledger \
java -jar target/bank-ledger-java-0.1.0-SNAPSHOT.jar migrate
```

The four SQL migrations are the original Go files. The Java runner shares Go's
`schema_migrations` table and PostgreSQL advisory lock. It applies pending
migrations and records their versions in one transaction. Keep applied
migration files fixed and make future schema changes in new migration files.

## Ledger behavior

The [ledger store](src/main/java/dev/lelirzin/bankledger/ledger/LedgerStore.java)
uses integer minor units and one transaction per posting. It locks affected
accounts in ascending ID order and applies increases before decreases. PostgreSQL
updates balances and validates journals at commit. A retry with the same key and
request returns the original journal, including when Go wrote it. A changed
request using that key is a conflict.

The original [banking API](https://github.com/ericlelirzin/bank-ledger-go/blob/1c90c258b3c2f9ad4d871c479f30fc3dbe61f302/docs/banking.md)
and [ledger API](https://github.com/ericlelirzin/bank-ledger-go/blob/1c90c258b3c2f9ad4d871c479f30fc3dbe61f302/docs/ledger-reference.md)
describe the wire format. Java uses Spring Boot settings for HTTP and connection
pool timeouts and PostgreSQL settings for statement and lock timeouts. Go's
whole-handler `REQUEST_TIMEOUT` remains unported.

Licensed under the [MIT License](LICENSE).
