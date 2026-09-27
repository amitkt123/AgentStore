package com.agentstore.platform.data;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PiiScannerTest {

    @SafeVarargs
    private static PiiScanner.Result scan(Map<String, Object>... rows) {
        return PiiScanner.scan(List.of(rows).iterator());
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }

    @Test
    void anEmptyFileIsClean() {
        PiiScanner.Result result = PiiScanner.scan(List.<Map<String, Object>>of().iterator());
        assertThat(result.status()).isEqualTo(PiiScanner.Result.CLEAN);
        assertThat(result.findings()).isEmpty();
        assertThat(result.findingCount()).isZero();
    }

    @Test
    void panIsDetectedAndAShortStringIsNot() {
        assertThat(scan(row("col", "ABCDE1234F")).status()).isEqualTo(PiiScanner.Result.FOUND);
        assertThat(scan(row("col", "ABCDE12345")).status()).isEqualTo(PiiScanner.Result.CLEAN);
    }

    @Test
    void gstinIsDetectedButADeliberatelyMalformedEmbeddedPanIsNot() {
        assertThat(scan(row("gstin", "29AAAPL1234C1ZS")).status()).isEqualTo(PiiScanner.Result.FOUND);
        assertThat(scan(row("gstin", "29AAAPL123XC1ZS")).status()).isEqualTo(PiiScanner.Result.CLEAN);
    }

    @Test
    void aadhaarVerhoeffChecksumIsValidatedNotJustShape() {
        assertThat(scan(row("aadhaar", "234123412346")).status()).isEqualTo(PiiScanner.Result.FOUND);
        assertThat(scan(row("aadhaar", "234123412347")).status()).isEqualTo(PiiScanner.Result.CLEAN);
    }

    @Test
    void ifscIsDetectedAndAMissingLiteralZeroIsNot() {
        assertThat(scan(row("ifsc", "HDFC0001234")).status()).isEqualTo(PiiScanner.Result.FOUND);
        assertThat(scan(row("ifsc", "HDFC1001234")).status()).isEqualTo(PiiScanner.Result.CLEAN);
    }

    @Test
    void accountNumberIsGatedOnTheColumnHeaderNotJustTheDigits() {
        assertThat(scan(row("Bank A/C No", "123456789")).status()).isEqualTo(PiiScanner.Result.FOUND);
        assertThat(scan(row("amount", "123456789")).status()).isEqualTo(PiiScanner.Result.CLEAN);
    }

    @Test
    void emailIsDetectedAndPlainTextIsNot() {
        assertThat(scan(row("col", "person@example.com")).status()).isEqualTo(PiiScanner.Result.FOUND);
        assertThat(scan(row("col", "not-an-email")).status()).isEqualTo(PiiScanner.Result.CLEAN);
    }

    @Test
    void indianMobileAndE164AreDetectedAndALeadingOneIsNot() {
        assertThat(scan(row("col", "9876543210")).status()).isEqualTo(PiiScanner.Result.FOUND);
        assertThat(scan(row("col", "+919876543210")).status()).isEqualTo(PiiScanner.Result.FOUND);
        assertThat(scan(row("col", "1234567890")).status()).isEqualTo(PiiScanner.Result.CLEAN);
    }

    @Test
    void findingsAreCappedAtTwoHundredButTheCountKeepsGoing() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            rows.add(row("col", "person" + i + "@example.com"));
        }
        PiiScanner.Result result = PiiScanner.scan(rows.iterator());
        assertThat(result.status()).isEqualTo(PiiScanner.Result.FOUND);
        assertThat(result.findingCount()).isEqualTo(250);
        assertThat(result.findings()).hasSize(200);
    }

    @Test
    void nestedObjectAndArrayValuesAreSkippedNotStringified() {
        assertThat(scan(row("col", Map.of("nested", "person@example.com"))).status()).isEqualTo(PiiScanner.Result.CLEAN);
        assertThat(scan(row("col", List.of("person@example.com"))).status()).isEqualTo(PiiScanner.Result.CLEAN);
    }

    @Test
    void nullCellsAreSkipped() {
        assertThat(scan(row("col", null)).status()).isEqualTo(PiiScanner.Result.CLEAN);
    }
}
