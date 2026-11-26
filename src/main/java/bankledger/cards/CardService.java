package bankledger.cards;

import static bankledger.cards.ColumnClient.cents;
import static bankledger.cards.ColumnClient.id;
import static bankledger.cards.ColumnClient.invalidResponse;
import static bankledger.cards.ColumnClient.text;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import bankledger.cards.CardStore.Attempt;
import bankledger.cards.CardStore.Mapping;
import com.fasterxml.jackson.databind.JsonNode;

final class CardService {

    private static final String CARDS = "/issuing/cards";
    private static final String TRANSACTIONS = "/issuing/card-transactions";

    record Card(String lastFourDigits, String status) {}

    record Balance(long availableAmount, long holdingAmount) {}

    record Overview(String issuanceState, Card card, Balance balance) {}

    record Activity(String id, String merchant, String type, String createdAt, long heldAmount, long settledAmount) {}

    record ActivityPage(List<Activity> items, String nextCursor) {}

    record Event(String type, String createdAt, String decision, String decisionReason, Long requestedAmount,
                 Long heldAmount, Long settledAmount) {}

    record Detail(Activity transaction, List<Event> events) {}

    static final class Missing extends RuntimeException {}

    static final class Conflict extends RuntimeException {
        Conflict(String message) {
            super(message);
        }
    }

    private final CardStore store;
    private final ColumnClient column;

    CardService(CardStore store, ColumnClient column) {
        this.store = store;
        this.column = column;
    }

    Overview overview(long accountId) {
        var mapping = store.mapping(accountId);
        if (mapping == null) {
            return new Overview("unconfigured", null, null);
        }
        var attempt = store.attempt(accountId);
        return overview(mapping, attempt);
    }

    Overview issue(long accountId) {
        return store.withCardLock(accountId, () -> {
            var mapping = requireMapping(accountId);
            var attempt = store.attempt(accountId);
            if (attempt != null && attempt.cardId() != null) {
                return overview(mapping, attempt);
            }

            if (attempt == null) {
                // Only the caller that commits this claim may send the create request.
                var claim = store.claimIssuance(accountId);
                if (claim == null) {
                    throw new Conflict("The issuance claim already exists. Refresh to check the result.");
                }
                attempt = claim;
                JsonNode created = null;
                try {
                    var response = column.post(CARDS, Map.of("type", "virtual", "status", "active",
                            "card_account_id", mapping.cardAccountId(), "authorized_user_entity_id", mapping.personId(),
                            "description", claim.reference()));
                    verifyCard(response, mapping, claim);
                    created = response;
                } catch (ColumnClient.Failure uncertain) {
                    // The create may have committed. Continue with read-only reconciliation.
                }
                if (created != null) {
                    store.recordIssuance(accountId, claim.reference(), id(created, "id"));
                    return new Overview("issued", card(created), balance(mapping));
                }
            }

            var match = reconcile(mapping, attempt);
            if (match == null) {
                store.recordIssuance(accountId, attempt.reference(), null);
                return new Overview("review", null, balance(mapping));
            }
            store.recordIssuance(accountId, attempt.reference(), id(match, "id"));
            return new Overview("issued", card(match), balance(mapping));
        });
    }

    Card setPaused(long accountId, boolean paused) {
        return store.withCardLock(accountId, () -> {
            var mapping = requireMapping(accountId);
            var issued = requireIssued(accountId);
            String target = paused ? "paused" : "active";
            var current = readCard(mapping, issued);
            if (current.status().equals(target)) {
                return current;
            }
            if (current.status().equals("deactivated")) {
                throw new Conflict("Column has deactivated this card.");
            }
            try {
                var result = column.patch(CARDS + "/" + issued.cardId() + (paused ? "/pause" : "/activate"));
                verifyCard(result, mapping, issued);
                var updated = card(result);
                if (updated.status().equals(target)) {
                    return updated;
                }
            } catch (ColumnClient.Failure uncertain) {
                // A timeout or dropped response can follow a status change.
            }

            // Report the provider's confirmed status, including when it is still unchanged.
            return readCard(mapping, issued);
        });
    }

    ActivityPage activity(long accountId, String cursor) {
        var mapping = requireMapping(accountId);
        var issued = requireIssued(accountId);
        // Column accepts one provider filter. Check the card account and program on each returned row.
        String path = TRANSACTIONS + "?card_id=" + issued.cardId()
                + "&limit=25" + (cursor == null ? "" : "&starting_after=" + cursor);
        var page = column.list(path, "card_transactions");
        var items = new ArrayList<Activity>();
        for (var row : page.rows()) {
            if (!belongsToCard(row, mapping, issued)) {
                throw invalidResponse();
            }
            items.add(activity(row));
        }
        return new ActivityPage(List.copyOf(items), page.nextCursor());
    }

    Detail detail(long accountId, String transactionId) {
        var mapping = requireMapping(accountId);
        var issued = requireIssued(accountId);
        JsonNode result;
        try {
            result = column.get(TRANSACTIONS + "/" + transactionId);
        } catch (ColumnClient.Failure failure) {
            if (failure.notFound()) {
                throw new Missing();
            }
            throw failure;
        }
        // Column's detail endpoint takes only a transaction ID. Verify scope before releasing any fields.
        if (!belongsToCard(result, mapping, issued) || !transactionId.equals(id(result, "id"))) {
            throw new Missing();
        }
        var entries = result.path("events");
        if (!entries.isArray()) {
            throw invalidResponse();
        }
        var events = new ArrayList<Event>();
        for (var event : entries) {
            if (!belongsToCard(event, mapping, issued)) {
                throw invalidResponse();
            }
            var amounts = event.path("amounts");
            var decision = event.path("decisioning_details");
            events.add(new Event(text(event, "event_type"), text(event, "created_at"),
                    decision.path("decision").textValue(), decision.path("decision_reason").textValue(),
                    amount(amounts, "cardholder_requested"), amount(amounts, "cardholder_authorized"),
                    amount(amounts, "cardholder_settled")));
        }
        return new Detail(activity(result), List.copyOf(events));
    }

    private Overview overview(Mapping mapping, Attempt attempt) {
        if (attempt == null) {
            return new Overview("available", null, balance(mapping));
        }
        var card = attempt.cardId() == null ? null : readCard(mapping, attempt);
        return new Overview(attempt.state(), card, balance(mapping));
    }

    private Balance balance(Mapping mapping) {
        var account = column.get("/bank-accounts/" + mapping.bankAccountId());
        if (!mapping.bankAccountId().equals(id(account, "id")) || !"USD".equals(text(account, "currency_code"))) {
            throw invalidResponse();
        }
        var balances = account.path("balances");
        return new Balance(cents(balances, "available_amount"), cents(balances, "holding_amount"));
    }

    private Card readCard(Mapping mapping, Attempt attempt) {
        var result = column.get(CARDS + "/" + attempt.cardId());
        verifyCard(result, mapping, attempt);
        return card(result);
    }

    private JsonNode reconcile(Mapping mapping, Attempt attempt) {
        String path = CARDS + "?card_account_id=" + mapping.cardAccountId() + "&limit=100";
        JsonNode match = null;
        String cursor = null;
        var seenCursors = new HashSet<String>();
        do {
            var page = column.list(path + (cursor == null ? "" : "&starting_after=" + cursor), "cards");
            for (var row : page.rows()) {
                if (!mapping.cardAccountId().equals(id(row, "card_account_id"))) {
                    throw invalidResponse();
                }
                if (attempt.reference().equals(row.path("description").textValue())) {
                    if (match != null) {
                        return null;
                    }
                    match = row;
                }
            }
            cursor = page.nextCursor();
            if (cursor != null && !seenCursors.add(cursor)) {
                throw invalidResponse();
            }
        } while (cursor != null);
        if (match != null) {
            try {
                verifyCard(match, mapping, attempt);
            } catch (ColumnClient.Failure unverified) {
                return null;
            }
        }
        return match;
    }

    private static void verifyCard(JsonNode row, Mapping mapping, Attempt attempt) {
        if (!mapping.cardAccountId().equals(id(row, "card_account_id"))
                || !mapping.programId().equals(id(row, "card_program_id"))
                || !mapping.personId().equals(id(row, "authorized_user_entity_id"))
                || !attempt.reference().equals(text(row, "description"))
                || !"virtual".equals(text(row, "type"))
                || (attempt.cardId() != null && !attempt.cardId().equals(id(row, "id")))) {
            throw invalidResponse();
        }
        id(row, "id");
        card(row);
    }

    private static Card card(JsonNode row) {
        var lastFour = text(row, "last_four_digits");
        var status = text(row, "status");
        if (!lastFour.matches("[0-9]{4}") || !List.of("active", "paused", "deactivated").contains(status)) {
            throw invalidResponse();
        }
        return new Card(lastFour, status);
    }

    private static boolean belongsToCard(JsonNode row, Mapping mapping, Attempt issued) {
        return mapping.cardAccountId().equals(id(row, "card_account_id"))
                && mapping.programId().equals(id(row, "card_program_id")) && issued.cardId().equals(id(row, "card_id"));
    }

    private static Activity activity(JsonNode row) {
        var totals = row.path("total_amounts");
        if (!totals.isObject()) {
            throw invalidResponse();
        }
        var merchant = row.path("card_acceptor_details").path("name").textValue();
        return new Activity(id(row, "id"), merchant == null || merchant.isBlank() ? null : merchant.trim(),
                text(row, "type"), text(row, "created_at"),
                zeroIfAbsent(amount(totals, "cardholder_authorized")),
                zeroIfAbsent(amount(totals, "cardholder_settled")));
    }

    private static Long amount(JsonNode amounts, String field) {
        var value = amounts.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!"USD".equals(text(value, "currency_code"))) {
            throw invalidResponse();
        }
        return cents(value, "amount");
    }

    private static long zeroIfAbsent(Long amount) {
        return amount == null ? 0 : amount;
    }

    private Mapping requireMapping(long accountId) {
        var mapping = store.mapping(accountId);
        if (mapping == null) {
            throw new Missing();
        }
        return mapping;
    }

    private Attempt requireIssued(long accountId) {
        var issued = store.attempt(accountId);
        if (issued == null || issued.cardId() == null) {
            throw new Missing();
        }
        return issued;
    }
}
