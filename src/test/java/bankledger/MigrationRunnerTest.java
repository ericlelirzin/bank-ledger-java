package bankledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MigrationRunnerTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            "postgres:" + System.getenv().getOrDefault("POSTGRES_MAJOR", "17") + "-bookworm");

    private PGSimpleDataSource dataSource;
    private JdbcTemplate jdbc;
    private MigrationRunner runner;

    @BeforeEach
    void createIsolatedSchema() {
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE SCHEMA " + schema);
        dataSource.setCurrentSchema(schema);
        runner = new MigrationRunner(dataSource);
    }

    @Test
    void initializesAStorageSchemaWithWorkingCommitGuards() throws Exception {
        runner.apply();
        runner.apply();

        assertThat(versions()).containsExactly(1, 2, 3, 4);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate("""
                    INSERT INTO journals(idempotency_key, request_hash, description)
                    VALUES ('unbalanced', sha256('unbalanced'::bytea), '')
                    """);

            var failure = assertThrows(SQLException.class, connection::commit);
            assertThat(failure.getSQLState()).isEqualTo("23514");
            connection.rollback();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journals", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pending_journals", Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4})
    void preservesPopulatedGoSchemas(int legacyVersion) throws Exception {
        initializeLegacyDatabase(legacyVersion);
        var originalJournals = jdbc.queryForList(
                "SELECT id, idempotency_key, encode(request_hash, 'hex') AS hash FROM journals ORDER BY id");

        runner.apply();

        assertThat(versions()).containsExactly(1, 2, 3, 4);
        assertThat(jdbc.queryForList(
                "SELECT id, idempotency_key, encode(request_hash, 'hex') AS hash FROM journals ORDER BY id"))
                .isEqualTo(originalJournals);
        assertThat(jdbc.queryForList("SELECT balance FROM accounts ORDER BY id", Long.class))
                .containsExactly(10_000L, 7_500L, 2_500L);
        assertThat(jdbc.queryForMap("""
                SELECT id, status, reserve_journal_id, beneficiary_id
                FROM outgoing_transfers WHERE id = 1
                """)).containsEntry("id", 1L)
                .containsEntry("status", "pending")
                .containsEntry("reserve_journal_id", 2L)
                .containsEntry("beneficiary_id", null);
    }

    @Test
    void serializesConcurrentMigrationRuns() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                runner.apply();
                return null;
            });
            var second = executor.submit(() -> {
                start.await();
                new MigrationRunner(dataSource).apply();
                return null;
            });
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }

        assertThat(versions()).containsExactly(1, 2, 3, 4);
    }

    @Test
    void rollsBackEarlierMigrationsWhenALaterOneFails() throws Exception {
        jdbc.execute("CREATE TABLE beneficiaries (id bigint)");

        var failure = assertThrows(SQLException.class, runner::apply);

        assertThat(failure.getMessage()).contains("004_beneficiaries.sql");
        assertThat(jdbc.queryForObject("SELECT to_regclass('accounts')::text", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT to_regclass('schema_migrations')::text", String.class)).isNull();

        jdbc.execute("DROP TABLE beneficiaries");
        runner.apply();
        assertThat(versions()).containsExactly(1, 2, 3, 4);
    }

    private List<Integer> versions() {
        return jdbc.queryForList("SELECT version FROM schema_migrations ORDER BY version", Integer.class);
    }

    private void initializeLegacyDatabase(int version) throws Exception {
        var files = List.of("001_ledger.sql", "002_reliability.sql", "003_banking.sql", "004_beneficiaries.sql");
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement();
                 var record = connection.prepareStatement("INSERT INTO schema_migrations VALUES (?)")) {
                statement.execute("CREATE TABLE schema_migrations(version integer PRIMARY KEY)");
                for (int index = 0; index < version; index++) {
                    var resource = new ClassPathResource("db/migrations/" + files.get(index));
                    statement.execute(resource.getContentAsString(StandardCharsets.UTF_8));
                    record.setInt(1, index + 1);
                    record.executeUpdate();
                }
            }
            connection.commit();
            seedLegacyPayment(connection);
        }
    }

    private void seedLegacyPayment(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO accounts(code, name, kind, currency, normal_side) VALUES
                        ('bank:1:cash', 'Bank account 1 cash', 'asset', 'USD', 'debit'),
                        ('bank:1:available', 'Bank account 1 available', 'liability', 'USD', 'credit'),
                        ('bank:1:reserved', 'Bank account 1 reserved', 'liability', 'USD', 'credit');
                    INSERT INTO bank_accounts(name, cash_id, available_id, reserved_id)
                        VALUES ('Legacy demo', 1, 2, 3);
                    INSERT INTO journals(idempotency_key, request_hash, description) VALUES
                        ('legacy-fund', sha256('legacy-fund'::bytea), 'Simulated inbound funding'),
                        ('bank:reserve:1', sha256('legacy-reserve'::bytea), 'Reserve outgoing transfer');
                    INSERT INTO entries(journal_id, line, account_id, side, amount) VALUES
                        (1, 1, 1, 'debit', 10000), (1, 2, 2, 'credit', 10000),
                        (2, 1, 3, 'credit', 2500), (2, 2, 2, 'debit', 2500);
                    INSERT INTO outgoing_transfers(idempotency_key, account_id, destination, amount, reserve_journal_id)
                        VALUES ('legacy-payment', 1, 'SIM-LEGACY', 2500, 2);
                    """);
        }
        connection.commit();
    }
}
