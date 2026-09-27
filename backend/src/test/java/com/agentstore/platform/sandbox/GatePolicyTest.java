package com.agentstore.platform.sandbox;

import com.agentstore.platform.sandbox.GatePolicy.GateMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatePolicyTest {

    @Test
    void anActionEntryOverridesTheDefault() {
        GatePolicy policy = GatePolicy.parse("{\"default\":\"AUTO_APPROVE\",\"actions\":{\"post\":\"AUTO_REJECT\"}}");

        assertThat(policy.modeFor("post")).isEqualTo(GateMode.AUTO_REJECT);
        assertThat(policy.modeFor("other")).isEqualTo(GateMode.AUTO_APPROVE);
    }

    @Test
    void missingOrEmptyPoliciesAreManual() {
        for (String json : new String[]{null, "", "  ", "{}"}) {
            GatePolicy policy = GatePolicy.parse(json);
            assertThat(policy.modeFor("anything")).isEqualTo(GateMode.MANUAL);
        }
    }

    @Test
    void anUnknownModeIsRejected() {
        assertThatThrownBy(() -> GatePolicy.parse("{\"default\":\"NOPE\"}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jsonRoundTrips() {
        GatePolicy policy = new GatePolicy(GateMode.AUTO_REJECT, java.util.Map.of("a", GateMode.AUTO_APPROVE));

        assertThat(GatePolicy.parse(policy.toJson())).isEqualTo(policy);
        assertThat(policy.toJson()).contains("\"default\":\"AUTO_REJECT\"");
    }
}
