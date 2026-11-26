package bankledger.cards;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class CardStore {

    record Mapping(String programId, String personId, String bankAccountId, String cardAccountId) {}

    record Attempt(String reference, String state, String cardId) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public CardStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    Mapping mapping(long accountId) {
        return mappingAtStep(accountId, "ready");
    }

    Mapping fundingMapping(long accountId) {
        var mapping = mappingAtStep(accountId, "funding");
        if (mapping == null) {
            throw new IllegalStateException("The Column funding mapping changed.");
        }
        return mapping;
    }

    private Mapping mappingAtStep(long accountId, String step) {
        return jdbc.query("""
                SELECT program_id, person_id, bank_account_id, card_account_id
                FROM column_card_accounts WHERE account_id = ? AND provisioning_step = ?
                """, (row, index) -> new Mapping(row.getString(1), row.getString(2), row.getString(3), row.getString(4)),
                accountId, step).stream().findFirst().orElse(null);
    }

    Attempt attempt(long accountId) {
        return jdbc.query("""
                SELECT reference, state, card_id FROM column_card_issuance WHERE account_id = ?
                """, (row, index) -> new Attempt(row.getString(1), row.getString(2), row.getString(3)),
                accountId).stream().findFirst().orElse(null);
    }

    Attempt claimIssuance(long accountId) {
        var reference = "Wizardric card " + UUID.randomUUID();
        return transaction.execute(status -> jdbc.update("""
                INSERT INTO column_card_issuance(account_id, reference) VALUES (?, ?)
                ON CONFLICT (account_id) DO NOTHING
                """, accountId, reference) == 1 ? new Attempt(reference, "pending", null) : null);
    }

    void recordIssuance(long accountId, String reference, String cardId) {
        int updated = transaction.execute(status -> jdbc.update("""
                UPDATE column_card_issuance SET state = ?, card_id = ?
                WHERE account_id = ? AND reference = ? AND card_id IS NULL
                """, cardId == null ? "review" : "issued", cardId, accountId, reference));
        if (updated != 1) {
            throw new IllegalStateException("The Column issuance claim changed.");
        }
    }

    String claimProvisioning(long accountId) {
        var reference = "Wizardric sandbox " + UUID.randomUUID();
        return transaction.execute(status -> jdbc.update("""
                INSERT INTO column_card_accounts(account_id, reference, provisioning_step) VALUES (?, ?, 'program')
                ON CONFLICT (account_id) DO NOTHING
                """, accountId, reference) == 1 ? reference : null);
    }

    void recordProvisioning(long accountId, String step, Mapping mapping) {
        transaction.executeWithoutResult(status -> jdbc.update("""
                UPDATE column_card_accounts
                SET provisioning_step = ?, program_id = ?, person_id = ?, bank_account_id = ?, card_account_id = ?
                WHERE account_id = ?
                """, step, mapping.programId(), mapping.personId(), mapping.bankAccountId(),
                mapping.cardAccountId(), accountId));
    }

    String provisioningStep(long accountId) {
        return jdbc.query("SELECT provisioning_step FROM column_card_accounts WHERE account_id = ?",
                (row, index) -> row.getString(1), accountId).stream().findFirst().orElse(null);
    }

    <T> T withCardLock(long accountId, Supplier<T> command) {
        // Negative session-lock keys are reserved for card commands. The connection stays in autocommit.
        // Store writes use a separate short transaction, so no database transaction spans a Column request.
        return jdbc.execute((ConnectionCallback<T>) connection -> {
            if (!connection.getAutoCommit()) {
                throw new IllegalStateException("Card commands require an autocommit connection.");
            }
            boolean acquired;
            try {
                acquired = lock(connection, "SELECT pg_try_advisory_lock(?)", -accountId);
            } catch (SQLException failure) {
                abort(connection, failure);
                throw failure;
            }
            if (!acquired) {
                throw new CardService.Conflict("A card command is already running. Refresh before trying again.");
            }
            try {
                return command.get();
            } finally {
                try {
                    if (!lock(connection, "SELECT pg_advisory_unlock(?)", -accountId)) {
                        throw new SQLException("The card command lock was lost.");
                    }
                } catch (SQLException failure) {
                    // A pooled connection must never be reused with an uncertain lock state.
                    abort(connection, failure);
                    throw failure;
                }
            }
        });
    }

    private static void abort(Connection connection, SQLException failure) {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException abortFailure) {
            failure.addSuppressed(abortFailure);
        }
    }

    private static boolean lock(Connection connection, String sql, long key) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, key);
            try (var result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }
}
