# Wizardric Bank Ledger in Java

Wizardric is a simulated banking demo. The Java port starts from
[bank-ledger-go at `1c90c25`](https://github.com/ericlelirzin/bank-ledger-go/tree/1c90c258b3c2f9ad4d871c479f30fc3dbe61f302).
This milestone provides the Java migration command and carries forward the
PostgreSQL schema and React dashboard. The dashboard's API and settlement worker
are currently provided by the Go reference application.

## Verify the build

Install JDK 21 and start Docker. The Maven Wrapper builds the Java app, and
Testcontainers runs the migration suite against a disposable PostgreSQL database.

```sh
./mvnw verify
POSTGRES_MAJOR=18 ./mvnw verify
```

PostgreSQL 17 is the default for local tests. CI tests both versions.
For the dashboard build, use Node.js 22 and run `npm ci` and `npm run build` in `web/`.

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

## Next milestone

Port account reads, double-entry posting, and internal transfers. Start
with Go's posting tests and verify that retries can find journals written
by either implementation.

Licensed under the [MIT License](LICENSE).
