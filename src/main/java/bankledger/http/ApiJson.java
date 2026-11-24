package bankledger.http;

import static bankledger.ledger.LedgerException.Reason.INVALID_REQUEST;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import bankledger.ledger.LedgerException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
final class ApiJson {

    private static final int MAX_BODY_BYTES = 1 << 20;
    private final ObjectMapper mapper;

    ApiJson(ObjectMapper mapper) {
        this.mapper = mapper.copy();
        this.mapper.setConfig(this.mapper.getDeserializationConfig().without(MapperFeature.ALLOW_COERCION_OF_SCALARS));
        this.mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        this.mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        this.mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(10_000).build());
    }

    <T> T read(InputStream input, Class<T> type) {
        try {
            byte[] bytes = input.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new LedgerException(INVALID_REQUEST);
            }
            try (var parser = mapper.createParser(new String(bytes, StandardCharsets.UTF_8));
                 var tokens = new TokenBuffer(mapper, false)) {
                JsonToken first = parser.nextToken();
                if (first == JsonToken.VALUE_NULL) {
                    if (parser.nextToken() != null) {
                        throw new LedgerException(INVALID_REQUEST);
                    }
                    // Decoding null into a Go request struct leaves its fields at their defaults.
                    return mapper.readValue("{}", type);
                }
                var fields = new ArrayDeque<Set<String>>();
                for (JsonToken token = first; token != null; token = parser.nextToken()) {
                    switch (token) {
                        case START_OBJECT -> {
                            fields.push(new HashSet<>());
                            tokens.copyCurrentEvent(parser);
                        }
                        case END_OBJECT -> {
                            fields.pop();
                            tokens.copyCurrentEvent(parser);
                        }
                        case FIELD_NAME -> {
                            // The Go API accepts case-folded aliases of its ASCII field names.
                            String name = parser.currentName().toLowerCase(Locale.ROOT).replace('\u017f', 's');
                            if (!fields.getFirst().add(name)) {
                                throw new LedgerException(INVALID_REQUEST);
                            }
                            tokens.writeFieldName(name);
                        }
                        default -> tokens.copyCurrentEvent(parser);
                    }
                }
                try (var body = tokens.asParser()) {
                    return mapper.readValue(body, type);
                }
            }
        } catch (IOException failure) {
            throw new LedgerException(INVALID_REQUEST);
        }
    }
}
