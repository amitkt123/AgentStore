package com.agentstore.platform.llm;

import com.agentstore.platform.llm.StubProviderServer.Reply;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** valid-llm-small-budget.json allows 1,000 tokens per run. A request this small estimates about 36 input tokens. */
class GatewayBudgetTest extends AbstractLlmIntegrationTest {

    @Test
    void aCallThatWouldExceedTheBudgetIsRejectedAfterAnEarlierOneUsedSome() throws Exception {
        var agent = fx.runningAgent("valid-llm-small-budget.json");
        STUB.enqueue(Reply.anthropicText("ok", 30, 20));

        var first = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 950));   // reserves about 986 of 1000
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fx.tokens(agent.runId())).containsExactly(50, 0);

        var second = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 950));  // 50 used + about 986 > 1000

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(second.getBody().get("code")).isEqualTo("BUDGET_EXCEEDED");
        assertThat(STUB.received()).as("the rejected call never reached the provider").hasSize(1);
        assertThat(fx.tokens(agent.runId())).containsExactly(50, 0);
        var calls = llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId());
        assertThat(calls).extracting(LlmCall::getStatus).containsExactly(LlmCallStatus.OK, LlmCallStatus.BUDGET_REJECTED);
        assertThat(calls.get(1).getErrorCode()).isEqualTo("BUDGET_EXCEEDED");
        var steps = llmSteps(agent.runId());
        assertThat(steps).hasSize(2);
        assertThat(steps.get(1).get("status").asText()).isEqualTo("BUDGET_REJECTED");
        assertThat(steps.get(1).get("tokens").asInt()).isZero();
    }

    @Test
    void aCallLargerThanTheWholeBudgetIsRejected() throws Exception {
        var agent = fx.runningAgent("valid-llm-small-budget.json");

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 1000));   // maxTokens alone is the whole budget

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(response.getBody().get("code")).isEqualTo("BUDGET_EXCEEDED");
        assertThat(STUB.received()).isEmpty();
        assertThat(fx.tokens(agent.runId())).containsExactly(0, 0);
        assertThat(llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId()))
                .extracting(LlmCall::getStatus).containsExactly(LlmCallStatus.BUDGET_REJECTED);
    }

    @Test
    void aFailedCallReleasesItsReservation() throws Exception {
        var agent = fx.runningAgent("valid-llm-small-budget.json");
        STUB.always(Reply.status(500, "{\"error\":\"boom\"}"));

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 900));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(fx.tokens(agent.runId())).as("nothing reserved, nothing used").containsExactly(0, 0);
        var calls = llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId());
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).getStatus()).isEqualTo(LlmCallStatus.ERROR);
        assertThat(calls.get(0).getErrorCode()).isEqualTo("LLM_PROVIDER_ERROR");
        assertThat(calls.get(0).getInputTokens()).isZero();
        var steps = llmSteps(agent.runId());
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).get("status").asText()).isEqualTo("ERROR");
        assertThat(steps.get(0).get("errorCode").asText()).isEqualTo("LLM_PROVIDER_ERROR");

        // the released budget is available again
        STUB.reset();
        STUB.enqueue(Reply.anthropicText("ok", 10, 10));
        assertThat(chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 900)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void concurrentCallsCannotOversubscribeTheBudget() throws Exception {
        var agent = fx.runningAgent("valid-llm-small-budget.json");
        // each call reserves about 436 and really uses 400; the provider is slow so calls overlap
        STUB.always(Reply.anthropicText("ok", 200, 200).delayed(400));
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 400)).getStatusCode().value();
            }));
        }
        start.countDown();
        int served = 0;
        int rejected = 0;
        for (Future<Integer> result : results) {
            int status = result.get(60, TimeUnit.SECONDS);
            if (status == 200) {
                served++;
            } else if (status == 402) {
                rejected++;
            }
        }
        pool.shutdown();

        assertThat(served).isEqualTo(2);
        assertThat(rejected).isEqualTo(4);
        assertThat(STUB.received()).hasSize(2);
        assertThat(fx.tokens(agent.runId())).containsExactly(800, 0);
        assertThat(llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId())).extracting(LlmCall::getStatus)
                .containsOnly(LlmCallStatus.OK, LlmCallStatus.BUDGET_REJECTED);
    }
}
