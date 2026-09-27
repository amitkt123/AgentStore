package com.agentstore.platform.sandbox;

import com.agentstore.platform.identity.Org;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** The five sandbox limits, unverified and verified (spec §5). Every org is unverified until WS7. */
@Component
public class SandboxQuotaProperties {

    public record Limits(long runSeconds, int concurrentRuns, BigDecimal llmSpend, long fixtureBytes, int buildsPerDay) {
    }

    private final Limits unverified;
    private final Limits verified;

    public SandboxQuotaProperties(
            @Value("${agentstore.sandbox.quota.unverified.run-seconds:7200}") long uRunSeconds,
            @Value("${agentstore.sandbox.quota.unverified.concurrent-runs:1}") int uConcurrent,
            @Value("${agentstore.sandbox.quota.unverified.llm-spend:200}") BigDecimal uLlm,
            @Value("${agentstore.sandbox.quota.unverified.fixture-bytes:524288000}") long uFixture,
            @Value("${agentstore.sandbox.quota.unverified.builds-per-day:20}") int uBuilds,
            @Value("${agentstore.sandbox.quota.verified.run-seconds:60000}") long vRunSeconds,
            @Value("${agentstore.sandbox.quota.verified.concurrent-runs:5}") int vConcurrent,
            @Value("${agentstore.sandbox.quota.verified.llm-spend:2000}") BigDecimal vLlm,
            @Value("${agentstore.sandbox.quota.verified.fixture-bytes:5368709120}") long vFixture,
            @Value("${agentstore.sandbox.quota.verified.builds-per-day:100}") int vBuilds) {
        this.unverified = new Limits(uRunSeconds, uConcurrent, uLlm, uFixture, uBuilds);
        this.verified = new Limits(vRunSeconds, vConcurrent, vLlm, vFixture, vBuilds);
    }

    public Limits unverified() {
        return unverified;
    }

    public Limits verified() {
        return verified;
    }

    public Limits forOrg(Org org) {
        return org.getDeveloperVerifiedAt() != null ? verified : unverified;
    }
}
