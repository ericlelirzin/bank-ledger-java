package bankledger.cards;

import static bankledger.cards.ColumnClient.cents;
import static bankledger.cards.ColumnClient.id;
import static bankledger.cards.ColumnClient.invalidResponse;
import static bankledger.cards.ColumnClient.text;

import java.util.Map;

import bankledger.banking.BankingStore;
import bankledger.cards.CardStore.Mapping;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public final class ColumnProvisioning {

    private static final long DEMO_FUNDING = 100_000;

    private final CardStore store;
    private final BankingStore banking;
    private final ObjectMapper json;

    public ColumnProvisioning(CardStore store, BankingStore banking, ObjectMapper json) {
        this.store = store;
        this.banking = banking;
        this.json = json;
    }

    public String provision(long accountId) {
        return provision(accountId, ColumnClient.sandbox(json));
    }

    String provision(long accountId, ColumnClient column) {
        banking.account(accountId);
        return store.withCardLock(accountId, () -> provisionLocked(accountId, column));
    }

    private String provisionLocked(long accountId, ColumnClient column) {
        try {
            var step = store.provisioningStep(accountId);
            if ("funding".equals(step)) {
                finishFunding(column, accountId, store.fundingMapping(accountId));
                return "Column sandbox card account is ready.";
            }
            if (step != null) {
                return existingProvisioning(step);
            }
            if (column.list("/entities?is_root=true&limit=1", "entities").rows().isEmpty()) {
                throw new IllegalStateException("Column requires a root entity on the sandbox platform. "
                        + "Configure it in the Column Dashboard under Entities > Root Entity, then rerun setup.");
            }
            var reference = store.claimProvisioning(accountId);
            if (reference == null) {
                return existingProvisioning(store.provisioningStep(accountId));
            }
            createResources(column, accountId, reference);
        } catch (ColumnClient.Failure failure) {
            throw new IllegalStateException("Column sandbox provisioning stopped at " + store.provisioningStep(accountId)
                    + ". " + failure.getMessage() + " Review the provider outcome before taking another action.");
        }
        return "Column sandbox card account is ready.";
    }

    private static String existingProvisioning(String step) {
        if ("ready".equals(step)) {
            return "Column sandbox card account is already configured.";
        }
        throw new IllegalStateException("Column sandbox provisioning requires review at " + step
                + ". Check the existing provider resources before taking another action.");
    }

    private void createResources(ColumnClient column, long accountId, String reference) {
        var program = column.post("/simulate/issuing/card-programs", Map.of("type", "debit", "scheme", "visa",
                "jit_funding_enabled", false, "description", reference));
        if (!"debit".equals(text(program, "card_program_type")) || !"visa".equals(text(program, "primary_card_scheme"))
                || !program.path("jit_funding_enabled").isBoolean() || program.path("jit_funding_enabled").booleanValue()
                || !"active".equals(text(program, "status"))) {
            throw invalidResponse();
        }
        String programId = id(program, "id");
        store.recordProvisioning(accountId, "person", new Mapping(programId, null, null, null));

        var person = column.post("/entities/person", Map.of(
                "first_name", "Avery", "last_name", "Demo", "date_of_birth", "1990-01-01", "is_root", false,
                "ssn", "123456789", "email", "avery.demo@example.com",
                "address", Map.of("line_1", "123 Federal Reserve Way", "city", "San Francisco",
                        "state", "CA", "postal_code", "94123", "country_code", "USA")));
        String personId = id(person, "id");
        store.recordProvisioning(accountId, "bank_account", new Mapping(programId, personId, null, null));

        var account = column.post("/bank-accounts", Map.of("entity_id", personId, "currency_code", "USD",
                "description", reference));
        if (!"USD".equals(text(account, "currency_code"))) {
            throw invalidResponse();
        }
        String bankId = id(account, "id");
        var mapping = new Mapping(programId, personId, bankId, null);
        store.recordProvisioning(accountId, "funding", mapping);

        column.post("/simulate/receive-wire", Map.of("destination_account_number_id", id(account, "default_account_number_id"),
                "currency_code", "USD", "amount", DEMO_FUNDING));
        finishFunding(column, accountId, mapping);
    }

    private void finishFunding(ColumnClient column, long accountId, Mapping mapping) {
        confirmFunding(column, mapping.bankAccountId());
        store.recordProvisioning(accountId, "card_account", mapping);

        var cardAccount = column.post("/issuing/card-accounts", Map.of("card_program_id", mapping.programId(),
                "bank_account_id", mapping.bankAccountId()));
        if (!mapping.programId().equals(id(cardAccount, "card_program_id"))
                || !mapping.bankAccountId().equals(id(cardAccount, "bank_account_id"))) {
            throw invalidResponse();
        }
        store.recordProvisioning(accountId, "ready", new Mapping(mapping.programId(), mapping.personId(),
                mapping.bankAccountId(), id(cardAccount, "id")));
    }

    private static void confirmFunding(ColumnClient column, String bankId) {
        // The simulator can acknowledge the wire before the funds become available.
        for (int read = 0; read < 6; read++) {
            if (read > 0) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new ColumnClient.Failure("The funding check was interrupted.");
                }
            }
            var account = column.get("/bank-accounts/" + bankId);
            if (!bankId.equals(id(account, "id")) || !"USD".equals(text(account, "currency_code"))) {
                throw invalidResponse();
            }
            long available = cents(account.path("balances"), "available_amount");
            if (available == DEMO_FUNDING) {
                return;
            }
            if (available != 0) {
                break;
            }
        }
        throw new ColumnClient.Failure("Could not confirm the expected sandbox wire in the available balance.");
    }
}
