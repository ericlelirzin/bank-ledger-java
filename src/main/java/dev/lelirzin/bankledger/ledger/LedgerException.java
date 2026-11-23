package dev.lelirzin.bankledger.ledger;

public final class LedgerException extends RuntimeException {

    public enum Reason {
        INVALID_REQUEST("invalid request"),
        NOT_FOUND("resource not found"),
        IDEMPOTENCY_CONFLICT("idempotency key reused with different request");

        private final String message;

        Reason(String message) {
            this.message = message;
        }
    }

    private final Reason reason;

    LedgerException(Reason reason) {
        super(reason.message);
        this.reason = reason;
    }

    LedgerException(Reason reason, String detail) {
        super(reason.message + ": " + detail);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
