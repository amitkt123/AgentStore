package com.agentstore.runner.api;

import com.agentstore.runner.TestBackendConfig;
import com.agentstore.runner.backend.BackendException;
import com.agentstore.runner.backend.FakeBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestBackendConfig.class)
class SandboxControllerTest {

    static final String SECRET = "test-runner-secret";

    @Autowired MockMvc mvc;
    @Autowired FakeBackend backend;

    @AfterEach
    void cleanup() {
        backend.listOwned().forEach(o -> backend.kill(o.sandboxId()));
    }

    static String body(String requestId, String shell) {
        return """
                {"requestId":"%s","imageRef":"fake","command":["sh","-c","%s"],
                 "env":{"TOKEN":"s3cret-value"},"allowOut":["example.com"],"cpu":1,"memoryMb":512,"timeoutSeconds":60}
                """.formatted(requestId, shell);
    }

    String create(String requestId, String shell) throws Exception {
        MvcResult result = mvc.perform(post("/sandboxes").header("X-Runner-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON).content(body(requestId, shell)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sandboxId").isNotEmpty())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.sandboxId");
    }

    @Test
    void missingOrWrongSecretIsUnauthorized() throws Exception {
        mvc.perform(get("/sandboxes/x")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/sandboxes/x").header("X-Runner-Secret", "nope")).andExpect(status().isUnauthorized());
    }

    @Test
    void healthNeedsNoSecret() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void createStatusLogsKillRoundTrip() throws Exception {
        String id = create("req-1", "echo hello; exit 4");

        long until = System.currentTimeMillis() + 10_000;
        String state = "RUNNING";
        while (!state.equals("EXITED") && System.currentTimeMillis() < until) {
            state = com.jayway.jsonpath.JsonPath.read(mvc.perform(get("/sandboxes/" + id).header("X-Runner-Secret", SECRET))
                    .andReturn().getResponse().getContentAsString(), "$.state");
        }
        mvc.perform(get("/sandboxes/" + id).header("X-Runner-Secret", SECRET))
                .andExpect(jsonPath("$.state").value("EXITED"))
                .andExpect(jsonPath("$.exitCode").value(4));

        mvc.perform(get("/sandboxes/" + id + "/logs").param("offset", "0").header("X-Runner-Secret", SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("hello\n"))
                .andExpect(jsonPath("$.nextOffset").value(6))
                .andExpect(jsonPath("$.eof").value(false));
        mvc.perform(get("/sandboxes/" + id + "/logs").param("offset", "6").header("X-Runner-Secret", SECRET))
                .andExpect(jsonPath("$.eof").value(true));

        mvc.perform(delete("/sandboxes/" + id).header("X-Runner-Secret", SECRET)).andExpect(status().isNoContent());
        mvc.perform(delete("/sandboxes/" + id).header("X-Runner-Secret", SECRET)).andExpect(status().isNoContent());
        mvc.perform(get("/sandboxes/" + id).header("X-Runner-Secret", SECRET))
                .andExpect(jsonPath("$.state").value("GONE"));
    }

    @Test
    void sameRequestIdReturnsSameSandbox() throws Exception {
        assertThat(create("req-2", "sleep 30")).isEqualTo(create("req-2", "sleep 30"));
    }

    @Test
    void capacityExhaustedIs429WithCode() throws Exception {
        create("req-3", "sleep 30");
        create("req-4", "sleep 30");
        mvc.perform(post("/sandboxes").header("X-Runner-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON).content(body("req-5", "sleep 30")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("CAPACITY_EXHAUSTED"));
    }

    @Test
    void backendFailureIs502WithoutLeakingEnv() throws Exception {
        backend.failNextCreate(new BackendException("start failed"));
        MvcResult result = mvc.perform(post("/sandboxes").header("X-Runner-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON).content(body("req-6", "true")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("BACKEND_ERROR"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("s3cret-value");
    }

    @Test
    void invalidBodyIs400() throws Exception {
        mvc.perform(post("/sandboxes").header("X-Runner-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
