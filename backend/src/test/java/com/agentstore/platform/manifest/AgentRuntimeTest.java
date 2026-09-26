package com.agentstore.platform.manifest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRuntimeTest {

    @Test
    void runCommandsStartFromTheAppRoot() {
        assertThat(AgentRuntime.PYTHON_3_12.runCommand("agent/main.py"))
                .isEqualTo(List.of("sh", "-c", "cd /workspace/app && exec python 'agent/main.py'"));
        assertThat(AgentRuntime.NODE_22.runCommand("index.mjs"))
                .isEqualTo(List.of("sh", "-c", "cd /workspace/app && exec node 'index.mjs'"));
    }

    @Test
    void idsAndExtensions() {
        assertThat(AgentRuntime.fromId("node-22")).contains(AgentRuntime.NODE_22);
        assertThat(AgentRuntime.fromId("ruby-3")).isEmpty();
        assertThat(AgentRuntime.fromId(null)).isEmpty();
        assertThat(AgentRuntime.NODE_22.acceptsEntrypoint("a.cjs")).isTrue();
        assertThat(AgentRuntime.PYTHON_3_12.acceptsEntrypoint("a.js")).isFalse();
    }
}
