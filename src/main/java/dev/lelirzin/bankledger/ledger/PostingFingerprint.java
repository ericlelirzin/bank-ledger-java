package dev.lelirzin.bankledger.ledger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.StringJoiner;

import dev.lelirzin.bankledger.ledger.LedgerStore.Posting;
import dev.lelirzin.bankledger.ledger.LedgerStore.Transfer;

final class PostingFingerprint {

    private PostingFingerprint() {}

    // The field order and escaping are part of Go's persisted SHA-256 format.
    static byte[] hash(Posting posting) {
        var entries = new StringJoiner(",", "[", "]");
        for (var entry : posting.entries()) {
            entries.add("{\"account_id\":" + entry.accountId() + ",\"side\":" + quote(entry.side())
                    + ",\"amount\":" + entry.amount() + "}");
        }
        return sha256("{\"Type\":\"journal\",\"Posting\":{\"idempotency_key\":" + quote(posting.key())
                + ",\"description\":" + quote(posting.description()) + ",\"entries\":" + entries + "}}");
    }

    static byte[] hash(Transfer transfer) {
        return sha256("{\"Type\":\"transfer\",\"Transfer\":{\"idempotency_key\":" + quote(transfer.key())
                + ",\"from_account_id\":" + transfer.fromAccountId() + ",\"to_account_id\":" + transfer.toAccountId()
                + ",\"amount\":" + transfer.amount() + "}}");
    }

    private static String quote(String value) {
        var json = new StringBuilder().append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20 || character == '<' || character == '>' || character == '&'
                            || character == '\u2028' || character == '\u2029') {
                        json.append("\\u").append(HexFormat.of().toHexDigits(character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        return json.append('"').toString();
    }

    private static byte[] sha256(String payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
