package com.example.similarproducts.infrastructure.http;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.example.similarproducts.domain.Product;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;

final class UpstreamPayloadDecoder {

    private final ObjectReader reader;

    UpstreamPayloadDecoder(ObjectMapper mapper) {
        reader = mapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES);
    }

    List<String> similarIds(byte[] body) {
        JsonNode root = tree(body);
        if (!root.isArray()) {
            throw UpstreamException.invalid("Similar IDs must be a JSON array");
        }
        var ids = new ArrayList<String>();
        root.forEach(node -> ids.add(id(node)));
        return ids;
    }

    Product product(byte[] body, String requestedId) {
        JsonNode root = tree(body);
        if (!root.isObject()) {
            throw UpstreamException.invalid("Product details must be a JSON object");
        }
        String id = id(root.path("id"));
        if (!requestedId.equals(id)) {
            throw UpstreamException.invalid("Product detail identity differs from the requested ID");
        }
        JsonNode name = root.path("name");
        JsonNode price = root.path("price");
        JsonNode availability = root.path("availability");
        if (!name.isTextual() || name.textValue().isEmpty() || !price.isNumber() || !availability.isBoolean()) {
            throw UpstreamException.invalid("Missing or invalid required product fields");
        }
        return new Product(id, name.textValue(), price.decimalValue(), availability.booleanValue());
    }

    private String id(JsonNode node) {
        if (node.isTextual() && !node.textValue().isEmpty()) {
            return node.textValue();
        }
        if (node.isNumber()) {
            try {
                return node.decimalValue().toBigIntegerExact().toString();
            } catch (ArithmeticException ignored) {
                throw UpstreamException.invalid("Numeric product IDs must be integral");
            }
        }
        throw UpstreamException.invalid("Product IDs must be non-empty strings or integral numbers");
    }

    private JsonNode tree(byte[] body) {
        try {
            JsonNode root = reader.readTree(body);
            if (root == null || root.isMissingNode()) {
                throw UpstreamException.invalid("Empty upstream JSON payload");
            }
            return root;
        } catch (IOException error) {
            throw new UpstreamException(UpstreamException.Kind.INVALID_PAYLOAD, 0,
                    "Malformed upstream JSON payload", error);
        }
    }
}
