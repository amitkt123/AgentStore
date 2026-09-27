package com.agentstore.platform.llm;

import com.agentstore.platform.llm.ProviderCallException.Kind;
import com.agentstore.platform.manifest.LlmVendor;
import com.agentstore.platform.runtime.RuntimeApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderClientTest {

    static final LlmRequest REQUEST = new LlmRequest("m", null,
            List.of(new LlmRequest.Msg("user", List.of(Map.of("type", "text", "text", "hi")))), List.of(), 100, null);
    static final LlmResponse RESPONSE = new LlmResponse("m", List.of(Map.of("type", "text", "text", "ok")), "end_turn", 10, 5);
    static final Duration BACKOFF = Duration.ofMillis(250);

    /** Plays back a script of outcomes (an LlmResponse or a ProviderCallException); succeeds once the script is empty. */
    static final class FakeProvider implements LlmProvider {
        final LlmVendor vendor;
        final Deque<Object> script = new ArrayDeque<>();
        int calls;

        FakeProvider(LlmVendor vendor) {
            this.vendor = vendor;
        }

        FakeProvider then(Object... outcomes) {
            script.addAll(List.of(outcomes));
            return this;
        }

        @Override
        public LlmVendor vendor() {
            return vendor;
        }

        @Override
        public LlmResponse chat(LlmRequest request, String apiKey) throws ProviderCallException {
            calls++;
            Object next = script.poll();
            if (next instanceof ProviderCallException e) {
                throw e;
            }
            return next == null ? RESPONSE : (LlmResponse) next;
        }
    }

    static ProviderCallException failure(Kind kind, int status) {
        return new ProviderCallException(kind, status, null);
    }

    static final LlmProperties.BreakerSettings SETTINGS = new LlmProperties.BreakerSettings(4, 2, 50f, Duration.ofSeconds(30));
    final List<Duration> sleeps = new ArrayList<>();

    ProviderClient client(LlmProvider... providers) {
        return new ProviderClient(List.of(providers), SETTINGS, BACKOFF, sleeps::add);
    }

    void assertApi(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(RuntimeApiException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.code()).isEqualTo(code);
        });
    }

    @Test
    void aSuccessfulCallIsNotRetried() {
        var anthropic = new FakeProvider(LlmVendor.ANTHROPIC);

        assertThat(client(anthropic).chat(LlmVendor.ANTHROPIC, REQUEST, "k")).isEqualTo(RESPONSE);

        assertThat(anthropic.calls).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void aTransientFailureIsRetriedOnceAfterTheBackoff() {
        for (Kind kind : List.of(Kind.SERVER_ERROR, Kind.RATE_LIMITED, Kind.TIMEOUT, Kind.IO)) {
            sleeps.clear();
            var anthropic = new FakeProvider(LlmVendor.ANTHROPIC).then(failure(kind, 500));

            assertThat(client(anthropic).chat(LlmVendor.ANTHROPIC, REQUEST, "k")).isEqualTo(RESPONSE);

            assertThat(anthropic.calls).as(kind.name()).isEqualTo(2);
            assertThat(sleeps).containsExactly(BACKOFF);
        }
    }

    @Test
    void retryAfterIsHonouredAndCappedAtTwoSeconds() {
        assertThat(ProviderClient.retryWait(new ProviderCallException(Kind.RATE_LIMITED, 429, Duration.ofSeconds(1)), BACKOFF))
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(ProviderClient.retryWait(new ProviderCallException(Kind.RATE_LIMITED, 429, Duration.ofSeconds(30)), BACKOFF))
                .isEqualTo(Duration.ofSeconds(2));
        assertThat(ProviderClient.retryWait(new ProviderCallException(Kind.SERVER_ERROR, 500, null), BACKOFF))
                .isEqualTo(BACKOFF);
        assertThat(ProviderClient.retryWait(new ProviderCallException(Kind.SERVER_ERROR, 500, null), Duration.ofSeconds(9)))
                .isEqualTo(Duration.ofSeconds(2));

        var anthropic = new FakeProvider(LlmVendor.ANTHROPIC)
                .then(new ProviderCallException(Kind.RATE_LIMITED, 429, Duration.ofSeconds(1)));
        client(anthropic).chat(LlmVendor.ANTHROPIC, REQUEST, "k");
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void afterTheRetryEachKindMapsToItsErrorContract() {
        assertApi(() -> client(new FakeProvider(LlmVendor.ANTHROPIC).then(failure(Kind.SERVER_ERROR, 500), failure(Kind.SERVER_ERROR, 502)))
                .chat(LlmVendor.ANTHROPIC, REQUEST, "k"), HttpStatus.BAD_GATEWAY, "LLM_PROVIDER_ERROR");
        assertApi(() -> client(new FakeProvider(LlmVendor.ANTHROPIC).then(failure(Kind.RATE_LIMITED, 429), failure(Kind.RATE_LIMITED, 429)))
                .chat(LlmVendor.ANTHROPIC, REQUEST, "k"), HttpStatus.SERVICE_UNAVAILABLE, "LLM_PROVIDER_UNAVAILABLE");
        assertApi(() -> client(new FakeProvider(LlmVendor.ANTHROPIC).then(failure(Kind.TIMEOUT, 0), failure(Kind.TIMEOUT, 0)))
                .chat(LlmVendor.ANTHROPIC, REQUEST, "k"), HttpStatus.GATEWAY_TIMEOUT, "LLM_PROVIDER_TIMEOUT");
        assertApi(() -> client(new FakeProvider(LlmVendor.ANTHROPIC).then(failure(Kind.IO, 0), failure(Kind.IO, 0)))
                .chat(LlmVendor.ANTHROPIC, REQUEST, "k"), HttpStatus.BAD_GATEWAY, "LLM_PROVIDER_ERROR");
    }

    @Test
    void clientErrorsAndUnparseableRepliesAreNotRetried() {
        var rejected = new FakeProvider(LlmVendor.ANTHROPIC).then(failure(Kind.CLIENT_ERROR, 401));
        assertApi(() -> client(rejected).chat(LlmVendor.ANTHROPIC, REQUEST, "k"), HttpStatus.BAD_GATEWAY, "LLM_PROVIDER_ERROR");
        assertThat(rejected.calls).isEqualTo(1);

        var garbled = new FakeProvider(LlmVendor.ANTHROPIC).then(failure(Kind.BAD_RESPONSE, 200));
        assertApi(() -> client(garbled).chat(LlmVendor.ANTHROPIC, REQUEST, "k"), HttpStatus.BAD_GATEWAY, "LLM_PROVIDER_ERROR");
        assertThat(garbled.calls).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void repeatedTransientFailuresOpenTheBreakerAndLaterCallsFailFast() {
        var anthropic = new FakeProvider(LlmVendor.ANTHROPIC).then(failure(Kind.SERVER_ERROR, 500), failure(Kind.SERVER_ERROR, 500));
        ProviderClient client = client(anthropic);

        assertApi(() -> client.chat(LlmVendor.ANTHROPIC, REQUEST, "k"), HttpStatus.BAD_GATEWAY, "LLM_PROVIDER_ERROR");
        assertThat(anthropic.calls).isEqualTo(2);

        assertApi(() -> client.chat(LlmVendor.ANTHROPIC, REQUEST, "k"), HttpStatus.SERVICE_UNAVAILABLE, "LLM_PROVIDER_UNAVAILABLE");
        assertThat(anthropic.calls).as("an open circuit does not reach the provider").isEqualTo(2);
    }

    @Test
    void theBreakerIsPerProviderAndCanBeReset() {
        var anthropic = new FakeProvider(LlmVendor.ANTHROPIC).then(failure(Kind.SERVER_ERROR, 500), failure(Kind.SERVER_ERROR, 500));
        var openai = new FakeProvider(LlmVendor.OPENAI);
        ProviderClient client = client(anthropic, openai);
        assertApi(() -> client.chat(LlmVendor.ANTHROPIC, REQUEST, "k"), HttpStatus.BAD_GATEWAY, "LLM_PROVIDER_ERROR");

        assertThat(client.chat(LlmVendor.OPENAI, REQUEST, "k")).isEqualTo(RESPONSE);
        assertApi(() -> client.chat(LlmVendor.ANTHROPIC, REQUEST, "k"), HttpStatus.SERVICE_UNAVAILABLE, "LLM_PROVIDER_UNAVAILABLE");

        client.resetBreakers();
        assertThat(client.chat(LlmVendor.ANTHROPIC, REQUEST, "k")).isEqualTo(RESPONSE);
    }

    @Test
    void clientErrorsNeverOpenTheBreaker() {
        var anthropic = new FakeProvider(LlmVendor.ANTHROPIC);
        for (int i = 0; i < 10; i++) {
            anthropic.then(failure(Kind.CLIENT_ERROR, 400));
        }
        ProviderClient client = client(anthropic);

        for (int i = 0; i < 10; i++) {
            assertApi(() -> client.chat(LlmVendor.ANTHROPIC, REQUEST, "k"), HttpStatus.BAD_GATEWAY, "LLM_PROVIDER_ERROR");
        }

        assertThat(client.chat(LlmVendor.ANTHROPIC, REQUEST, "k")).isEqualTo(RESPONSE);
    }

    @Test
    void aVendorWithoutAnAdapterIsUnavailable() {
        assertApi(() -> client(new FakeProvider(LlmVendor.ANTHROPIC)).chat(LlmVendor.OPENAI, REQUEST, "k"),
                HttpStatus.SERVICE_UNAVAILABLE, "LLM_PROVIDER_UNAVAILABLE");
    }
}
