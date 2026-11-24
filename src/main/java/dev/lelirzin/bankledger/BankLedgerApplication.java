package dev.lelirzin.bankledger;

import dev.lelirzin.bankledger.banking.SettlementWorker;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BankLedgerApplication {

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "serve" : args[0];
        if (args.length > 1 || (!command.equals("serve") && !command.equals("migrate") && !command.equals("worker"))) {
            System.err.println("Usage: java -jar bank-ledger-java.jar [serve|migrate|worker]");
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
            }
        }
    }
}
