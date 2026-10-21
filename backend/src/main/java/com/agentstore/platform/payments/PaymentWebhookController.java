package com.agentstore.platform.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public provider callback. Authenticity comes from the signature over the raw bytes, so the body is read as
 * {@code byte[]} and never re-serialised. 200 tells the provider to stop retrying, including for duplicates and
 * events we deliberately ignore.
 */
@RestController
public class PaymentWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookController.class);

    private final WebhookProcessor processor;

    public PaymentWebhookController(WebhookProcessor processor) {
        this.processor = processor;
    }

    @PostMapping("/webhooks/payments/{provider}")
    public ResponseEntity<Map<String, Object>> receive(@PathVariable String provider,
                                                       @RequestBody(required = false) byte[] body,
                                                       @RequestHeader HttpHeaders headers) {
        if (!processor.providerName().equals(provider)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        try {
            var outcome = processor.process(provider, body == null ? new byte[0] : body, headers.toSingleValueMap());
            return ResponseEntity.ok(Map.of("outcome", outcome.name()));
        } catch (WebhookProcessor.BadSignatureException e) {
            log.warn("Rejected {} webhook: bad signature", provider);
            return ResponseEntity.badRequest().body(Map.of("code", "BAD_SIGNATURE", "message", "Signature did not verify"));
        } catch (PaymentProviderRejectedException e) {
            log.warn("Rejected {} webhook: {}", provider, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("code", "MALFORMED_EVENT", "message", "Event could not be read"));
        }
    }
}
