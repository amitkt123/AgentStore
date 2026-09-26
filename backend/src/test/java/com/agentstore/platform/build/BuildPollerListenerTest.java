package com.agentstore.platform.build;

import com.agentstore.platform.build.BuildRunnerClient.RunnerBuild;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Listener contract of the poller, without a database: who is told, and that one bad listener harms nobody. */
class BuildPollerListenerTest {

    TemplateBuildRepository builds = mock(TemplateBuildRepository.class);
    BuildTransitions transitions = mock(BuildTransitions.class);
    FakeBuildRunnerClient runner = new FakeBuildRunnerClient();
    List<BuildFinishedEvent> heard = new ArrayList<>();
    BuildFinishedListener throwing = e -> { throw new IllegalStateException("boom"); };
    BuildFinishedListener recording = heard::add;
    BuildPoller poller;
    TemplateBuild build = new TemplateBuild();
    UUID id = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        ObjectProvider<BuildFinishedListener> listeners = mock(ObjectProvider.class);
        when(listeners.orderedStream()).thenAnswer(i -> Stream.of(throwing, recording));
        poller = new BuildPoller(builds, transitions, runner, listeners);
        build.setId(id);
        build.setStatus(BuildStatus.BUILDING);
        build.setStartedAt(Instant.now());
        build.setAttempts(1);
        when(builds.findByStatusOrderByQueuedAtAsc(BuildStatus.BUILDING)).thenReturn(List.of(build));
    }

    @Test
    void everyListenerHearsAFinishedBuildEvenWhenAnEarlierOneThrows() {
        var event = new BuildFinishedEvent(id, UUID.randomUUID(), "e2b", true, null);
        when(transitions.succeed(id, "fake-image-" + id)).thenReturn(Optional.of(event));

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(heard).containsExactly(event);
    }

    @Test
    void nobodyIsToldWhenTheTransitionDidNothingOrTheRunnerIsUnreachable() {
        when(transitions.succeed(id, "fake-image-" + id)).thenReturn(Optional.empty());
        assertThat(poller.pollOnce()).isZero();

        runner.setUnavailable(true);
        assertThat(poller.pollOnce()).isZero();

        assertThat(heard).isEmpty();
        verify(transitions, never()).fail(any(), anyString(), any());
        verify(transitions, never()).requeue(any());
    }

    @Test
    void aRequeuedBuildIsNotAFinishedBuild() {
        runner.answer(id.toString(), new RunnerBuild(id.toString(), "FAILED", "e2b", null, "RUNNER_RESTARTED", "r"));

        assertThat(poller.pollOnce()).isZero();

        verify(transitions).requeue(eq(id));
        assertThat(heard).isEmpty();
    }
}
