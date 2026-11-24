package dev.lelirzin.bankledger.banking;

import static dev.lelirzin.bankledger.ledger.LedgerException.Reason.IDEMPOTENCY_CONFLICT;
import static dev.lelirzin.bankledger.ledger.LedgerException.Reason.INVALID_REQUEST;
import static dev.lelirzin.bankledger.ledger.LedgerException.Reason.NOT_FOUND;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.lelirzin.bankledger.ledger.LedgerException;
import dev.lelirzin.bankledger.ledger.LedgerStore;
import dev.lelirzin.bankledger.ledger.LedgerStore.Entry;
import dev.lelirzin.bankledger.ledger.LedgerStore.Posting;
import dev.lelirzin.bankledger.ledger.LedgerStore.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class BankingStore {

    private static final String USD = "USD";
    private static final Pattern WHITESPACE = Pattern.compile("\\s*", Pattern.UNICODE_CHARACTER_CLASS);
    private static final String BENEFICIARY_COLUMNS = "id, account_id, name, destination, created_at";
    private static final String TRANSFER_COLUMNS = """
            id, account_id, destination, amount, status, reserve_journal_id, settlement_journal_id,
            created_at, settled_at, beneficiary_id
            """;
    private static final String ACCOUNT_PAGE = " WHERE account_id = ? AND id <= ? ORDER BY id DESC LIMIT ?";

    public record Account(long id, String name, String currency, long postedBalance, long availableBalance,
                          long reservedBalance) {}

    public record BeneficiaryRequest(@JsonProperty("idempotency_key") String key, String name, String destination) {}

    public record Beneficiary(long id, long accountId, String name, String destination, Instant createdAt) {}

    public record TransferRequest(@JsonProperty("idempotency_key") String key, long accountId, String destination,
                                  Long beneficiaryId, long amount) {
        public TransferRequest {
            destination = Objects.requireNonNullElse(destination, "");
        }
    }

    public record Transfer(long id, long accountId, String destination,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Long beneficiaryId, long amount, String status,
                           Long reserveJournalId, Long settlementJournalId, Instant createdAt, Instant settledAt) {}

    public record Page<T>(List<T> items, Long nextBeforeId) {
        public Page {
            items = List.copyOf(items);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Event(String kind, Instant occurredAt, Long journalId, Long providerReference) {}

    public record Timeline(long transferId, List<Event> events) {
        public Timeline {
            events = List.copyOf(events);
        }
    }

    private record BackingAccounts(long cash, long available, long reserved) {}

    private record Receipt(String destination, long amount) {}

    private final JdbcTemplate jdbc;
    private final LedgerStore ledger;
    private final TransactionTemplate transaction;

    public BankingStore(JdbcTemplate jdbc, LedgerStore ledger, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    public Account open(String name) {
        if (!validText(name, 200)) {
            throw new LedgerException(INVALID_REQUEST);
        }
        return transaction.execute(status -> {
            long id = jdbc.queryForObject("SELECT nextval(pg_get_serial_sequence('bank_accounts', 'id'))", Long.class);
            long cash = createBackingAccount(id, "cash", "asset", "debit");
            long available = createBackingAccount(id, "available", "liability", "credit");
            long reserved = createBackingAccount(id, "reserved", "liability", "credit");
            jdbc.update("""
                    INSERT INTO bank_accounts(id, name, cash_id, available_id, reserved_id)
                    OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, ?, ?)
                    """, id, name, cash, available, reserved);
            return new Account(id, name, USD, 0, 0, 0);
        });
    }

    public Account account(long id) {
        return requireRow(jdbc.query("""
                SELECT b.id, b.name, b.currency, c.balance AS posted, a.balance AS available, r.balance AS reserved
                FROM bank_accounts b JOIN accounts c ON c.id = b.cash_id
                JOIN accounts a ON a.id = b.available_id JOIN accounts r ON r.id = b.reserved_id
                WHERE b.id = ?
                """, (row, index) -> new Account(row.getLong("id"), row.getString("name"), row.getString("currency"),
                row.getLong("posted"), row.getLong("available"), row.getLong("reserved")), id));
    }

    public Result fund(long id, long amount, String key) {
        if (id <= 0 || amount <= 0 || !validText(key, 200)) {
            throw new LedgerException(INVALID_REQUEST);
        }
        return transaction.execute(status -> {
            var accounts = backingAccounts(id);
            return ledger.postInTransaction(new Posting(fundingKey(key), "Simulated inbound funding", List.of(
                    new Entry(accounts.cash(), "debit", amount), new Entry(accounts.available(), "credit", amount))));
        });
    }

    public Beneficiary createBeneficiary(long accountId, BeneficiaryRequest request) {
        if (accountId <= 0 || !validText(request.key(), 200) || !validText(request.name(), 200)
                || !validText(request.destination(), 100)) {
            throw new LedgerException(INVALID_REQUEST);
        }
        return transaction.execute(status -> {
            var inserted = jdbc.query("""
                    INSERT INTO beneficiaries(account_id, idempotency_key, name, destination)
                    SELECT id, ?, ?, ? FROM bank_accounts WHERE id = ?
                    ON CONFLICT(account_id, idempotency_key) DO NOTHING RETURNING
                    """ + BENEFICIARY_COLUMNS, BankingStore::readBeneficiary,
                    request.key(), request.name(), request.destination(), accountId);
            if (!inserted.isEmpty()) {
                return inserted.getFirst();
            }

            // After a concurrent insert commits, read its result in a new statement snapshot.
            var prior = requireRow(jdbc.query("SELECT " + BENEFICIARY_COLUMNS
                    + " FROM beneficiaries WHERE account_id = ? AND idempotency_key = ?",
                    BankingStore::readBeneficiary, accountId, request.key()));
            if (!prior.name().equals(request.name()) || !prior.destination().equals(request.destination())) {
                throw new LedgerException(IDEMPOTENCY_CONFLICT);
            }
            return prior;
        });
    }

    public Page<Beneficiary> beneficiaries(long accountId, long beforeId, int limit) {
        requirePageAccount(accountId, beforeId, limit);
        var rows = jdbc.query("SELECT " + BENEFICIARY_COLUMNS + " FROM beneficiaries" + ACCOUNT_PAGE,
                BankingStore::readBeneficiary, accountId, pageUpperBound(beforeId), limit + 1);
        return page(rows, limit, Beneficiary::id);
    }

    public Page<Transfer> transfers(long accountId, long beforeId, int limit) {
        requirePageAccount(accountId, beforeId, limit);
        var rows = jdbc.query("SELECT " + TRANSFER_COLUMNS + "FROM outgoing_transfers" + ACCOUNT_PAGE,
                BankingStore::readTransfer, accountId, pageUpperBound(beforeId), limit + 1);
        return page(rows, limit, Transfer::id);
    }

    public Transfer createTransfer(TransferRequest request) {
        if (!validText(request.key(), 200) || request.accountId() <= 0 || request.amount() <= 0
                || !validDestination(request)) {
            throw new LedgerException(INVALID_REQUEST);
        }
        return transaction.execute(status -> {
            String destination = resolveDestination(request);
            var inserted = jdbc.query("""
                    INSERT INTO outgoing_transfers(idempotency_key, account_id, destination, amount, beneficiary_id)
                    SELECT ?, id, ?, ?, ? FROM bank_accounts WHERE id = ?
                    ON CONFLICT(idempotency_key) DO NOTHING RETURNING id
                    """, (row, index) -> row.getLong("id"), request.key(), destination,
                    request.amount(), request.beneficiaryId(), request.accountId());
            if (inserted.isEmpty()) {
                return replayTransfer(request, destination);
            }

            long id = inserted.getFirst();
            var accounts = backingAccounts(request.accountId());
            var journal = ledger.postInTransaction(new Posting("bank:reserve:" + id, "Reserve outgoing transfer",
                    List.of(new Entry(accounts.available(), "debit", request.amount()),
                            new Entry(accounts.reserved(), "credit", request.amount()))));
            jdbc.update("UPDATE outgoing_transfers SET reserve_journal_id = ? WHERE id = ?", journal.journalId(), id);
            return transfer(id);
        });
    }

    public Transfer transfer(long id) {
        return requireRow(jdbc.query("SELECT " + TRANSFER_COLUMNS + "FROM outgoing_transfers WHERE id = ?",
                BankingStore::readTransfer, id));
    }

    public Timeline timeline(long id) {
        if (id <= 0) {
            throw new LedgerException(INVALID_REQUEST);
        }
        // A single snapshot also sees receipts that survived an aborted settlement.
        var events = jdbc.query("""
                WITH transfer AS (SELECT * FROM outgoing_transfers WHERE id = ?),
                facts(step, kind, occurred_at, journal_id, provider_reference) AS (
                    SELECT 1, 'transfer_created', t.created_at, NULL::bigint, NULL::bigint FROM transfer t
                    UNION ALL
                    SELECT 2, 'funds_reserved', j.created_at, j.id, NULL::bigint
                    FROM transfer t JOIN journals j ON j.id = t.reserve_journal_id
                    UNION ALL
                    SELECT 3, 'bank_confirmed', r.created_at, NULL::bigint, r.transfer_id
                    FROM transfer t JOIN simulated_bank_receipts r ON r.transfer_id = t.id
                    UNION ALL
                    SELECT 4, 'transfer_settled', t.settled_at, t.settlement_journal_id, NULL::bigint
                    FROM transfer t WHERE t.status = 'settled'
                )
                SELECT kind, occurred_at, journal_id, provider_reference FROM facts ORDER BY step
                """, (row, index) -> new Event(row.getString("kind"),
                row.getObject("occurred_at", OffsetDateTime.class).toInstant(), row.getObject("journal_id", Long.class),
                row.getObject("provider_reference", Long.class)), id);
        if (events.isEmpty()) {
            throw new LedgerException(NOT_FOUND);
        }
        return new Timeline(id, events);
    }

    public boolean processOne() {
        return transaction.execute(status -> {
            var pending = jdbc.query("SELECT " + TRANSFER_COLUMNS + """
                    FROM outgoing_transfers WHERE status = 'pending' ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1
                    """, BankingStore::readTransfer);
            if (pending.isEmpty()) {
                return false;
            }

            var transfer = pending.getFirst();
            acceptAtSimulatedBank(transfer);
            var accounts = backingAccounts(transfer.accountId());
            var journal = ledger.postInTransaction(new Posting("bank:settle:" + transfer.id(), "Settle outgoing transfer",
                    List.of(new Entry(accounts.reserved(), "debit", transfer.amount()),
                            new Entry(accounts.cash(), "credit", transfer.amount()))));
            jdbc.update("""
                    UPDATE outgoing_transfers SET status = 'settled', settlement_journal_id = ?,
                    settled_at = clock_timestamp() WHERE id = ?
                    """, journal.journalId(), transfer.id());
            return true;
        });
    }

    private void acceptAtSimulatedBank(Transfer transfer) {
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

    private Transfer replayTransfer(TransferRequest request, String destination) {
        var prior = requireRow(jdbc.query("SELECT " + TRANSFER_COLUMNS + """
                FROM outgoing_transfers WHERE idempotency_key = ?
                """, BankingStore::readTransfer, request.key()));
        if (prior.accountId() != request.accountId() || prior.amount() != request.amount()
                || !prior.destination().equals(destination) || !Objects.equals(prior.beneficiaryId(), request.beneficiaryId())) {
            throw new LedgerException(IDEMPOTENCY_CONFLICT);
        }
        return prior;
    }

    private long createBackingAccount(long id, String role, String kind, String normalSide) {
        var account = new LedgerStore.Account(0, "bank:" + id + ":" + role, "Bank account " + id + " " + role,
                kind, USD, normalSide, false, 0);
        return ledger.createAccount(account).id();
    }

    private BackingAccounts backingAccounts(long id) {
        return requireRow(jdbc.query("SELECT cash_id, available_id, reserved_id FROM bank_accounts WHERE id = ?",
                (row, index) -> new BackingAccounts(row.getLong("cash_id"), row.getLong("available_id"),
                        row.getLong("reserved_id")), id));
    }

    private String resolveDestination(TransferRequest request) {
        if (request.beneficiaryId() == null) {
            return request.destination();
        }
        return requireRow(jdbc.query("SELECT destination FROM beneficiaries WHERE account_id = ? AND id = ?",
                (row, index) -> row.getString("destination"), request.accountId(), request.beneficiaryId()));
    }

    private static boolean validDestination(TransferRequest request) {
        if (request.beneficiaryId() != null) {
            return request.beneficiaryId() > 0 && request.destination().isEmpty();
        }
        return validText(request.destination(), 100);
    }

    private void requirePageAccount(long accountId, long beforeId, int limit) {
        if (accountId <= 0 || beforeId < 0 || limit < 1 || limit > 100) {
            throw new LedgerException(INVALID_REQUEST);
        }
        if (!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bank_accounts WHERE id = ?)", Boolean.class, accountId)) {
            throw new LedgerException(NOT_FOUND);
        }
    }

    private static <T> Page<T> page(List<T> rows, int limit, ToLongFunction<T> id) {
        if (rows.size() <= limit) {
            return new Page<>(rows, null);
        }
        return new Page<>(rows.subList(0, limit), id.applyAsLong(rows.get(limit - 1)));
    }

    private static long pageUpperBound(long beforeId) {
        return beforeId == 0 ? Long.MAX_VALUE : beforeId - 1;
    }

    private static boolean validText(String value, int bytes) {
        return value != null && value.length() <= bytes && !WHITESPACE.matcher(value).matches()
                && StandardCharsets.UTF_8.newEncoder().canEncode(value)
                && value.getBytes(StandardCharsets.UTF_8).length <= bytes;
    }

    private static String fundingKey(String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return "bank:fund:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static Transfer readTransfer(ResultSet row, int index) throws SQLException {
        var settledAt = row.getObject("settled_at", OffsetDateTime.class);
        return new Transfer(row.getLong("id"), row.getLong("account_id"), row.getString("destination"),
                row.getObject("beneficiary_id", Long.class), row.getLong("amount"), row.getString("status"),
                row.getObject("reserve_journal_id", Long.class), row.getObject("settlement_journal_id", Long.class),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                settledAt == null ? null : settledAt.toInstant());
    }

    private static Beneficiary readBeneficiary(ResultSet row, int index) throws SQLException {
        return new Beneficiary(row.getLong("id"), row.getLong("account_id"), row.getString("name"),
                row.getString("destination"), row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static <T> T requireRow(List<T> rows) {
        if (rows.isEmpty()) {
            throw new LedgerException(NOT_FOUND);
        }
        return rows.getFirst();
    }
}
