package bankledger.cards;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "wizardric.cards.enabled", havingValue = "true")
class CardConfiguration {

    @Bean
    CardService cardService(CardStore store, ObjectMapper json) {
        return new CardService(store, ColumnClient.sandbox(json));
    }
}
