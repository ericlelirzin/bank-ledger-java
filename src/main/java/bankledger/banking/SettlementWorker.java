package bankledger.banking;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SettlementWorker {

    private static final Logger LOG = LoggerFactory.getLogger(SettlementWorker.class);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private final SettlementStore settlement;

    public SettlementWorker(SettlementStore settlement) {
        this.settlement = settlement;
    }

    public void run() throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            Thread.sleep(POLL_INTERVAL);
            try {
                settlement.processOne();
            } catch (RuntimeException failure) {
                LOG.error("Transfer processing failed", failure);
            }
        }
    }
}
