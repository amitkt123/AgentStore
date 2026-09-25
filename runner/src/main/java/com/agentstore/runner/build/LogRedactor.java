package com.agentstore.runner.build;

import java.util.List;
import java.util.regex.Pattern;

/** Masks known secret formats before a line reaches a build log (spec §5 "Secrets"). */
public final class LogRedactor {

    public static final String MASK = "[REDACTED]";

    private record Rule(Pattern pattern, String replacement) {}

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"), MASK),
            new Rule(Pattern.compile("(?i)(aws_secret_access_key\\s*[=:]\\s*)\\S+"), "$1" + MASK),
            new Rule(Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}"), MASK),
            new Rule(Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}"), MASK),
            new Rule(Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}"), MASK),
            new Rule(Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"), MASK),
            new Rule(Pattern.compile("(?i)\\b(https?://[^\\s:/@]+):[^\\s@/]+@"), "$1:" + MASK + "@"));

    private LogRedactor() {}

    public static String redact(String line) {
        String out = line;
        for (Rule rule : RULES) {
            out = rule.pattern().matcher(out).replaceAll(rule.replacement());
        }
        return out;
    }
}
