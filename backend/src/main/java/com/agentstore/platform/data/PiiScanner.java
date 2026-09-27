package com.agentstore.platform.data;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Regex/checksum detectors run over every scalar cell of a tabular fixture's normalized rows, only for
 * {@code purpose == FIXTURE} uploads (wired in {@link DatasetService#upload}). Pure: takes a row iterator, returns a
 * result, saves nothing. Row numbering matches {@link SchemaChecker} (from 1, no header). Findings are capped at
 * {@link #MAX_FINDINGS} in the returned list, but {@link Result#findingCount()} keeps counting past the cap.
 */
public final class PiiScanner {

    public static final int MAX_FINDINGS = 200;

    public enum Type { PAN, GSTIN, AADHAAR, IFSC, ACCOUNT_NO, EMAIL, PHONE }

    public record Finding(Type type, long row, String column) {
    }

    public record Result(String status, long findingCount, List<Finding> findings) {

        public static final String CLEAN = "CLEAN";
        public static final String FOUND = "FOUND";

        public static Result clean() {
            return new Result(CLEAN, 0, List.of());
        }
    }

    private static final Pattern PAN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
    private static final Pattern GSTIN_SHAPE = Pattern.compile("^[0-9]{2}[A-Z0-9]{13}$");
    private static final Pattern AADHAAR_SHAPE = Pattern.compile("^[0-9]{12}$");
    private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
    private static final Pattern ACCOUNT_NO_VALUE = Pattern.compile("^[0-9]{9,18}$");
    private static final Pattern ACCOUNT_NO_COLUMN = Pattern.compile("account|acct|a/c", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern PHONE_IN = Pattern.compile("^[6-9]\\d{9}$");
    private static final Pattern PHONE_E164 = Pattern.compile("^\\+\\d{10,15}$");

    // Standard Verhoeff multiplication (d) and permutation (p) tables, for the AADHAAR checksum.
    private static final int[][] D = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 2, 3, 4, 0, 6, 7, 8, 9, 5}, {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
            {3, 4, 0, 1, 2, 8, 9, 5, 6, 7}, {4, 0, 1, 2, 3, 9, 5, 6, 7, 8}, {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
            {6, 5, 9, 8, 7, 1, 0, 4, 3, 2}, {7, 6, 5, 9, 8, 2, 1, 0, 4, 3}, {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
            {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}};
    private static final int[][] P = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 5, 7, 6, 2, 8, 3, 0, 9, 4}, {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
            {8, 9, 1, 6, 0, 4, 3, 5, 2, 7}, {9, 4, 5, 3, 1, 2, 6, 8, 7, 0}, {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
            {2, 7, 9, 3, 8, 0, 6, 4, 1, 5}, {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}};

    private PiiScanner() {
    }

    public static Result scan(Iterator<Map<String, Object>> rows) {
        List<Finding> findings = new ArrayList<>();
        long count = 0;
        long n = 0;
        while (rows.hasNext()) {
            n++;
            for (Map.Entry<String, Object> cell : rows.next().entrySet()) {
                Object raw = cell.getValue();
                // A ragged CSV row's overflow cells (CsvNormalizer, Normalized.EXTRA_KEY) are plain scalars from the
                // original row, just parked in a JSON array; scan each one instead of skipping the whole cell.
                if (Normalized.EXTRA_KEY.equals(cell.getKey()) && raw instanceof List<?> extras) {
                    for (Object extra : extras) {
                        if (scanScalar(cell.getKey(), extra, n, findings)) {
                            count++;
                        }
                    }
                    continue;
                }
                if (scanScalar(cell.getKey(), raw, n, findings)) {
                    count++;
                }
            }
        }
        return count == 0 ? Result.clean() : new Result(Result.FOUND, count, List.copyOf(findings));
    }

    /** Null, a nested object, or a nested array (other than the extra-cells one, unwrapped by the caller) is skipped. */
    private static boolean scanScalar(String column, Object raw, long row, List<Finding> findings) {
        if (raw == null || raw instanceof Map || raw instanceof List) {
            return false;
        }
        Type type = detect(column, String.valueOf(raw));
        if (type == null) {
            return false;
        }
        if (findings.size() < MAX_FINDINGS) {
            findings.add(new Finding(type, row, column));
        }
        return true;
    }

    private static Type detect(String column, String value) {
        if (PAN.matcher(value).matches()) {
            return Type.PAN;
        }
        if (GSTIN_SHAPE.matcher(value).matches() && PAN.matcher(value.substring(2, 12)).matches()) {
            return Type.GSTIN;
        }
        if (AADHAAR_SHAPE.matcher(value).matches() && verhoeffValid(value)) {
            return Type.AADHAAR;
        }
        if (IFSC.matcher(value).matches()) {
            return Type.IFSC;
        }
        if (ACCOUNT_NO_VALUE.matcher(value).matches() && ACCOUNT_NO_COLUMN.matcher(column).find()) {
            return Type.ACCOUNT_NO;
        }
        if (EMAIL.matcher(value).matches()) {
            return Type.EMAIL;
        }
        if (PHONE_IN.matcher(value).matches() || PHONE_E164.matcher(value).matches()) {
            return Type.PHONE;
        }
        return null;
    }

    private static boolean verhoeffValid(String digits) {
        int c = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(digits.length() - 1 - i) - '0';
            c = D[c][P[i % 8][digit]];
        }
        return c == 0;
    }
}
