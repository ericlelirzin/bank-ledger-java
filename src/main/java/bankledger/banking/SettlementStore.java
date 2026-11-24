package bankledger.banking;

import static bankledger.ledger.LedgerException.Reason.IDEMPOTENCY_CONFLICT;

import java.util.List;

import bankledger.ledger.LedgerException;
import bankledger.ledger.LedgerStore;
import bankledger.ledger.LedgerStore.Entry;
import bankledger.ledger.LedgerStore.Posting;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class SettlementStore {

    private record Pending(long id, String destination, long amount, long cashId, long reservedId) {}

    private record Receipt(String destination, long amount) {}

    private final JdbcTemplate jdbc;
    private final LedgerStore ledger;
    private final TransactionTemplate transaction;

    public SettlementStore(JdbcTemplate jdbc, LedgerStore ledger, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    public boolean processOne() {
        var claimed = transaction.execute(status -> jdbc.query("""
                WITH due AS (
                    SELECT id FROM outgoing_transfers
                    WHERE status = 'pending' AND next_attempt_at <= statement_timestamp()
                    ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE outgoing_transfers t
                SET next_attempt_at = clock_timestamp() + interval '5 seconds'
                FROM due WHERE t.id = due.id
                RETURNING t.id
                """, (row, index) -> row.getLong("id")));
        if (claimed.isEmpty()) {
            return false;
        }

        // The retry time commits first, so a failed settlement or a stopped worker leaves the queue moving.
        return transaction.execute(status -> settle(claimed.getFirst()));
    }

    private boolean settle(long id) {
        var pending = jdbc.query("""
                SELECT t.id, t.destination, t.amount, b.cash_id, b.reserved_id
                FROM outgoing_transfers t JOIN bank_accounts b ON b.id = t.account_id
                WHERE t.id = ? AND t.status = 'pending' FOR UPDATE OF t SKIP LOCKED
                """, (row, index) -> new Pending(row.getLong("id"), row.getString("destination"),
                row.getLong("amount"), row.getLong("cash_id"), row.getLong("reserved_id")), id);
        if (pending.isEmpty()) {
            return false;
        }

        var transfer = pending.getFirst();
        acceptAtSimulatedBank(transfer);
        var journal = ledger.postInTransaction(new Posting("bank:settle:" + transfer.id(), "Settle outgoing transfer",
                List.of(new Entry(transfer.reservedId(), "debit", transfer.amount()),
                        new Entry(transfer.cashId(), "credit", transfer.amount()))));
        jdbc.update("""
                UPDATE outgoing_transfers SET status = 'settled', settlement_journal_id = ?,
                settled_at = clock_timestamp() WHERE id = ?
                """, journal.journalId(), transfer.id());
        return true;
    }

    private void acceptAtSimulatedBank(Pending transfer) {
        // A separate connection makes the receipt durable even if local settlement rolls back.
        transaction.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO simulated_bank_receipts(transfer_id, destination, amount)
                    VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                    """, transfer.id(), transfer.destination(), transfer.amount());
            var receipt = jdbc.queryForObject("""
                    SELECT destination, amount FROM simulated_bank_receipts WHERE transfer_id = ?
                    """, (row, index) -> new Receipt(row.getString("destination"), row.getLong("amount")), transfer.id());
            if (!transfer.destination().equals(receipt.destination()) || transfer.amount() != receipt.amount()) {
                throw new LedgerException(IDEMPOTENCY_CONFLICT);
            }
        });
    }
}
