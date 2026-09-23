package com.agentstore.platform.execution;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class GateStatusResponseTest {

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"CANCELLED", "FAILED", "TIMED_OUT", "EXPIRED"})
    void runsStoppedWithoutApprovalReportRejected(RunStatus status) {
        assertThat(GateStatusResponse.from(status).decision()).isEqualTo("REJECTED");
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"RUNNING", "COMPLETED"})
    void resumedRunsReportApproved(RunStatus status) {
        assertThat(GateStatusResponse.from(status).decision()).isEqualTo("APPROVED");
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"AWAITING_APPROVAL"})
    void waitingRunsReportPending(RunStatus status) {
        assertThat(GateStatusResponse.from(status).decision()).isEqualTo("PENDING");
    }
}
