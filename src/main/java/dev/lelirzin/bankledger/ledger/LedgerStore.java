package dev.lelirzin.bankledger.ledger;

import static dev.lelirzin.bankledger.ledger.LedgerException.Reason.IDEMPOTENCY_CONFLICT;
import static dev.lelirzin.bankledger.ledger.LedgerException.Reason.INVALID_REQUEST;
import static dev.lelirzin.bankledger.ledger.LedgerException.Reason.NOT_FOUND;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class LedgerStore {

    private static final String DEBIT = "debit";
    private static final String CREDIT = "credit";
    private static final String ACCOUNT_COLUMNS = "id, code, name, kind, currency, normal_side, allow_overdraft, balance";
    private static final String SELECT_ACCOUNT = "SELECT " + ACCOUNT_COLUMNS + " FROM accounts WHERE id = ?";

    public record Account(long id, String code, String name, String kind, String currency, String normalSide,
                          boolean allowOverdraft, long balance) {}

    public record Entry(long accountId, String side, long amount) {}

    public record Posting(String key, String description, List<Entry> entries) {
        public Posting {
            description = Objects.requireNonNullElse(description, "");
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    public record Transfer(String key, long fromAccountId, long toAccountId, long amount) {}

    public record Result(long journalId, boolean replayed) {}

    public record Journal(long id, String description, Instant createdAt, List<Entry> entries) {
        public Journal {
            entries = List.copyOf(entries);
        }
    }

    private record JournalHeader(long id, String description, Instant createdAt) {}

    private record ExistingPosting(long id, byte[] hash) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate postingTransaction;
    private final TransactionTemplate callerTransaction;

    public LedgerStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        postingTransaction = new TransactionTemplate(transactionManager);
        postingTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        postingTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

        callerTransaction = new TransactionTemplate(transactionManager);
        callerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    public Account createAccount(Account account) {
        if (account.id() != 0 || account.balance() != 0) {
            throw new LedgerException(INVALID_REQUEST);
        }
        return jdbc.queryForObject("""
                INSERT INTO accounts(code, name, kind, currency, normal_side, allow_overdraft)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING
                """ + ACCOUNT_COLUMNS, LedgerStore::readAccount, account.code(), account.name(), account.kind(),
                account.currency(), account.normalSide(), account.allowOverdraft());
    }

    public Account account(long id) {
        return requireRow(jdbc.query(SELECT_ACCOUNT, LedgerStore::readAccount, id));
    }

    public List<Account> accounts() {
        return jdbc.query("SELECT " + ACCOUNT_COLUMNS + " FROM accounts ORDER BY id", LedgerStore::readAccount);
    }

    public Journal journal(long id) {
        var header = requireRow(jdbc.query("SELECT id, description, created_at FROM journals WHERE id = ?",
                (row, index) -> new JournalHeader(row.getLong("id"), row.getString("description"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant()), id));
        var entries = jdbc.query("""
                SELECT account_id, side, amount FROM entries WHERE journal_id = ? ORDER BY line
                """, (row, index) -> new Entry(row.getLong("account_id"), row.getString("side"), row.getLong("amount")), id);
        return new Journal(header.id(), header.description(), header.createdAt(), entries);
    }

    public Result post(Posting posting) {
        validate(posting);
        return postingTransaction.execute(status -> postJournal(posting));
    }

    /**
     * Posts in the caller's read-committed transaction. The result is provisional until that transaction commits.
     */
    public Result postInTransaction(Posting posting) {
        return callerTransaction.execute(status -> {
            validate(posting);
            return postJournal(posting);
        });
    }

    public Result transfer(Transfer transfer) {
        if (!validKey(transfer.key()) || transfer.fromAccountId() <= 0 || transfer.toAccountId() <= 0
                || transfer.fromAccountId() == transfer.toAccountId() || transfer.amount() <= 0) {
            throw new LedgerException(INVALID_REQUEST);
        }
        return postingTransaction.execute(status -> writeJournal(
                transfer.key(), "internal transfer", PostingFingerprint.hash(transfer), () -> transferEntries(transfer)));
    }

    private Result postJournal(Posting posting) {
        return writeJournal(posting.key(), posting.description(), PostingFingerprint.hash(posting), () -> {
            var accounts = lockAccounts(posting.entries().stream().map(Entry::accountId).toList());
            var orderedEntries = new ArrayList<>(posting.entries());
            // Apply increases first and retain the caller's order within each group.
            orderedEntries.sort(Comparator.comparing(
                    entry -> !entry.side().equals(accounts.get(entry.accountId()).normalSide())));
            return orderedEntries;
        });
    }

    private Result writeJournal(String key, String description, byte[] hash, Supplier<List<Entry>> prepareEntries) {
        var inserted = jdbc.query("""
                INSERT INTO journals(idempotency_key, request_hash, description) VALUES (?, ?, ?)
                ON CONFLICT(idempotency_key) DO NOTHING RETURNING id
                """, (row, index) -> row.getLong("id"), key, hash, description);
        if (inserted.isEmpty()) {
            var original = requireRow(jdbc.query("SELECT id, request_hash FROM journals WHERE idempotency_key = ?",
                    (row, index) -> new ExistingPosting(row.getLong("id"), row.getBytes("request_hash")), key));
            if (!Arrays.equals(hash, original.hash())) {
                throw new LedgerException(IDEMPOTENCY_CONFLICT);
            }
            return new Result(original.id(), true);
        }

        long journalId = inserted.getFirst();
        int line = 1;
        for (var entry : prepareEntries.get()) {
            jdbc.update("INSERT INTO entries(journal_id, line, account_id, side, amount) VALUES (?, ?, ?, ?, ?)",
                    journalId, line++, entry.accountId(), entry.side(), entry.amount());
        }
        return new Result(journalId, false);
    }

    private Map<Long, Account> lockAccounts(List<Long> ids) {
        var accounts = new HashMap<Long, Account>();
        String currency = null;
        for (long id : ids.stream().distinct().sorted().toList()) {
            var account = requireRow(jdbc.query(SELECT_ACCOUNT + " FOR UPDATE", LedgerStore::readAccount, id));
            if (currency != null && !currency.equals(account.currency())) {
                throw new LedgerException(INVALID_REQUEST);
            }
            currency = account.currency();
            accounts.put(id, account);
        }
        return accounts;
    }

    private List<Entry> transferEntries(Transfer transfer) {
        var accounts = lockAccounts(List.of(transfer.fromAccountId(), transfer.toAccountId()));
        String normalSide = accounts.get(transfer.fromAccountId()).normalSide();
        if (!normalSide.equals(accounts.get(transfer.toAccountId()).normalSide())) {
            throw new LedgerException(INVALID_REQUEST, "transfer accounts need the same normal side");
        }
        String decreaseSide = CREDIT.equals(normalSide) ? DEBIT : CREDIT;
        return List.of(new Entry(transfer.toAccountId(), normalSide, transfer.amount()),
                new Entry(transfer.fromAccountId(), decreaseSide, transfer.amount()));
    }

    private static void validate(Posting posting) {
        if (!validKey(posting.key()) || !fitsUtf8(posting.description(), 1000)
                || posting.entries().size() < 2 || posting.entries().size() > 1000) {
            throw new LedgerException(INVALID_REQUEST);
        }
        var total = BigInteger.ZERO;
        for (var entry : posting.entries()) {
            if (entry.accountId() <= 0 || entry.amount() <= 0
                    || (!DEBIT.equals(entry.side()) && !CREDIT.equals(entry.side()))) {
                throw new LedgerException(INVALID_REQUEST);
            }
            var amount = BigInteger.valueOf(entry.amount());
            total = DEBIT.equals(entry.side()) ? total.add(amount) : total.subtract(amount);
        }
        if (total.signum() != 0) {
            throw new LedgerException(INVALID_REQUEST);
        }
    }

    private static boolean validKey(String key) {
        return key != null && !key.isEmpty() && fitsUtf8(key, 200);
    }

    private static boolean fitsUtf8(String value, int maxBytes) {
        return value.length() <= maxBytes && StandardCharsets.UTF_8.newEncoder().canEncode(value)
                && value.getBytes(StandardCharsets.UTF_8).length <= maxBytes;
    }

    private static <T> T requireRow(List<T> rows) {
        if (rows.isEmpty()) {
            throw new LedgerException(NOT_FOUND);
        }
        return rows.getFirst();
    }

    private static Account readAccount(ResultSet row, int index) throws SQLException {
        return new Account(row.getLong("id"), row.getString("code"), row.getString("name"), row.getString("kind"),
                row.getString("currency"), row.getString("normal_side"), row.getBoolean("allow_overdraft"),
                row.getLong("balance"));
    }
}
