package com.agentstore.platform.identity;

import org.springframework.util.AntPathMatcher;

import java.util.List;

/**
 * What an API key may call. Deny by default: a new endpoint is unreachable by keys until it is added here, which is
 * a deliberate edit to this one file. Submitting for review is a human decision (WS8), so it is denied explicitly
 * even though it sits under the versions path.
 */
final class ApiKeyScope {

    private record Rule(String method, String pattern) {}

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private static final List<String> DENIED = List.of(
            "/api/v1/agents/*/versions/*/submit",
            "/api/v1/agents/*/versions/*/submit/**");

    private static final List<Rule> ALLOWED = List.of(
            new Rule("POST", "/api/v1/agents"),
            new Rule("GET", "/api/v1/agents/mine"),
            new Rule(null, "/api/v1/agents/*/versions"),
            new Rule(null, "/api/v1/agents/*/versions/**"),
            new Rule(null, "/api/v1/agents/*/bundles/**"),
            new Rule(null, "/api/v1/agents/*/builds/**"),
            new Rule(null, "/api/v1/sandbox/**"),
            new Rule(null, "/api/v1/agents/*/sandbox-profiles"),
            new Rule(null, "/api/v1/agents/*/sandbox-profiles/**"),
            new Rule(null, "/api/v1/sandbox-profiles/**"),
            new Rule(null, "/api/v1/developer/fixtures"),
            new Rule(null, "/api/v1/developer/fixtures/**"),
            new Rule("GET", "/api/v1/developer/quota"));

    private ApiKeyScope() {
    }

    static boolean allows(String method, String path) {
        for (String denied : DENIED) {
            if (MATCHER.match(denied, path)) {
                return false;
            }
        }
        return ALLOWED.stream().anyMatch(rule ->
                (rule.method() == null || rule.method().equalsIgnoreCase(method)) && MATCHER.match(rule.pattern(), path));
    }
}
