package com.agentstore.platform.manifest;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BundleManifestRulesTest {

    static Manifest manifest(String runtime, String entrypoint) {
        return new Manifest("n", "1.0.0", List.of(), List.of(), null, List.of(), List.of(), runtime, entrypoint);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", delimiter = '|', textBlock = """
            python-3.12 | agent/main.py | ''
            node-22     | src/index.mjs | ''
            NULL        | main.py       | runtime is required
            ruby-3      | main.rb       | runtime must be python-3.12 or node-22
            python-3.12 | NULL          | entrypoint is required
            python-3.12 | ../main.py    | entrypoint must be a relative path
            python-3.12 | /abs/main.py  | entrypoint must be a relative path
            python-3.12 | a//main.py    | entrypoint must be a relative path
            python-3.12 | main.js       | does not match runtime python-3.12
            node-22     | main.py       | does not match runtime node-22
            """)
    void checksRuntimeAndEntrypoint(String runtime, String entrypoint, String expectedError) {
        List<String> errors = BundleManifestRules.check(manifest(runtime, entrypoint));
        if (expectedError.isEmpty()) {
            assertThat(errors).isEmpty();
        } else {
            assertThat(errors).anyMatch(e -> e.contains(expectedError));
        }
    }
}
