package com.agentstore.platform.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonNormalizerTest {

    @TempDir
    Path dir;

    Normalized run(String json, List<String> lines) throws Exception {
        Path in = dir.resolve("in.json");
        Path out = dir.resolve("out.jsonl");
        Files.writeString(in, json, StandardCharsets.UTF_8);
        Normalized n = JsonNormalizer.normalize(in, out);
        lines.addAll(Files.readAllLines(out));
        return n;
    }

    @Test
    void writesOneObjectPerLineWithTheUnionOfKeysAndExactDecimals() throws Exception {
        List<String> lines = new ArrayList<>();
        Normalized n = run("[{\"a\":1,\"amount\":12.50},{\"b\":\"x\",\"amount\":3}]", lines);

        assertThat(n.rowCount()).isEqualTo(2);
        assertThat(n.columns()).containsExactly("a", "amount", "b");
        assertThat(lines).containsExactly("{\"a\":1,\"amount\":12.50}", "{\"b\":\"x\",\"amount\":3}");
    }

    @Test
    void anEmptyArrayIsAValidFileWithNoRows() throws Exception {
        List<String> lines = new ArrayList<>();
        assertThat(run("[]", lines).rowCount()).isZero();
    }

    @Test
    void rejectsAnythingThatIsNotAnArrayOfObjects() {
        assertThatThrownBy(() -> run("{\"a\":1}", new ArrayList<>())).isInstanceOf(IngestionException.class)
                .hasMessageContaining("array of objects");
        assertThatThrownBy(() -> run("[1,2]", new ArrayList<>())).isInstanceOf(IngestionException.class)
                .hasMessageContaining("Item 1");
        assertThatThrownBy(() -> run("[{\"a\":1},", new ArrayList<>())).isInstanceOf(IngestionException.class);
        assertThatThrownBy(() -> run("[{\"a\":1}] trailing", new ArrayList<>())).isInstanceOf(IngestionException.class);
    }
}
