package com.agentstore.platform.payouts;

import com.agentstore.platform.payments.WebhookProcessor;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Public payout provider callback, the payouts twin of {@code PaymentWebhookController}. Authenticity comes from
 * the signature over the raw bytes, so the body is read as {@code byte[]} and never re-serialised. 200 tells the
 * provider to stop retrying, including for duplicates and events we deliberately ignore.
 */
@RestController
public class PayoutWebhookController implements PayoutApiController {

    private static final Logger log = LoggerFactory.getLogger(PayoutWebhookController.class);
    /** Provider events are a few KB; anything over this is refused before it is read in full or verified. */
    static final int MAX_BODY_BYTES = 1024 * 1024;

    private final PayoutWebhookProcessor processor;

    public PayoutWebhookController(PayoutWebhookProcessor processor) {
        this.processor = processor;
    }

    @PostMapping("/webhooks/payouts/{provider}")
    public ResponseEntity<Map<String, Object>> receive(@PathVariable String provider,
                                                       HttpServletRequest request,
                                                       @RequestHeader HttpHeaders headers) throws IOException {
        if (!processor.providerName().equals(provider)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        byte[] body = request.getContentLengthLong() > MAX_BODY_BYTES ? null : readAtMost(request, MAX_BODY_BYTES);
        if (body == null) {
            log.warn("Rejected {} payout webhook: body over {} bytes", provider, MAX_BODY_BYTES);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("code", "PAYLOAD_TOO_LARGE", "message", "Webhook body is too large"));
        }
        try {
            var outcome = processor.process(provider, body, headers.toSingleValueMap());
            return ResponseEntity.ok(Map.of("outcome", outcome.name()));
        } catch (WebhookProcessor.BadSignatureException e) {
            log.warn("Rejected {} payout webhook: bad signature", provider);
            return ResponseEntity.badRequest().body(Map.of("code", "BAD_SIGNATURE", "message", "Signature did not verify"));
        } catch (PayoutProviderRejectedException e) {
            log.warn("Rejected {} payout webhook: {}", provider, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("code", "MALFORMED_EVENT", "message", "Event could not be read"));
        }
    }

    /** The whole body, or null when it is longer than {@code max} bytes (a chunked body has no Content-Length). */
    private static byte[] readAtMost(HttpServletRequest request, int max) throws IOException {
        try (InputStream in = request.getInputStream()) {
            byte[] read = in.readNBytes(max + 1);
            return read.length > max ? null : read;
        }
    }
}
