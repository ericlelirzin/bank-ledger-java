package bankledger.cards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import bankledger.banking.BankingStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CardServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            "postgres:" + System.getenv().getOrDefault("POSTGRES_MAJOR", "17") + "-bookworm");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private BankingStore banking;
    @Autowired private CardStore store;
    @Autowired private ColumnProvisioning provisioning;
    @Autowired private ObjectMapper json;

    private HttpServer stub;
    private long account;
    private CardService cards;
    private final AtomicReference<ObjectNode> remoteCard = new AtomicReference<>();
    private final AtomicInteger creates = new AtomicInteger();

    @BeforeEach
    void setup() throws IOException {
        account = banking.open("Column sandbox").id();
        mapAccount(account, "main");
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/bank-accounts/", exchange -> reply(exchange, 200,
                Map.of("id", "bank_main", "currency_code", "USD",
                        "balances", Map.of("available_amount", 9_007_199_254_740_993L, "holding_amount", 125))));
        stub.start();
        cards = newService();
    }

    @AfterEach
    void shutdown() {
        stub.stop(0);
        jdbc.update("DELETE FROM column_card_issuance WHERE account_id = ?", account);
        jdbc.update("DELETE FROM column_card_accounts WHERE account_id = ?", account);
    }

    @Test
    void commitsTheClaimBeforeSendingOneCreateAcrossOverlappingRequests() throws Exception {
        var arrived = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        stub.createContext("/issuing/cards", exchange -> {
            createCard(exchange);
            arrived.countDown();
            await(release);
            reply(exchange, 200, remoteCard.get());
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> cards.issue(account));
            try {
                assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue();
                var claim = store.attempt(account);
                assertThat(claim.state()).isEqualTo("pending");
                assertThat(remoteCard.get().path("description").asText()).isEqualTo(claim.reference());
                assertThatThrownBy(() -> newService().issue(account)).isInstanceOf(CardService.Conflict.class);
            } finally {
                release.countDown();
            }
            var result = first.get(8, TimeUnit.SECONDS);
            assertThat(result.card()).isEqualTo(new CardService.Card("0007", "active"));
            assertThat(result.balance().availableAmount()).isEqualTo(9_007_199_254_740_993L);
            assertThat(store.attempt(account).cardId()).isEqualTo("card_main");
            assertThat(creates).hasValue(1);
        }
    }

    @Test
    void adoptsTheCardAfterACommittedCreateDropsItsResponseAndTheClientRestarts() {
        var listings = new AtomicInteger();
        stub.createContext("/issuing/cards", exchange -> {
            if (exchange.getRequestMethod().equals("POST")) {
                createCard(exchange);
                exchange.close();
                return;
            }
            if (listings.incrementAndGet() == 1) {
                reply(exchange, 503, Map.of());
                return;
            }
            reply(exchange, 200, Map.of("cards", List.of(remoteCard.get()), "has_more", false));
        });

        assertThatThrownBy(() -> cards.issue(account)).isInstanceOf(ColumnClient.Failure.class);
        assertThat(store.attempt(account).state()).isEqualTo("pending");
        assertThat(newService().issue(account).issuanceState()).isEqualTo("issued");
        assertThat(store.attempt(account).cardId()).isEqualTo("card_main");
        assertThat(creates).hasValue(1);
    }

    @Test
    void requiresReviewForZeroMultipleOrUnverifiedMatchesAcrossPages() {
        var mode = new AtomicInteger();
        stub.createContext("/issuing/cards", exchange -> {
            if (exchange.getRequestMethod().equals("POST")) {
                createCard(exchange);
                exchange.close();
                return;
            }
            if (mode.get() == 0) {
                reply(exchange, 200, Map.of("cards", List.of(), "has_more", false));
            } else if (mode.get() == 1 && exchange.getRequestURI().getQuery().contains("starting_after=card_main")) {
                reply(exchange, 200, Map.of("cards", List.of(remoteCard.get().deepCopy().put("id", "card_second")),
                        "has_more", false));
            } else if (mode.get() == 1) {
                reply(exchange, 200, Map.of("cards", List.of(remoteCard.get()), "has_more", true));
            } else {
                reply(exchange, 200, Map.of("cards", List.of(remoteCard.get().deepCopy()
                        .put("authorized_user_entity_id", "person_other")), "has_more", false));
            }
        });

        assertThat(cards.issue(account).issuanceState()).isEqualTo("review");
        mode.set(1);
        assertThat(newService().issue(account).issuanceState()).isEqualTo("review");
        mode.set(2);
        assertThat(newService().issue(account).issuanceState()).isEqualTo("review");
        assertThat(store.attempt(account).cardId()).isNull();
        assertThat(creates).hasValue(1);
    }

    @Test
    void scopesActivityAndRejectsForeignDetailBeforeReleasingIt() {
        seedIssuedCard();
        var query = new AtomicReference<String>();
        stub.createContext("/issuing/card-transactions", exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            reply(exchange, 200, Map.of("card_transactions", List.of(transaction("main")), "has_more", true));
        });
        stub.createContext("/issuing/card-transactions/txn_main", exchange -> reply(exchange, 200, transaction("main")));
        stub.createContext("/issuing/card-transactions/txn_other", exchange -> reply(exchange, 200, transaction("other")));
        long unconfigured = banking.open("No sandbox mapping").id();
        assertThat(cards.overview(unconfigured).issuanceState()).isEqualTo("unconfigured");
        assertThatThrownBy(() -> cards.activity(unconfigured, null)).isInstanceOf(CardService.Missing.class);
        assertThat(query).hasNullValue();

        var page = cards.activity(account, null);
        assertThat(query.get()).isEqualTo("card_id=card_main&limit=25");
        assertThat(page.nextCursor()).isEqualTo("txn_main");
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo("txn_main");
            assertThat(item.heldAmount()).isEqualTo(125);
        });
        assertThat(cards.detail(account, "txn_main").events()).singleElement()
                .satisfies(event -> assertThat(event.decision()).isEqualTo("approved"));
        assertThatThrownBy(() -> cards.detail(account, "txn_other")).isInstanceOf(CardService.Missing.class);
    }

    @Test
    void serializesStatusCommandsAndRereadsColumnAfterThePauseTimesOut() throws Exception {
        seedIssuedCard();
        var paused = new CountDownLatch(1);
        var confirm = new CountDownLatch(1);
        var reads = new AtomicInteger();
        try (var handlers = Executors.newVirtualThreadPerTaskExecutor()) {
            stub.createContext("/issuing/cards/card_main", exchange -> {
                reads.incrementAndGet();
                reply(exchange, 200, remoteCard.get());
            });
            stub.createContext("/issuing/cards/card_main/pause", exchange -> {
                remoteCard.updateAndGet(card -> card.deepCopy().put("status", "paused"));
                paused.countDown();
                handlers.submit(() -> {
                    try {
                        await(confirm);
                    } finally {
                        exchange.close();
                    }
                    return null;
                });
            });
            stub.createContext("/issuing/cards/card_main/activate", exchange -> {
                remoteCard.updateAndGet(card -> card.deepCopy().put("status", "active"));
                reply(exchange, 200, remoteCard.get());
            });
            var command = handlers.submit(() -> cards.setPaused(account, true));
            try {
                assertThat(paused.await(4, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> newService().setPaused(account, false)).isInstanceOf(CardService.Conflict.class);
                assertThat(command.get(10, TimeUnit.SECONDS).status()).isEqualTo("paused");
                assertThat(reads).hasValue(2);
                assertThat(newService().setPaused(account, false).status()).isEqualTo("active");
            } finally {
                confirm.countDown();
            }
        }
    }

    @Test
    void provisionsOnlyAfterTheSandboxRootExistsAndDoesNotRecreateTheMapping() {
        long localAccount = banking.open("Provisioned sandbox").id();
        var rootExists = new AtomicReference<>(false);
        var requests = new AtomicInteger();
        stub.createContext("/entities", exchange -> reply(exchange, 200,
                Map.of("entities", rootExists.get() ? List.of(Map.of("id", "root_stub")) : List.of(), "has_more", false)));
        stub.createContext("/simulate/issuing/card-programs", exchange -> {
            requests.incrementAndGet();
            var request = json.readTree(exchange.getRequestBody());
            assertThat(request.path("type").textValue()).isEqualTo("debit");
            assertThat(request.path("scheme").textValue()).isEqualTo("visa");
            assertThat(request.path("jit_funding_enabled").booleanValue()).isFalse();
            reply(exchange, 200, Map.of("id", "program_provisioned", "status", "active",
                    "card_program_type", "debit", "primary_card_scheme", "visa", "jit_funding_enabled", false));
        });
        stub.createContext("/entities/person", exchange -> {
            requests.incrementAndGet();
            var request = json.readTree(exchange.getRequestBody());
            assertThat(request.path("is_root").booleanValue()).isFalse();
            reply(exchange, 200, Map.of("id", "person_provisioned"));
        });
        stub.createContext("/bank-accounts", exchange -> {
            requests.incrementAndGet();
            var request = json.readTree(exchange.getRequestBody());
            assertThat(request.path("entity_id").textValue()).isEqualTo("person_provisioned");
            reply(exchange, 200, Map.of("id", "bank_provisioned", "currency_code", "USD",
                    "default_account_number_id", "number_provisioned"));
        });
        stub.createContext("/simulate/receive-wire", exchange -> {
            requests.incrementAndGet();
            var request = json.readTree(exchange.getRequestBody());
            assertThat(request.path("amount").longValue()).isEqualTo(100_000);
            assertThat(request.path("destination_account_number_id").textValue()).isEqualTo("number_provisioned");
            reply(exchange, 200, Map.of("id", "wire_provisioned"));
        });
        var fundingReads = new AtomicInteger();
        stub.createContext("/bank-accounts/bank_provisioned", exchange -> reply(exchange, 200,
                Map.of("id", "bank_provisioned", "currency_code", "USD",
                        "balances", Map.of("available_amount", fundingReads.incrementAndGet() == 1 ? 0 : 100_000,
                                "holding_amount", 0))));
        stub.createContext("/issuing/card-accounts", exchange -> {
            requests.incrementAndGet();
            var request = json.readTree(exchange.getRequestBody());
            assertThat(request.path("card_program_id").textValue()).isEqualTo("program_provisioned");
            assertThat(request.path("bank_account_id").textValue()).isEqualTo("bank_provisioned");
            reply(exchange, 200, Map.of("id", "account_provisioned", "card_program_id", "program_provisioned",
                    "bank_account_id", "bank_provisioned"));
        });

        assertThatThrownBy(() -> provisioning.provision(localAccount, column())).hasMessageContaining("root entity");
        assertThat(store.provisioningStep(localAccount)).isNull();
        assertThat(requests).hasValue(0);

        rootExists.set(true);
        assertThat(provisioning.provision(localAccount, column())).contains("ready");
        assertThat(store.mapping(localAccount)).isEqualTo(new CardStore.Mapping(
                "program_provisioned", "person_provisioned", "bank_provisioned", "account_provisioned"));
        assertThat(provisioning.provision(localAccount, column())).contains("already configured");
        assertThat(requests).hasValue(5);
        assertThat(fundingReads).hasValue(2);
    }

    @Test
    void resumesFromConfirmedFundingWithoutSendingAnotherWire() {
        long localAccount = banking.open("Funded sandbox").id();
        store.claimProvisioning(localAccount);
        store.recordProvisioning(localAccount, "funding",
                new CardStore.Mapping("program_resume", "person_resume", "bank_resume", null));
        stub.createContext("/bank-accounts/bank_resume", exchange -> reply(exchange, 200,
                Map.of("id", "bank_resume", "currency_code", "USD", "balances", Map.of("available_amount", 100_000))));
        stub.createContext("/issuing/card-accounts", exchange -> reply(exchange, 200,
                Map.of("id", "account_resume", "card_program_id", "program_resume", "bank_account_id", "bank_resume")));

        // The stub has no root, program, person, bank account creation, or wire endpoints.
        assertThat(provisioning.provision(localAccount, column())).contains("ready");
        assertThat(store.mapping(localAccount).cardAccountId()).isEqualTo("account_resume");
    }

    private CardService newService() {
        return new CardService(store, column());
    }

    private ColumnClient column() {
        var uri = URI.create("http://127.0.0.1:" + stub.getAddress().getPort());
        return new ColumnClient(uri, "test_stub", json);
    }

    private void mapAccount(long id, String suffix) {
        jdbc.update("""
                INSERT INTO column_card_accounts(account_id, reference, provisioning_step, program_id,
                                                 person_id, bank_account_id, card_account_id)
                VALUES (?, ?, 'ready', ?, ?, ?, ?)
                """, id, "reference_" + id, "program_" + suffix, "person_" + suffix,
                "bank_" + suffix, "account_" + suffix);
    }

    private void seedIssuedCard() {
        var attempt = store.claimIssuance(account);
        remoteCard.set(card(attempt.reference()));
        store.recordIssuance(account, attempt.reference(), "card_main");
    }

    private void createCard(HttpExchange exchange) throws IOException {
        creates.incrementAndGet();
        var request = json.readTree(exchange.getRequestBody());
        remoteCard.set(card(request.path("description").asText()));
    }

    private ObjectNode card(String reference) {
        return json.createObjectNode().put("id", "card_main").put("description", reference).put("type", "virtual")
                .put("card_account_id", "account_main").put("card_program_id", "program_main")
                .put("authorized_user_entity_id", "person_main").put("last_four_digits", "0007").put("status", "active");
    }

    private ObjectNode transaction(String suffix) {
        var scope = Map.of("card_account_id", "account_" + suffix, "card_program_id", "program_" + suffix,
                "card_id", "card_" + suffix);
        var amount = Map.of("cardholder_authorized", Map.of("amount", 125L, "currency_code", "USD"));
        ObjectNode row = json.valueToTree(scope);
        ObjectNode event = json.valueToTree(scope);
        event.put("event_type", "authorization").put("created_at", "2026-11-26T09:00:00Z");
        event.set("amounts", json.valueToTree(amount));
        event.putObject("decisioning_details").put("decision", "approved");
        row.put("id", "txn_" + suffix).put("type", "purchase").put("created_at", "2026-11-26T09:00:00Z");
        row.set("total_amounts", json.valueToTree(amount));
        row.putArray("events").add(event);
        return row;
    }

    private void reply(HttpExchange exchange, int status, Object body) throws IOException {
        var bytes = json.writeValueAsBytes(body);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var response = exchange.getResponseBody()) {
            response.write(bytes);
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IOException("The test did not release the Column stub.");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException(failure);
        }
    }
}
