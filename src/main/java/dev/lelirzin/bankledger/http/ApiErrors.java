package dev.lelirzin.bankledger.http;

import java.sql.SQLException;
import java.util.Map;

import dev.lelirzin.bankledger.ledger.LedgerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ApiController.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
final class ApiErrors {

    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(LedgerException.class)
    ResponseEntity<Map<String, String>> ledgerFailure(LedgerException failure) {
        var status = switch (failure.reason()) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
        };
        return error(status, failure.getMessage());
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<Map<String, String>> databaseFailure(RuntimeException failure) {
        return switch (sqlState(failure)) {
            case "23505" -> error(HttpStatus.CONFLICT, "account code already exists");
            case "23514", "22003" -> error(HttpStatus.UNPROCESSABLE_ENTITY,
                    "posting violates account policy, balance bounds, or journal constraints");
            case "23502", "22001", "22P02" -> error(HttpStatus.BAD_REQUEST, "invalid account or posting");
            case "40P01", "40001", "55P03", "57014", "25P03" -> error(HttpStatus.SERVICE_UNAVAILABLE,
                    "transaction aborted; retry with the same idempotency key");
            default -> {
                if (failure instanceof TransactionTimedOutException || failure instanceof QueryTimeoutException) {
                    yield error(HttpStatus.GATEWAY_TIMEOUT,
                            "request deadline exceeded; posting outcome may be unknown; "
                                    + "retry the identical request with the same idempotency key");
                }
                LOG.error("Ledger API request failed", failure);
                yield error(HttpStatus.INTERNAL_SERVER_ERROR, "internal error");
            }
        };
    }

    private static String sqlState(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return "";
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
