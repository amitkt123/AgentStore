package com.agentstore.runner.api;

import com.agentstore.runner.TestBackendConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestBackendConfig.class)
class BuildControllerTest {

    static final String SECRET = "test-runner-secret";

    @Autowired MockMvc mvc;

    static String body(String buildId, String sha) {
        return """
                {"buildId":"%s","runtime":"python-3.12","bundleUrl":"http://127.0.0.1:1/bundle.zip",
                 "bundleSha256":"%s","entrypoint":"main.py","logUploadUrl":"http://127.0.0.1:1/log"}
                """.formatted(buildId, sha);
    }

    @Test
    void acceptsABuildIdempotentlyAndReportsItsOutcome() throws Exception {
        String sha = "a".repeat(64);
        mvc.perform(post("/builds").header("X-Runner-Secret", SECRET).contentType(MediaType.APPLICATION_JSON)
                        .content(body("ctl-1", sha)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.buildId").value("ctl-1"))
                .andExpect(jsonPath("$.status").value(anyOf(is("QUEUED"), is("BUILDING"), is("FAILED"))));
        mvc.perform(post("/builds").header("X-Runner-Secret", SECRET).contentType(MediaType.APPLICATION_JSON)
                        .content(body("ctl-1", sha)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.buildId").value("ctl-1"));

        // The bundle URL is unreachable, so the build ends as an invalid bundle.
        String state = "QUEUED";
        long until = System.currentTimeMillis() + 10_000;
        String json = "";
        while (!state.equals("FAILED") && System.currentTimeMillis() < until) {
            json = mvc.perform(get("/builds/ctl-1").header("X-Runner-Secret", SECRET)).andReturn().getResponse()
                    .getContentAsString();
            state = JsonPath.read(json, "$.status");
        }
        mvc.perform(get("/builds/ctl-1").header("X-Runner-Secret", SECRET))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("INVALID_BUNDLE"))
                .andExpect(jsonPath("$.backend").value("fake"));
    }

    @Test
    void anUnknownBuildIsRunnerRestarted() throws Exception {
        mvc.perform(get("/builds/nope").header("X-Runner-Secret", SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("RUNNER_RESTARTED"));
    }

    @Test
    void aBadShaOrBuildIdIs400() throws Exception {
        mvc.perform(post("/builds").header("X-Runner-Secret", SECRET).contentType(MediaType.APPLICATION_JSON)
                        .content(body("ctl-2", "not-a-sha")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/builds").header("X-Runner-Secret", SECRET).contentType(MediaType.APPLICATION_JSON)
                        .content(body("../../etc", "a".repeat(64))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void buildsNeedTheRunnerSecret() throws Exception {
        mvc.perform(get("/builds/ctl-1")).andExpect(status().isUnauthorized());
    }
}
