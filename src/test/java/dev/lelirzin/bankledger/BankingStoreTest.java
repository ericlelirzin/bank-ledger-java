package dev.lelirzin.bankledger;

import static dev.lelirzin.bankledger.ledger.LedgerException.Reason.IDEMPOTENCY_CONFLICT;
import static dev.lelirzin.bankledger.ledger.LedgerException.Reason.INVALID_REQUEST;
import static dev.lelirzin.bankledger.ledger.LedgerException.Reason.NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import dev.lelirzin.bankledger.banking.BankingStore;
import dev.lelirzin.bankledger.banking.BankingStore.Account;
import dev.lelirzin.bankledger.banking.BankingStore.BeneficiaryRequest;
import dev.lelirzin.bankledger.banking.BankingStore.Event;
import dev.lelirzin.bankledger.banking.BankingStore.TransferRequest;
import dev.lelirzin.bankledger.ledger.LedgerException;
import dev.lelirzin.bankledger.ledger.LedgerStore;
import dev.lelirzin.bankledger.ledger.LedgerStore.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class BankingStoreTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            "postgres:" + System.getenv().getOrDefault("POSTGRES_MAJOR", "17") + "-bookworm");

    private JdbcTemplate jdbc;
    private BankingStore banking;

    @BeforeEach
    void createBankingStore() throws Exception {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        dataSource.setOptions("-c lock_timeout=5s -c statement_timeout=10s");
        jdbc = new JdbcTemplate(dataSource);
        String schema = "banking_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        dataSource.setCurrentSchema(schema);
        new MigrationRunner(dataSource).apply();

        var manager = new JdbcTransactionManager(dataSource);
        banking = new BankingStore(jdbc, new LedgerStore(jdbc, manager), manager);
    }

    @Test
    void fundsReservesAndSettlesWithReplayProtection() {
        assertThat(banking.processOne()).isFalse();
        var account = banking.open("Demo");
        assertThat(account).isEqualTo(new Account(account.id(), "Demo", "USD", 0, 0, 0));

        var funding = banking.fund(account.id(), 10_000, "fund");
        assertThat(funding.replayed()).isFalse();
        assertThat(banking.fund(account.id(), 10_000, "fund")).isEqualTo(new Result(funding.journalId(), true));
        assertThat(assertThrows(LedgerException.class, () -> banking.fund(account.id(), 9_999, "fund")).reason())
                .isEqualTo(IDEMPOTENCY_CONFLICT);

        var request = new TransferRequest("send", account.id(), "SIM-DESTINATION", null, 2_500);
        var pending = banking.createTransfer(request);
        assertThat(pending.status()).isEqualTo("pending");
        assertThat(pending.reserveJournalId()).isNotNull();
        assertThat(pending.settlementJournalId()).isNull();
        assertThat(pending.settledAt()).isNull();
        assertThat(banking.createTransfer(request)).isEqualTo(pending);
        assertThat(assertThrows(LedgerException.class, () -> banking.createTransfer(
                new TransferRequest("send", account.id(), "SIM-OTHER", null, 2_500))).reason())
                .isEqualTo(IDEMPOTENCY_CONFLICT);
        assertThat(banking.account(account.id())).isEqualTo(new Account(account.id(), "Demo", "USD", 10_000, 7_500, 2_500));

        assertThat(banking.processOne()).isTrue();
        var settled = banking.transfer(pending.id());
        assertThat(settled.status()).isEqualTo("settled");
        assertThat(settled.reserveJournalId()).isEqualTo(pending.reserveJournalId());
        assertThat(settled.settlementJournalId()).isNotNull();
        assertThat(settled.settledAt()).isNotNull();
        assertThat(banking.createTransfer(request)).isEqualTo(settled);
        assertThat(banking.processOne()).isFalse();
        assertThat(banking.account(account.id())).isEqualTo(new Account(account.id(), "Demo", "USD", 7_500, 7_500, 0));
        assertThat(journalCount()).isEqualTo(3);
        assertThat(receipt(settled.id())).containsEntry("destination", "SIM-DESTINATION").containsEntry("amount", 2_500L);
    }

    @Test
    void preservesReceiptAcrossCommitFailureAndRetriesWithoutPayingTwice() throws Exception {
        long accountId = fundedAccount();
        var pending = banking.createTransfer(new TransferRequest("send", accountId, "SIM-DESTINATION", null, 2_500));
        jdbc.execute("""
                CREATE FUNCTION reject_settlement() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'test settlement failure' USING ERRCODE = '23514';
                END $$;
                CREATE CONSTRAINT TRIGGER reject_settlement
                AFTER UPDATE ON outgoing_transfers DEFERRABLE INITIALLY DEFERRED
                FOR EACH ROW WHEN (NEW.status = 'settled') EXECUTE FUNCTION reject_settlement();
                """);

        assertThat(sqlState(assertThrows(DataAccessException.class, banking::processOne))).isEqualTo("23514");
        var originalReceipt = receipt(pending.id());
        assertThat(banking.transfer(pending.id())).isEqualTo(pending);
        assertThat(banking.account(accountId).reservedBalance()).isEqualTo(2_500);
        assertThat(journalCount()).isEqualTo(2);
        var eventsAfterFailure = banking.timeline(pending.id()).events();
        assertThat(eventsAfterFailure).extracting(Event::kind)
                .containsExactly("transfer_created", "funds_reserved", "bank_confirmed");
        assertThat(eventsAfterFailure.getLast().providerReference()).isEqualTo(pending.id());

        jdbc.execute("DROP TRIGGER reject_settlement ON outgoing_transfers");
        assertThat(concurrently(banking::processOne, banking::processOne)).containsExactlyInAnyOrder(true, false);
        assertThat(receipt(pending.id())).isEqualTo(originalReceipt);
        assertThat(banking.transfer(pending.id()).status()).isEqualTo("settled");
        assertThat(banking.timeline(pending.id()).events().subList(0, 3)).isEqualTo(eventsAfterFailure);
        assertThat(banking.account(accountId)).isEqualTo(new Account(accountId, "Demo", "USD", 7_500, 7_500, 0));
        assertThat(journalCount()).isEqualTo(3);
    }

    @Test
    void rejectsCompetingReservationsAndKeepsTheFailedKeyReusable() throws Exception {
        long accountId = fundedAccount();
        var attempts = concurrently(() -> reserve("first", accountId), () -> reserve("second", accountId));

        assertThat(attempts).filteredOn(attempt -> attempt.failure() == null).hasSize(1);
        var rejected = attempts.stream().filter(attempt -> attempt.failure() != null).findFirst().orElseThrow();
        assertThat(sqlState(rejected.failure())).isEqualTo("23514");
        assertThat(banking.account(accountId)).isEqualTo(new Account(accountId, "Demo", "USD", 10_000, 2_000, 8_000));
        assertThat(journalCount()).isEqualTo(2);

        var retried = banking.createTransfer(new TransferRequest(rejected.key(), accountId, "SIM-DESTINATION", null, 2_000));
        assertThat(retried.status()).isEqualTo("pending");
        assertThat(banking.account(accountId).reservedBalance()).isEqualTo(10_000);
    }

    @Test
    void usesAnAccountScopedBeneficiaryAndPreservesItsDestination() {
        long accountId = fundedAccount();
        long otherId = banking.open("Other").id();
        long beneficiaryId = jdbc.queryForObject("""
                INSERT INTO beneficiaries(account_id, idempotency_key, name, destination)
                VALUES (?, 'recipient', 'Sample Recipient', 'SIM-RECIPIENT') RETURNING id
                """, Long.class, accountId);

        assertThat(assertThrows(LedgerException.class, () -> banking.createTransfer(
                new TransferRequest("other", otherId, null, beneficiaryId, 100))).reason()).isEqualTo(NOT_FOUND);
        assertThat(assertThrows(LedgerException.class, () -> banking.createTransfer(
                new TransferRequest("both", accountId, "SIM-RECIPIENT", beneficiaryId, 100))).reason())
                .isEqualTo(INVALID_REQUEST);
        var request = new TransferRequest("send", accountId, null, beneficiaryId, 100);
        var transfer = banking.createTransfer(request);

        assertThat(transfer.beneficiaryId()).isEqualTo(beneficiaryId);
        assertThat(transfer.destination()).isEqualTo("SIM-RECIPIENT");
        assertThat(banking.createTransfer(request)).isEqualTo(transfer);
        assertThat(assertThrows(LedgerException.class, () -> banking.createTransfer(
                new TransferRequest("send", accountId, "SIM-RECIPIENT", null, 100))).reason()).isEqualTo(IDEMPOTENCY_CONFLICT);
    }

    @Test
    void concurrentBeneficiaryRequestsReplayOneRecord() throws Exception {
        long accountId = banking.open("Demo").id();
        var request = new BeneficiaryRequest("recipient", "Sample Recipient", "SIM-RECIPIENT");
        var attempts = concurrently(() -> banking.createBeneficiary(accountId, request),
                () -> banking.createBeneficiary(accountId, request));

        assertThat(attempts.getFirst()).isEqualTo(attempts.getLast());
        assertThat(banking.beneficiaries(accountId, 0, 50).items()).containsExactly(attempts.getFirst());
    }

    private long fundedAccount() {
        long id = banking.open("Demo").id();
        banking.fund(id, 10_000, "fund");
        return id;
    }

    private long journalCount() {
        return jdbc.queryForObject("SELECT count(*) FROM journals", Long.class);
    }

    private Map<String, Object> receipt(long transferId) {
        return jdbc.queryForMap("SELECT * FROM simulated_bank_receipts WHERE transfer_id = ?", transferId);
    }

    private record ReservationAttempt(String key, DataAccessException failure) {}

    private ReservationAttempt reserve(String key, long accountId) {
        try {
            banking.createTransfer(new TransferRequest(key, accountId, "SIM-DESTINATION", null, 8_000));
            return new ReservationAttempt(key, null);
        } catch (DataAccessException failure) {
            return new ReservationAttempt(key, failure);
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
