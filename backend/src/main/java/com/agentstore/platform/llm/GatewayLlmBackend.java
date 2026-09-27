package com.agentstore.platform.llm;

import com.agentstore.platform.execution.RunLlmMode;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.manifest.LlmDecl;
import com.agentstore.platform.manifest.LlmMode;
import com.agentstore.platform.runtime.LlmBackend;
import com.agentstore.platform.runtime.RunPrincipal;
import com.agentstore.platform.runtime.RuntimeApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * {@code POST /runtime/v1/llm/chat}. Validates, checks the model, reserves budget, calls the provider through
 * {@link ProviderClient}, settles, and writes the call row and step. No database transaction is held across the
 * provider call: {@link LlmBudget}, {@link LlmCallLog} and {@link RunLlmContextLoader} each use their own short one.
 */
@Service
public class GatewayLlmBackend implements LlmBackend {

    private final LlmRequestNormalizer normalizer;
    private final LlmModelRepository models;
    private final RunLlmContextLoader contexts;
    private final LlmKeyResolver keys;
    private final LlmBudget budget;
    private final ProviderClient providers;
    private final LlmCallLog callLog;
    private final MockResponder mock;
    private final ReplayResponder replay;
    private final LlmRecorder recorder;

    public GatewayLlmBackend(LlmRequestNormalizer normalizer, LlmModelRepository models, RunLlmContextLoader contexts,
                             LlmKeyResolver keys, LlmBudget budget, ProviderClient providers, LlmCallLog callLog,
                             MockResponder mock, ReplayResponder replay, LlmRecorder recorder) {
        this.normalizer = normalizer;
        this.models = models;
        this.contexts = contexts;
        this.keys = keys;
        this.budget = budget;
        this.providers = providers;
        this.callLog = callLog;
        this.mock = mock;
        this.replay = replay;
        this.recorder = recorder;
    }

    @Override
    public Map<String, Object> chat(RunPrincipal principal, Map<String, Object> rawRequest) {
        LlmDecl llm = principal.manifest().llm();
        if (llm == null) {
            throw new RuntimeApiException(HttpStatus.BAD_REQUEST, "LLM_NOT_DECLARED",
                    "This agent's manifest does not declare an llm block");
        }
        if (llm.mode() == LlmMode.BYOK_DIRECT) {
            throw new RuntimeApiException(HttpStatus.BAD_REQUEST, "USE_DIRECT_PROVIDER",
                    "This agent calls its LLM provider directly; /llm/chat is not available for llm.mode byok_direct");
        }
        LlmRequestNormalizer.Normalized normalized = normalizer.normalize(rawRequest);
        LlmModel model = allowedModel(llm, normalized.request().model());
        RunLlmContext context = contexts.load(principal.runId());
        if (context.llmMode() == RunLlmMode.MOCK) {
            return serveMock(principal, context, model, normalized).toMap();
        }
        if (context.llmMode() == RunLlmMode.REPLAY) {
            return replay.serve(principal, context.replayRunId(), model, normalized).toMap();
        }
        return callProvider(principal, llm, context, model, normalized).toMap();
    }

    /** No key, no budget, no provider: a canned answer, recorded as a MOCK call with its canned token counts. */
    private LlmResponse serveMock(RunPrincipal principal, RunLlmContext context, LlmModel model,
                                  LlmRequestNormalizer.Normalized normalized) {
        long started = System.nanoTime();
        LlmResponse response = mock.respond(context.llmMockJson(), normalized.request(), normalized.hash());
        LlmCall call = new LlmCall(UUID.randomUUID(), principal.runId(), model.getId(), LlmSource.MOCK,
                LlmCallStatus.OK, normalized.hash());
        call.setInputTokens(response.inputTokens());
        call.setOutputTokens(response.outputTokens());
        call.setLatencyMs(millisSince(started));
        callLog.record(principal, call);
        return response;
    }

    private LlmModel allowedModel(LlmDecl llm, String modelId) {
        if (!llm.models().contains(modelId)) {
            throw modelNotAllowed(modelId);
        }
        return models.findByIdAndEnabledTrue(modelId).orElseThrow(() -> modelNotAllowed(modelId));
    }

    private static RuntimeApiException modelNotAllowed(String modelId) {
        return new RuntimeApiException(HttpStatus.BAD_REQUEST, "MODEL_NOT_ALLOWED",
                "Model '" + modelId + "' is not available to this agent");
    }

    private LlmResponse callProvider(RunPrincipal principal, LlmDecl llm, RunLlmContext context, LlmModel model,
                                     LlmRequestNormalizer.Normalized normalized) {
        UUID runId = principal.runId();
        LlmRequest request = normalized.request();
        LlmSource source = llm.mode() == LlmMode.PLATFORM ? LlmSource.PLATFORM : LlmSource.BYOK_PROXY;
        long started = System.nanoTime();

        String apiKey;
        try {
            apiKey = keys.resolve(llm.mode(), model.getProvider(), context.developerOrgId());
        } catch (RuntimeApiException e) {
            if (e.status().is5xxServerError()) {
                callLog.record(principal, failed(runId, model, source, normalized.hash(), e.code(), started));
            }
            throw e;
        }

        long reservation = normalized.estimatedInputTokens() + request.maxTokens();
        if (!budget.reserve(runId, reservation, llm.maxTokensPerRun())) {
            LlmCall rejected = new LlmCall(UUID.randomUUID(), runId, model.getId(), source,
                    LlmCallStatus.BUDGET_REJECTED, normalized.hash());
            rejected.setErrorCode("BUDGET_EXCEEDED");
            rejected.setLatencyMs(millisSince(started));
            callLog.record(principal, rejected);
            throw new RuntimeApiException(HttpStatus.PAYMENT_REQUIRED, "BUDGET_EXCEEDED",
                    "This call does not fit the run's LLM token budget of " + llm.maxTokensPerRun() + " tokens");
        }

        LlmResponse response;
        try {
            response = providers.chat(model.getProvider(), request, apiKey);
        } catch (RuntimeApiException e) {
            budget.release(runId, reservation);
            callLog.record(principal, failed(runId, model, source, normalized.hash(), e.code(), started));
            throw e;
        } catch (RuntimeException e) {
            budget.release(runId, reservation);
            callLog.record(principal, failed(runId, model, source, normalized.hash(), "LLM_PROVIDER_ERROR", started));
            throw new RuntimeApiException(HttpStatus.BAD_GATEWAY, "LLM_PROVIDER_ERROR", "The LLM provider call failed");
        }

        budget.settle(runId, reservation, (long) response.inputTokens() + response.outputTokens());
        LlmCall call = new LlmCall(UUID.randomUUID(), runId, model.getId(), source, LlmCallStatus.OK, normalized.hash());
        call.setInputTokens(response.inputTokens());
        call.setOutputTokens(response.outputTokens());
        call.setCost(LlmCost.of(source, model, response.inputTokens(), response.outputTokens()));
        call.setLatencyMs(millisSince(started));
        if (principal.mode() == RunMode.SANDBOX) {
            recorder.attach(call, request, response);   // production, trial and review runs never store prompts
        }
        callLog.record(principal, call);
        return response;
    }

    private static LlmCall failed(UUID runId, LlmModel model, LlmSource source, String hash, String errorCode,
                                  long startedNanos) {
        LlmCall call = new LlmCall(UUID.randomUUID(), runId, model.getId(), source, LlmCallStatus.ERROR, hash);
        call.setErrorCode(errorCode);
        call.setLatencyMs(millisSince(startedNanos));
        return call;
    }

    private static int millisSince(long startedNanos) {
        return (int) ((System.nanoTime() - startedNanos) / 1_000_000);
    }
}
