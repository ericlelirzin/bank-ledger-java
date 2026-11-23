package dev.lelirzin.bankledger;

import static dev.lelirzin.bankledger.ledger.LedgerException.Reason.IDEMPOTENCY_CONFLICT;
import static dev.lelirzin.bankledger.ledger.LedgerException.Reason.INVALID_REQUEST;
import static dev.lelirzin.bankledger.ledger.LedgerException.Reason.NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import dev.lelirzin.bankledger.ledger.LedgerException;
import dev.lelirzin.bankledger.ledger.LedgerStore;
import dev.lelirzin.bankledger.ledger.LedgerStore.Account;
import dev.lelirzin.bankledger.ledger.LedgerStore.Entry;
import dev.lelirzin.bankledger.ledger.LedgerStore.Posting;
import dev.lelirzin.bankledger.ledger.LedgerStore.Result;
import dev.lelirzin.bankledger.ledger.LedgerStore.Transfer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class LedgerStoreTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            "postgres:" + System.getenv().getOrDefault("POSTGRES_MAJOR", "17") + "-bookworm");

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private LedgerStore ledger;

    @BeforeEach
    void createLedger() throws Exception {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        dataSource.setOptions("-c lock_timeout=5s -c statement_timeout=10s");
        jdbc = new JdbcTemplate(dataSource);
        String schema = "ledger_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        dataSource.setCurrentSchema(schema);
        new MigrationRunner(dataSource).apply();

        var manager = new JdbcTransactionManager(dataSource);
        transaction = new TransactionTemplate(manager);
        ledger = new LedgerStore(jdbc, manager);
    }

    @Test
    void replaysGoJournalAndTransferWithTheirOriginalIds() {
        var alice = account("alice", "USD", "debit");
        var bob = account("bob", "USD", "debit");
        var source = account("source", "USD", "credit");
        var funding = new Posting("go-入金<&>",
                "quote: \" slash: \\ controls:\b\f\n\r\t\013 separators:\u2028\u2029 emoji:🪙",
                List.of(new Entry(source.id(), "credit", 10_000), new Entry(alice.id(), "debit", 10_000)));
        var transfer = new Transfer("go-transfer🪙", alice.id(), bob.id(), 2_500);

        // Hashes captured from the pinned Go backend by submitting these exact requests.
        transaction.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO journals(idempotency_key, request_hash, description)
                    VALUES (?, decode('47510b75d329ae2beeaf5bdacd0bed25b3179582495c1feeff7f78d23c9755f0', 'hex'), ?),
                           (?, decode('e33ea945fc38f8db058b33bfae971d88305a126241f676eb5ec5ca547f6043b6', 'hex'),
                            'internal transfer')
                    """, funding.key(), funding.description(), transfer.key());
            jdbc.update("""
                    INSERT INTO entries(journal_id, line, account_id, side, amount)
                    VALUES (1, 1, ?, 'credit', 10000), (1, 2, ?, 'debit', 10000),
                           (2, 1, ?, 'debit', 2500), (2, 2, ?, 'credit', 2500)
                    """, source.id(), alice.id(), bob.id(), alice.id());
        });

        assertThat(ledger.post(funding)).isEqualTo(new Result(1, true));
        assertThat(ledger.transfer(transfer)).isEqualTo(new Result(2, true));
        assertThat(assertThrows(LedgerException.class, () -> ledger.post(
                new Posting(funding.key(), funding.description(), funding.entries().reversed()))).reason())
                .isEqualTo(IDEMPOTENCY_CONFLICT);
        assertThat(assertThrows(LedgerException.class, () -> ledger.transfer(
                new Transfer(transfer.key(), alice.id(), bob.id(), 2_501))).reason()).isEqualTo(IDEMPOTENCY_CONFLICT);
        assertThat(assertThrows(LedgerException.class, () -> ledger.post(new Posting(
                transfer.key(), "internal transfer", ledger.journal(2).entries()))).reason())
                .isEqualTo(IDEMPOTENCY_CONFLICT);
        assertThat(ledger.accounts()).extracting(Account::balance).containsExactly(7_500L, 2_500L, 10_000L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journals", Long.class)).isEqualTo(2L);
    }

    @Test
    void insertsIncreasesFirstAndKeepsTheOriginalOrderForReplay() {
        var account = account("cash", "USD", "debit");
        var entries = List.of(new Entry(account.id(), "credit", 6), new Entry(account.id(), "debit", 4),
                new Entry(account.id(), "debit", 3), new Entry(account.id(), "credit", 1));
        var posting = new Posting("zero-net", null, entries);

        var result = ledger.post(posting);

        assertThat(result.replayed()).isFalse();
        var journal = ledger.journal(result.journalId());
        assertThat(journal.description()).isEmpty();
        assertThat(journal.createdAt()).isNotNull();
        assertThat(journal.entries()).containsExactly(entries.get(1), entries.get(2), entries.get(0), entries.get(3));
        assertThat(posting.entries()).isEqualTo(entries);
        assertThat(ledger.account(account.id())).isEqualTo(account);
        assertThat(ledger.post(posting)).isEqualTo(new Result(result.journalId(), true));
    }

    @Test
    void keepsArbitraryPrecisionTotalsAndSigned64BitBalances() {
        var a = account("a", "USD", "debit");
        var b = account("b", "USD", "debit");
        var c = account("c", "USD", "credit");
        var d = account("d", "USD", "credit");
        long max = Long.MAX_VALUE;

        ledger.post(new Posting("full-range", "", List.of(new Entry(a.id(), "debit", max),
                new Entry(b.id(), "debit", max), new Entry(c.id(), "credit", max), new Entry(d.id(), "credit", max))));

        var overflow = new Transfer("overflow", a.id(), b.id(), 1);
        var failure = assertThrows(DataAccessException.class, () -> ledger.transfer(overflow));
        assertThat(sqlState(failure)).isEqualTo("22003");
        assertThat(ledger.accounts()).extracting(Account::balance).containsExactly(max, max, max, max);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journals", Long.class)).isEqualTo(1L);
    }

    @Test
    void validatesRequestCurrencyNormalSideAndMissingResources() {
        var cash = account("cash", "USD", "debit");
        var equity = account("equity", "USD", "credit");
        var euros = account("euros", "EUR", "debit");
        var fundingEntries = List.of(new Entry(cash.id(), "debit", 100), new Entry(equity.id(), "credit", 100));

        assertThat(ledger.account(cash.id()).balance()).isZero();
        assertThat(assertThrows(LedgerException.class, () -> ledger.account(999)).reason()).isEqualTo(NOT_FOUND);
        assertThat(assertThrows(LedgerException.class, () -> ledger.journal(999)).reason()).isEqualTo(NOT_FOUND);
        assertThat(assertThrows(LedgerException.class, () -> ledger.transfer(
                new Transfer("missing", cash.id(), 999, 1))).reason()).isEqualTo(NOT_FOUND);
        assertThat(assertThrows(LedgerException.class, () -> ledger.transfer(
                new Transfer("mixed-side", cash.id(), equity.id(), 1))).reason()).isEqualTo(INVALID_REQUEST);
        assertThat(assertThrows(LedgerException.class, () -> ledger.transfer(
                new Transfer("mixed-currency", cash.id(), euros.id(), 1))).reason()).isEqualTo(INVALID_REQUEST);
        assertThat(assertThrows(LedgerException.class, () -> ledger.post(
                new Posting("é".repeat(101), "", fundingEntries))).reason()).isEqualTo(INVALID_REQUEST);
        assertThat(assertThrows(LedgerException.class, () -> ledger.post(new Posting("unbalanced", "",
                List.of(fundingEntries.getFirst(), new Entry(equity.id(), "credit", 99))))).reason())
                .isEqualTo(INVALID_REQUEST);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journals", Long.class)).isZero();
    }

    @Test
    void joinsCallerTransactionAndRollsBackWhenDeferredValidationFails() {
        var cash = account("cash", "USD", "debit");
        var equity = account("equity", "USD", "credit");
        var posting = new Posting("caller-owned", "", List.of(new Entry(cash.id(), "debit", 100),
                new Entry(equity.id(), "credit", 100)));

        assertThrows(IllegalTransactionStateException.class, () -> ledger.postInTransaction(posting));
        var failure = assertThrows(DataAccessException.class, () -> transaction.executeWithoutResult(status -> {
            assertThat(ledger.postInTransaction(posting).replayed()).isFalse();
            jdbc.update("""
                    INSERT INTO journals(idempotency_key, request_hash, description)
                    VALUES ('incomplete', sha256('incomplete'::bytea), '')
                    """);
        }));

        assertThat(sqlState(failure)).isEqualTo("23514");
        assertThat(ledger.account(cash.id()).balance()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journals", Long.class)).isZero();
        assertThat(ledger.post(posting).replayed()).isFalse();
    }

    @Test
    void serializesSameKeyAndOpposingTransfers() throws Exception {
        var alice = account("alice", "USD", "debit");
        var bob = account("bob", "USD", "debit");
        var source = account("source", "USD", "credit");
        fund(alice, source);
        var same = new Transfer("same", alice.id(), bob.id(), 2_500);

        var repeated = concurrently(() -> ledger.transfer(same), () -> ledger.transfer(same));

        assertThat(repeated).extracting(Result::replayed).containsExactlyInAnyOrder(false, true);
        assertThat(repeated).extracting(Result::journalId).containsOnly(repeated.getFirst().journalId());

        var opposing = concurrently(
                () -> ledger.transfer(new Transfer("out", alice.id(), bob.id(), 1000)),
                () -> ledger.transfer(new Transfer("back", bob.id(), alice.id(), 1000)));
        assertThat(opposing).extracting(Result::replayed).containsOnly(false);
        assertThat(ledger.accounts()).extracting(Account::balance).containsExactly(7_500L, 2_500L, 10_000L);
    }

    @Test
    void rejectsCompetingOverspendWithoutConsumingTheFailedKey() throws Exception {
        var alice = account("alice", "USD", "debit");
        var bob = account("bob", "USD", "debit");
        var source = account("source", "USD", "credit");
        fund(alice, source);

        var attempts = concurrently(
                () -> tryTransfer("first", alice.id(), bob.id()),
                () -> tryTransfer("second", alice.id(), bob.id()));

        assertThat(attempts).filteredOn(attempt -> attempt.error() == null).hasSize(1);
        var rejected = attempts.stream().filter(attempt -> attempt.error() != null).findFirst().orElseThrow();
        assertThat(sqlState(rejected.error())).isEqualTo("23514");
        assertThat(ledger.accounts()).extracting(Account::balance).containsExactly(2_000L, 8_000L, 10_000L);

        assertThat(ledger.transfer(new Transfer(rejected.key(), alice.id(), bob.id(), 2_000)).replayed()).isFalse();
        assertThat(ledger.accounts()).extracting(Account::balance).containsExactly(0L, 10_000L, 10_000L);
    }

    private void fund(Account cash, Account source) {
        ledger.post(new Posting("fund", "", List.of(
                new Entry(cash.id(), "debit", 10_000), new Entry(source.id(), "credit", 10_000))));
    }

    private Account account(String code, String currency, String normalSide) {
        String kind = normalSide.equals("debit") ? "asset" : "equity";
        return ledger.createAccount(new Account(0, code, code, kind, currency, normalSide, false, 0));
    }

    private record TransferAttempt(String key, DataAccessException error) {}

    private TransferAttempt tryTransfer(String key, long from, long to) {
        try {
            ledger.transfer(new Transfer(key, from, to, 8_000));
            return new TransferAttempt(key, null);
        } catch (DataAccessException failure) {
            return new TransferAttempt(key, failure);
        }
    }

    private static String sqlState(Throwable failure) {
        var cause = NestedExceptionUtils.getMostSpecificCause(failure);
        assertThat(cause).isInstanceOf(SQLException.class);
        return ((SQLException) cause).getSQLState();
    }

    private static <T> List<T> concurrently(Callable<T> first, Callable<T> second) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstResult = executor.submit(() -> {
                start.await();
                return first.call();
            });
            var secondResult = executor.submit(() -> {
                start.await();
                return second.call();
            });
            start.countDown();
            return List.of(firstResult.get(15, TimeUnit.SECONDS), secondResult.get(15, TimeUnit.SECONDS));
        }
    }
}
