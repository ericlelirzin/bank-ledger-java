package dev.lelirzin.bankledger;

import dev.lelirzin.bankledger.banking.SettlementWorker;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BankLedgerApplication {

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || (!args[0].equals("migrate") && !args[0].equals("worker"))) {
            System.err.println("Usage: java -jar bank-ledger-java.jar [migrate|worker]");
            System.exit(2);
        }

        try (var context = SpringApplication.run(BankLedgerApplication.class)) {
            context.getBean(MigrationRunner.class).apply();
            if (args[0].equals("worker")) {
                context.getBean(SettlementWorker.class).run();
            }
        }
    }
}
