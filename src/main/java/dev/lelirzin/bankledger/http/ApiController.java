package dev.lelirzin.bankledger.http;

import static dev.lelirzin.bankledger.ledger.LedgerException.Reason.INVALID_REQUEST;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.lelirzin.bankledger.banking.BankingStore;
import dev.lelirzin.bankledger.ledger.LedgerException;
import dev.lelirzin.bankledger.ledger.LedgerStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(produces = "application/json")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
final class ApiController {

    private static final Pattern INTEGER = Pattern.compile("[+-]?[0-9]+");

    private record OpenAccount(String name) {}

    private record Funding(@JsonProperty("idempotency_key") String key, long amount) {}

    private record Pagination(long beforeId, int limit) {}

    private final BankingStore banking;
    private final LedgerStore ledger;
    private final JdbcTemplate jdbc;
    private final ApiJson json;

    ApiController(BankingStore banking, LedgerStore ledger, JdbcTemplate jdbc, ApiJson json) {
        this.banking = banking;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.json = json;
    }

    @GetMapping("/healthz")
    Map<String, String> health() {
        jdbc.execute("SELECT 1");
        return Map.of("status", "ok");
    }

    @GetMapping("/accounts")
    List<LedgerStore.Account> ledgerAccounts() {
        return ledger.accounts();
    }

    @PostMapping("/accounts")
    LedgerStore.Account createLedgerAccount(InputStream body) {
        var account = json.read(body, LedgerStore.Account.class);
        // Missing or null strings are empty in the reference API's account request.
        return ledger.createAccount(new LedgerStore.Account(account.id(), Objects.toString(account.code(), ""),
                Objects.toString(account.name(), ""), Objects.toString(account.kind(), ""),
                Objects.toString(account.currency(), ""), Objects.toString(account.normalSide(), ""),
                account.allowOverdraft(), account.balance()));
    }

    @GetMapping("/accounts/{id}")
    LedgerStore.Account ledgerAccount(@PathVariable String id) {
        return ledger.account(positiveLong(id));
    }

    @PostMapping("/journals")
    LedgerStore.Result postJournal(InputStream body) {
        return ledger.post(json.read(body, LedgerStore.Posting.class));
    }

    @GetMapping("/journals/{id}")
    LedgerStore.Journal journal(@PathVariable String id) {
        return ledger.journal(positiveLong(id));
    }

    @PostMapping("/transfers")
    LedgerStore.Result ledgerTransfer(InputStream body) {
        return ledger.transfer(json.read(body, LedgerStore.Transfer.class));
    }

    @PostMapping("/bank/accounts")
    BankingStore.Account openBankAccount(InputStream body) {
        return banking.open(json.read(body, OpenAccount.class).name());
    }

    @GetMapping("/bank/accounts/{id}")
    BankingStore.Account bankAccount(@PathVariable String id) {
        return banking.account(positiveLong(id));
    }

    @PostMapping("/bank/accounts/{id}/simulated-funding")
    LedgerStore.Result fund(@PathVariable String id, InputStream body) {
        long accountId = positiveLong(id);
        var funding = json.read(body, Funding.class);
        return banking.fund(accountId, funding.amount(), funding.key());
    }

    @PostMapping("/bank/accounts/{id}/beneficiaries")
    BankingStore.Beneficiary createBeneficiary(@PathVariable String id, InputStream body) {
        return banking.createBeneficiary(positiveLong(id), json.read(body, BankingStore.BeneficiaryRequest.class));
    }

    @GetMapping("/bank/accounts/{id}/beneficiaries")
    BankingStore.Page<BankingStore.Beneficiary> beneficiaries(@PathVariable String id, HttpServletRequest request) {
        long accountId = positiveLong(id);
        var page = pagination(request);
        return banking.beneficiaries(accountId, page.beforeId(), page.limit());
    }

    @GetMapping("/bank/accounts/{id}/transfers")
    BankingStore.Page<BankingStore.Transfer> transfers(@PathVariable String id, HttpServletRequest request) {
        long accountId = positiveLong(id);
        var page = pagination(request);
        return banking.transfers(accountId, page.beforeId(), page.limit());
    }

    @PostMapping("/bank/transfers")
    BankingStore.Transfer createTransfer(InputStream body) {
        return banking.createTransfer(json.read(body, BankingStore.TransferRequest.class));
    }

    @GetMapping("/bank/transfers/{id}")
    BankingStore.Transfer transfer(@PathVariable String id) {
        return banking.transfer(positiveLong(id));
    }

    @GetMapping("/bank/transfers/{id}/timeline")
    BankingStore.Timeline timeline(@PathVariable String id) {
        return banking.timeline(positiveLong(id));
    }

    private static Pagination pagination(HttpServletRequest request) {
        long before = pageParameter(request, "before_id", 0);
        long limit = pageParameter(request, "limit", 50);
        if (limit > 100) {
            throw new LedgerException(INVALID_REQUEST);
        }
        return new Pagination(before, (int) limit);
    }

    private static long pageParameter(HttpServletRequest request, String name, long defaultValue) {
        String[] values = request.getParameterValues(name);
        if (values == null) {
            return defaultValue;
        }
        if (values.length != 1) {
            throw new LedgerException(INVALID_REQUEST);
        }
        return positiveLong(values[0]);
    }

    private static long positiveLong(String value) {
        if (INTEGER.matcher(value).matches()) {
            try {
                long number = Long.parseLong(value);
                if (number > 0) {
                    return number;
                }
            } catch (NumberFormatException failure) {
                throw new LedgerException(INVALID_REQUEST);
            }
        }
        throw new LedgerException(INVALID_REQUEST);
    }
}
