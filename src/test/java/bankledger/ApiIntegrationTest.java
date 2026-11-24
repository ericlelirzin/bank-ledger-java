package bankledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import bankledger.banking.SettlementStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            "postgres:" + System.getenv().getOrDefault("POSTGRES_MAJOR", "17") + "-bookworm");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private SettlementStore settlement;

    @Test
    void exposesBankingLifecycleAndCursorPages() throws Exception {
        assertThat(get("/healthz", 200).path("status").asText()).isEqualTo("ok");
        var account = post("/bank/accounts", """
                {"NAME":"Everyday"}
                """, 200);
        assertThat(account.path("available_balance").longValue()).isZero();
        long accountId = account.path("id").longValue();
        String path = "/bank/accounts/" + accountId;
        String recipients = path + "/beneficiaries";
        String transfers = path + "/transfers";
        var empty = get(recipients, 200);
        assertThat(empty.path("items").isArray()).isTrue();
        assertThat(empty.path("items")).isEmpty();
        assertThat(empty.path("next_before_id").isNull()).isTrue();

        String recipient = """
                {"idempotency_key":"recipient","name":"Sam","destination":"SIM-SAM"}
                """;
        var saved = post(recipients, recipient, 200);
        assertThat(saved.path("account_id").longValue()).isEqualTo(accountId);
        assertThat(post(recipients, recipient, 200)).isEqualTo(saved);
        assertThat(post(recipients, recipient.replace("Sam", "Avery"), 409).path("error").asText())
                .isEqualTo("idempotency key reused with different request");
        long beneficiaryId = saved.path("id").longValue();
        long newestRecipient = post(recipients, recipient.replace("recipient", "recipient-2"), 200).path("id").longValue();
        assertPage(recipients, newestRecipient, beneficiaryId);
        var other = post("/bank/accounts", "{\"name\":\"Other\"}", 200);
        long otherId = other.path("id").longValue();
        assertThat(get("/bank/accounts/" + otherId + "/beneficiaries", 200).path("items")).isEmpty();
        assertThat(post("/bank/accounts/" + otherId + "/beneficiaries", recipient, 200).path("id").longValue())
                .isNotEqualTo(beneficiaryId);

        var funding = post(path + "/simulated-funding", "{\"idempotency_key\":\"fund-api\",\"amount\":10000}", 200);
        assertThat(funding.path("replayed").booleanValue()).isFalse();
        assertThat(get("/journals/" + funding.path("journal_id").longValue(), 200).path("entries")).hasSize(2);
        String payment = """
                {"idempotency_key":"send-api","account_id":%d,"beneficiary_id":%d,"amount":2500}
                """;
        post("/bank/transfers", payment.formatted(otherId, beneficiaryId), 404);
        var pending = post("/bank/transfers", payment.formatted(accountId, beneficiaryId), 200);
        long transferId = pending.path("id").longValue();
        String transfer = "/bank/transfers/" + transferId;
        assertThat(pending.path("status").asText()).isEqualTo("pending");
        assertThat(pending.path("settlement_journal_id").isNull()).isTrue();
        assertThat(pending.path("settled_at").isNull()).isTrue();
        assertThat(pending.path("destination").asText()).isEqualTo("SIM-SAM");
        assertThat(get(transfer, 200)).isEqualTo(pending);
        assertThat(get(path, 200).path("reserved_balance").longValue()).isEqualTo(2_500);

        var direct = post("/bank/transfers", """
                {"idempotency_key":"direct-api","account_id":%d,"destination":"DIRECT","amount":1000}
                """.formatted(accountId), 200);
        assertThat(direct.has("beneficiary_id")).isFalse();
        assertPage(transfers, direct.path("id").longValue(), transferId);
        var waiting = get(transfer + "/timeline", 200);
        assertThat(kinds(waiting)).containsExactly("transfer_created", "funds_reserved");
        assertThat(waiting.path("events").get(0).has("journal_id")).isFalse();

        assertThat(settlement.processOne()).isTrue();
        var timeline = get(transfer + "/timeline", 200);
        assertThat(timeline.path("transfer_id").longValue()).isEqualTo(transferId);
        assertThat(kinds(timeline)).containsExactly("transfer_created", "funds_reserved", "bank_confirmed", "transfer_settled");
        var events = timeline.path("events");
        assertThat(events.get(2).path("provider_reference").longValue()).isEqualTo(transferId);
        assertThat(events.get(2).has("journal_id")).isFalse();
        assertThat(Instant.parse(events.get(3).path("occurred_at").asText())).isNotNull();
        assertThat(get("/journals/" + events.get(3).path("journal_id").longValue(), 200).path("entries")).hasSize(2);
        assertThat(post("/bank/transfers", payment.formatted(accountId, beneficiaryId), 200).path("status").asText())
                .isEqualTo("settled");
        assertThat(post("/bank/transfers", payment.formatted(accountId, beneficiaryId).replace("2500", "9000"), 409)
                .has("error")).isTrue();
        assertThat(post("/bank/transfers", payment.formatted(accountId, beneficiaryId)
                .replace("send-api", "overdraft-api").replace("2500", "9000"), 422).path("error").asText())
                .contains("account policy");
    }

    @Test
    void exposesLedgerWritesAndRejectsAmbiguousRequests() throws Exception {
        String ledgerAccount = """
                {"code":"%s","name":"Cash","kind":"asset","currency":"USD","normal_side":"debit","allow_overdraft":true}
                """;
        long from = post("/accounts", ledgerAccount.formatted("api-from"), 200).path("id").longValue();
        long to = post("/accounts", ledgerAccount.formatted("api-to"), 200).path("id").longValue();
        post("/accounts", ledgerAccount.formatted("api-to"), 409);
        assertThat(get("/accounts", 200).isArray()).isTrue();
        assertThat(get("/accounts/" + to, 200).path("normal_side").asText()).isEqualTo("debit");

        String posting = """
                {"idempotency_key":"raw-post-api","entries":[
                  {"account_id":%d,"side":"credit","amount":9223372036854775807},
                  {"account_id":%d,"side":"debit","amount":9223372036854775807}]}
                """.formatted(from, to);
        long journalId = post("/journals", posting, 200).path("journal_id").longValue();
        assertThat(get("/journals/" + journalId, 200).path("entries").get(0).path("amount").longValue())
                .isEqualTo(Long.MAX_VALUE);
        assertThat(post("/journals", posting, 200).path("replayed").booleanValue()).isTrue();
        post("/transfers", """
                {"idempotency_key":"raw-transfer-api","from_account_id":%d,"to_account_id":%d,"amount":1}
                """.formatted(to, from), 200);
        assertThat(get("/accounts/" + to, 200).path("balance").longValue()).isEqualTo(Long.MAX_VALUE - 1);

        for (String invalid : List.of(
                posting.replace("9223372036854775807", "9223372036854775808"),
                posting.replace("9223372036854775807", "1.0"),
                posting.replace("9223372036854775807", "\"1\""),
                posting.replace("\"debit\"", "true"),
                posting.replace("\"debit\"", "\"debit\",\"ſide\":\"credit\""),
                posting.replace("\"debit\"", "\"debit\",\"\\u0073ide\":\"credit\""),
                posting.replace("\"debit\"", "\"debit\",\"unknown\":1"),
                posting + "{}", "null {}", "", " ".repeat(1 << 20) + posting)) {
            assertThat(post("/journals", invalid, 400).path("error").asText()).isEqualTo("invalid request");
        }
        for (String query : List.of("limit=", "limit=0", "limit=101", "limit=1&limit=2", "before_id=0", "before_id=-1")) {
            assertThat(get("/bank/accounts/1/transfers?" + query, 400).path("error").asText()).isEqualTo("invalid request");
        }
        get("/bank/accounts/9223372036854775807/beneficiaries", 404);
        get("/bank/transfers/0/timeline", 400);
        get("/bank/transfers/9223372036854775807/timeline", 404);
    }

    private void assertPage(String path, long firstId, long lastId) throws Exception {
        var page = get(path + "?limit=1", 200);
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("items").get(0).path("id").longValue()).isEqualTo(firstId);
        assertThat(page.path("next_before_id").longValue()).isEqualTo(firstId);
        var last = get(path + "?limit=1&before_id=" + firstId, 200);
        assertThat(last.path("items")).hasSize(1);
        assertThat(last.path("items").get(0).path("id").longValue()).isEqualTo(lastId);
        assertThat(last.path("next_before_id").isNull()).isTrue();
    }

    private static List<String> kinds(JsonNode timeline) {
        return StreamSupport.stream(timeline.path("events").spliterator(), false)
                .map(event -> event.path("kind").asText()).toList();
    }

    private JsonNode get(String path, int status) throws Exception {
        return request(path, null, status);
    }

    private JsonNode post(String path, String body, int status) throws Exception {
        return request(path, body, status);
    }

    private JsonNode request(String path, String body, int status) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (body != null) {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        }
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("HTTP status for %s %s, response %s", request.build().method(), path,
                    response.body()).isEqualTo(status);
            return mapper.readTree(response.body());
        }
    }
}
