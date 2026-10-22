package com.agentstore.platform.payouts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Compares the developer's legal name with the name the bank holds for the account. */
public final class NameMatcher {

    private static final Set<String> TITLES = Set.of("MR", "MRS", "MS", "DR", "SHRI", "SMT", "KUMARI");

    private NameMatcher() {}

    public static boolean matches(String legalName, String registeredName) {
        if (legalName == null || registeredName == null) return false;
        List<String> a = tokens(legalName);
        List<String> b = tokens(registeredName);
        if (a.isEmpty() || a.size() != b.size()) return false;
        return pair(a, b, new boolean[b.size()], 0, false);
    }

    private static boolean pair(List<String> a, List<String> b, boolean[] used, int i, boolean full) {
        if (i == a.size()) return full;
        for (int j = 0; j < b.size(); j++) {
            if (used[j]) continue;
            String x = a.get(i), y = b.get(j);
            boolean equal = x.equals(y);
            boolean initial = !equal && ((x.length() == 1 && y.startsWith(x)) || (y.length() == 1 && x.startsWith(y)));
            if (!equal && !initial) continue;
            used[j] = true;
            if (pair(a, b, used, i + 1, full || (equal && x.length() >= 2))) return true;
            used[j] = false;
        }
        return false;
    }

    private static List<String> tokens(String name) {
        String cleaned = name.toUpperCase().replaceAll("[^\\p{L} ]", " ");
        List<String> out = new ArrayList<>();
        for (String t : Arrays.asList(cleaned.trim().split("\\s+"))) {
            if (!t.isEmpty() && !TITLES.contains(t)) out.add(t);
        }
        return out;
    }
}
