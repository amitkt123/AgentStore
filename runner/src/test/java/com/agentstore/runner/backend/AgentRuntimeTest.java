package com.agentstore.runner.backend;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRuntimeTest {

    @Test
    void mapsIdsToBaseImages() {
        assertThat(AgentRuntime.fromId("python-3.12").baseImage()).isEqualTo("python:3.12-slim");
        assertThat(AgentRuntime.fromId("node-22").baseImage()).isEqualTo("node:22-slim");
    }

    @Test
    void rejectsUnknownRuntimes() {
        assertThatThrownBy(() -> AgentRuntime.fromId("ruby-3")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ruby-3");
    }
}
