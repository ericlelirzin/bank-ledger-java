package bankledger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
final class MigrationRunner {

    private static final List<String> MIGRATIONS = List.of(
            "001_ledger.sql", "002_reliability.sql", "003_banking.sql", "004_beneficiaries.sql",
            "005_settlement_retries.sql", "006_column_cards.sql");

    private final DataSource dataSource;

    MigrationRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    void apply() throws IOException, SQLException {
        try (var connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);

            try {
                applyPendingMigrations(connection);
                connection.commit();
            } catch (IOException | SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    private void applyPendingMigrations(Connection connection) throws IOException, SQLException {
        try (var statements = connection.createStatement();
             var find = connection.prepareStatement("SELECT 1 FROM schema_migrations WHERE version = ?");
             var record = connection.prepareStatement("INSERT INTO schema_migrations(version) VALUES (?)")) {
            // The Go runner uses the same lock and migration history table.
            statements.execute("SELECT pg_advisory_xact_lock(74209183)");
            statements.execute("CREATE TABLE IF NOT EXISTS schema_migrations(version integer PRIMARY KEY)");

            for (int index = 0; index < MIGRATIONS.size(); index++) {
                int version = index + 1;
                find.setInt(1, version);
                try (var applied = find.executeQuery()) {
                    if (applied.next()) {
                        continue;
                    }
                }

                String file = MIGRATIONS.get(index);
                var resource = new ClassPathResource("db/migrations/" + file);
                try {
                    statements.execute(resource.getContentAsString(StandardCharsets.UTF_8));
                    record.setInt(1, version);
                    record.executeUpdate();
                } catch (SQLException failure) {
                    throw new SQLException("Could not apply migration " + file, failure.getSQLState(), failure);
                }
            }
        }
    }
}
