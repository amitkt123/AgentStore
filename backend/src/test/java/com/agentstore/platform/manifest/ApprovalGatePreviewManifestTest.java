package com.agentstore.platform.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApprovalGatePreviewManifestTest {

    ObjectMapper mapper = new ObjectMapper();
    ManifestValidator validator = new ManifestValidator();
    ManifestParser parser = new ManifestParser();

    private String withGate(String gateJson) throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree(Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json")));
        root.putArray("approvalGates").add(mapper.readTree(gateJson));
        return mapper.writeValueAsString(root);
    }

    @Test
    void parsesAnOptionalPreviewBlock() throws Exception {
        String json = withGate("{\"trigger\":\"post\",\"description\":\"d\",\"preview\":{\"columns\":[\"date\",\"amount\"],\"totalField\":\"amount\"}}");
        assertThat(validator.validate(json).valid()).isTrue();
        assertThat(parser.parse(json).approvalGates().get(0).preview())
                .isEqualTo(new ApprovalGateDecl.Preview(List.of("date", "amount"), "amount"));
    }

    @Test
    void aGateWithoutPreviewHasNone() throws Exception {
        String json = withGate("{\"trigger\":\"post\",\"description\":\"d\"}");
        assertThat(validator.validate(json).valid()).isTrue();
        assertThat(parser.parse(json).approvalGates().get(0).preview()).isNull();
    }

    @Test
    void aPreviewWhoseColumnsIsNotAnArrayIsRejected() throws Exception {
        String json = withGate("{\"trigger\":\"post\",\"description\":\"d\",\"preview\":{\"columns\":\"date\"}}");
        assertThat(validator.validate(json).valid()).isFalse();
    }
}
