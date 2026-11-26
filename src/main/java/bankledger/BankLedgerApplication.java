package bankledger;

import bankledger.banking.SettlementWorker;
import bankledger.cards.ColumnProvisioning;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BankLedgerApplication {

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "serve" : args[0];
        boolean provision = command.equals("column-provision");
        if (provision ? args.length != 2 || !args[1].matches("[1-9][0-9]{0,18}")
                : args.length > 1 || (!command.equals("serve") && !command.equals("migrate") && !command.equals("worker"))) {
            System.err.println("Usage: java -jar bank-ledger-java.jar [serve|migrate|worker|column-provision ACCOUNT_ID]");
            System.exit(2);
        }

        var application = new SpringApplication(BankLedgerApplication.class);
        if (command.equals("serve")) {
            application.run();
            return;
        }

        application.setWebApplicationType(WebApplicationType.NONE);
        try (var context = application.run()) {
            context.getBean(MigrationRunner.class).apply();
            if (command.equals("worker")) {
                context.getBean(SettlementWorker.class).run();
            } else if (provision) {
                System.out.println(context.getBean(ColumnProvisioning.class).provision(Long.parseLong(args[1])));
            }
        }
    }
}
