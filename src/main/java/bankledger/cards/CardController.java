package bankledger.cards;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(path = "/bank/accounts/{accountId}/cards", produces = "application/json",
        headers = "X-Wizardric-Sandbox=cards")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "wizardric.cards.enabled", havingValue = "true")
final class CardController {

    private final CardService cards;

    CardController(CardService cards, ServerProperties server) {
        if (server.getAddress() == null || !server.getAddress().isLoopbackAddress()) {
            throw new IllegalStateException("Column sandbox cards require the API to bind to a loopback address.");
        }
        this.cards = cards;
    }

    @GetMapping
    CardService.Overview overview(@PathVariable long accountId, HttpServletRequest request) {
        checkRequest(accountId, request);
        return cards.overview(accountId);
    }

    @PostMapping("/issue")
    CardService.Overview issue(@PathVariable long accountId, HttpServletRequest request) {
        checkRequest(accountId, request);
        return cards.issue(accountId);
    }

    @PostMapping("/pause")
    CardService.Card pause(@PathVariable long accountId, HttpServletRequest request) {
        checkRequest(accountId, request);
        return cards.setPaused(accountId, true);
    }

    @PostMapping("/resume")
    CardService.Card resume(@PathVariable long accountId, HttpServletRequest request) {
        checkRequest(accountId, request);
        return cards.setPaused(accountId, false);
    }

    @GetMapping("/activity")
    CardService.ActivityPage activity(@PathVariable long accountId, @RequestParam(required = false) String after,
                                      HttpServletRequest request) {
        checkRequest(accountId, request);
        if (after != null && !ColumnClient.validId(after)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return cards.activity(accountId, after);
    }

    @GetMapping("/activity/{transactionId}")
    CardService.Detail detail(@PathVariable long accountId, @PathVariable String transactionId,
                              HttpServletRequest request) {
        checkRequest(accountId, request);
        if (!ColumnClient.validId(transactionId)) {
            throw new CardService.Missing();
        }
        return cards.detail(accountId, transactionId);
    }

    private static void checkRequest(long accountId, HttpServletRequest request) {
        // A custom header also prevents cross-origin forms from calling the command routes.
        boolean localHost = switch (request.getServerName()) {
            case "localhost", "127.0.0.1", "[::1]", "[0:0:0:0:0:0:0:1]" -> true;
            default -> false;
        };
        String origin = request.getHeader("Origin");
        boolean sameOrigin = origin == null || origin.equals("http://" + request.getHeader("Host"))
                || origin.equals("https://" + request.getHeader("Host"));
        if (!localHost || !sameOrigin || accountId <= 0) {
            throw new CardService.Missing();
        }
    }

    @ExceptionHandler(CardService.Missing.class)
    ResponseEntity<Map<String, String>> missing() {
        return error(HttpStatus.NOT_FOUND, "No matching sandbox card resource.");
    }

    @ExceptionHandler(CardService.Conflict.class)
    ResponseEntity<Map<String, String>> conflict(CardService.Conflict failure) {
        return error(HttpStatus.CONFLICT, failure.getMessage());
    }

    @ExceptionHandler(ColumnClient.Failure.class)
    ResponseEntity<Map<String, String>> unavailable(ColumnClient.Failure failure) {
        return error(HttpStatus.BAD_GATEWAY, failure.getMessage());
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<Map<String, String>> databaseUnavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "Could not confirm the local card state. Refresh to check the result.");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
