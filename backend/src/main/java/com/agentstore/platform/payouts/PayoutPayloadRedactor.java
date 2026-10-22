package com.agentstore.platform.payouts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

/**
 * Masks a payout webhook body before it is stored in {@code payment_webhook_event.payload}. RazorpayX fund account
 * and validation entities embed the developer's full bank account number and UPI address; anywhere in the JSON tree,
 * an {@code account_number} inside a {@code bank_account} object and an {@code address} inside a {@code vpa} object
 * become "••••" plus their last 4 characters. Everything else is kept. The signature is verified on the raw bytes
 * before this runs.
 */
final class PayoutPayloadRedactor {

    static final String MASK = "••••";
    private static final ObjectMapper JSON = new ObjectMapper();

    private PayoutPayloadRedactor() {}

    /** The redacted body as JSON text; throws {@link PayoutProviderRejectedException} when it is not JSON. */
    static String redact(byte[] body) {
        JsonNode root;
        try {
            root = body == null ? null : JSON.readTree(body);
        } catch (IOException e) {
            throw new PayoutProviderRejectedException("Payout webhook body is not JSON");
        }
        if (root == null) throw new PayoutProviderRejectedException("Payout webhook body is empty");
        walk(root);
        try {
            return JSON.writeValueAsString(root);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write a redacted payout webhook body", e);
        }
    }

    private static void walk(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            mask(object.get("bank_account"), "account_number");
            mask(object.get("vpa"), "address");
            for (Iterator<Map.Entry<String, JsonNode>> it = object.fields(); it.hasNext(); ) {
                walk(it.next().getValue());
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) walk(child);
        }
    }

    private static void mask(JsonNode holder, String field) {
        if (holder == null || !holder.isObject()) return;
        JsonNode value = holder.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) return;
        ((ObjectNode) holder).put(field, masked(value.asText()));
    }

    static String masked(String value) {
        return value.length() <= 4 ? MASK : MASK + value.substring(value.length() - 4);
    }
}
