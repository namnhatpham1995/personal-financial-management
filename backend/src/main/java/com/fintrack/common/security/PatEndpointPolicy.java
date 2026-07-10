package com.fintrack.common.security;

import com.fintrack.apitoken.domain.ApiTokenScope;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Deny-by-default allowlist for PAT-authenticated requests (design.md decision D3).
 * Endpoints are inaccessible to PATs until explicitly added here, so new controllers added
 * later are automatically PAT-safe rather than automatically PAT-exposed. In particular this
 * never allowlists /api/v1/auth/**, /api/v1/tokens/**, any DELETE, or the vault endpoints.
 */
@Component
public class PatEndpointPolicy {

    private record Rule(String method, Pattern pathPattern) {}

    private static final List<Rule> READ_RULES = List.of(
            rule("GET", "/api/v1/accounts.*"),
            rule("GET", "/api/v1/transactions.*"),
            rule("GET", "/api/v1/categories.*"),
            rule("GET", "/api/v1/budgets.*"),
            rule("GET", "/api/v1/recurring-transactions.*"),
            rule("GET", "/api/v1/analytics.*")
    );

    private static final List<Rule> WRITE_RULES = List.of(
            rule("POST", "/api/v1/transactions"),
            rule("POST", "/api/v1/transactions/batch"),
            rule("PUT", "/api/v1/transactions/[^/]+"),
            rule("POST", "/api/v1/accounts"),
            rule("PUT", "/api/v1/accounts/[^/]+"),
            rule("POST", "/api/v1/categories"),
            rule("PUT", "/api/v1/categories/[^/]+"),
            rule("POST", "/api/v1/budgets"),
            rule("PUT", "/api/v1/budgets/[^/]+")
    );

    public boolean isAllowed(String method, String uri, ApiTokenScope scope) {
        if (matches(READ_RULES, method, uri)) {
            return true;
        }
        return scope == ApiTokenScope.WRITE && matches(WRITE_RULES, method, uri);
    }

    private boolean matches(List<Rule> rules, String method, String uri) {
        return rules.stream().anyMatch(r ->
                r.method().equalsIgnoreCase(method) && r.pathPattern().matcher(uri).matches());
    }

    private static Rule rule(String method, String pathRegex) {
        return new Rule(method, Pattern.compile(pathRegex));
    }
}
