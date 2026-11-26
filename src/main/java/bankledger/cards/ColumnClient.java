package bankledger.cards;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class ColumnClient {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z0-9_-]{1,128}");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    record Page(List<JsonNode> rows, boolean hasMore) {
        String nextCursor() {
            return hasMore ? id(rows.getLast(), "id") : null;
        }
    }

    static final class Failure extends RuntimeException {
        private final int status;

        Failure(String message) {
            this(message, 0);
        }

        Failure(String message, int status) {
            super(message);
            this.status = status;
        }

        boolean notFound() {
            return status == 404;
        }
    }

    private final HttpClient http;
    private final URI origin;
    private final ObjectMapper json;
    private final String authorization;

    static ColumnClient sandbox(ObjectMapper json) {
        return new ColumnClient(URI.create("https://api.column.com"), System.getenv("column"), json);
    }

    // The alternate origin is used only by the HTTP stub tests.
    ColumnClient(URI origin, String key, ObjectMapper json) {
        if (key == null || !key.matches("test_[!-~]+")) {
            throw new IllegalStateException("Set the lowercase column environment variable to a Column sandbox key.");
        }
        this.origin = origin;
        this.json = json;
        this.authorization = "Basic " + Base64.getEncoder().encodeToString((":" + key).getBytes(StandardCharsets.UTF_8));
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    JsonNode get(String path) {
        return request("GET", path, null);
    }

    JsonNode post(String path, Map<String, ?> body) {
        return request("POST", path, body);
    }

    JsonNode patch(String path) {
        return request("PATCH", path, null);
    }

    Page list(String path, String field) {
        var result = get(path);
        var rows = result.path(field);
        var hasMore = result.path("has_more");
        if (!rows.isArray() || !hasMore.isBoolean() || (hasMore.booleanValue() && rows.isEmpty())) {
            throw invalidResponse();
        }
        var items = new ArrayList<JsonNode>(rows.size());
        rows.forEach(items::add);
        return new Page(List.copyOf(items), hasMore.booleanValue());
    }

    private JsonNode request(String method, String path, Map<String, ?> body) {
        try {
            var request = HttpRequest.newBuilder(origin.resolve(path)).timeout(TIMEOUT)
                    .header("Authorization", authorization).header("Accept", "application/json");
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                // Provider messages and responses may contain account or cardholder data.
                throw new Failure("Column returned HTTP " + response.statusCode() + ".", response.statusCode());
            }
            var result = json.readTree(response.body());
            if (result == null || !result.isObject()) {
                throw invalidResponse();
            }
            return result;
        } catch (IOException failure) {
            throw new Failure("Column did not return a usable response.");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new Failure("The Column request was interrupted.");
        }
    }

    static boolean validId(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    static String id(JsonNode node, String field) {
        var value = text(node, field);
        if (!validId(value)) {
            throw invalidResponse();
        }
        return value;
    }

    static String text(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw invalidResponse();
        }
        return value.textValue();
    }

    static long cents(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalidResponse();
        }
        return value.longValue();
    }

    static Failure invalidResponse() {
        return new Failure("Column returned an unexpected response.");
    }
}
