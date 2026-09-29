package com.agentstore.platform.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** What a buyer sees at an approval gate. payload and preview are parsed JSON, so clients get objects, not strings. */
public record GateDetailResponse(UUID gateId, String action, String summary, Object payload, GateStatus status,
                                 Instant expiresAt, BigDecimal estimatedUnits, BigDecimal costIfApproved,
                                 Object preview, DecidedVia decidedVia, UUID decidedByUserId, Instant decidedAt,
                                 String note) {

    static GateDetailResponse from(GateRequest gate, BigDecimal costIfApproved, ObjectMapper mapper) {
        return new GateDetailResponse(gate.getId(), gate.getAction(), gate.getSummary(), parse(gate.getPayload(), mapper),
                gate.getStatus(), gate.getExpiresAt(), gate.getEstimatedUnits(), costIfApproved,
                parse(gate.getPreview(), mapper), gate.getDecidedVia(), gate.getDecidedByUserId(), gate.getDecidedAt(),
                gate.getDecisionNote());
    }

    private static Object parse(String json, ObjectMapper mapper) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
