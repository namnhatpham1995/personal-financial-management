package com.fintrack.common.security;

import com.fintrack.apitoken.domain.ApiTokenScope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PatEndpointPolicyTest {

    private final PatEndpointPolicy policy = new PatEndpointPolicy();

    @Test
    void readScopedPatCannotCreateOrUpdateSetupResources() {
        assertDenied(ApiTokenScope.READ, "POST", "/api/v1/accounts");
        assertDenied(ApiTokenScope.READ, "PUT", "/api/v1/accounts/123");
        assertDenied(ApiTokenScope.READ, "POST", "/api/v1/categories");
        assertDenied(ApiTokenScope.READ, "PUT", "/api/v1/categories/123");
        assertDenied(ApiTokenScope.READ, "POST", "/api/v1/budgets");
        assertDenied(ApiTokenScope.READ, "PUT", "/api/v1/budgets/123");
    }

    @Test
    void writeScopedPatCanCreateAndUpdateSetupResources() {
        assertAllowed(ApiTokenScope.WRITE, "POST", "/api/v1/accounts");
        assertAllowed(ApiTokenScope.WRITE, "PUT", "/api/v1/accounts/123");
        assertAllowed(ApiTokenScope.WRITE, "POST", "/api/v1/categories");
        assertAllowed(ApiTokenScope.WRITE, "PUT", "/api/v1/categories/123");
        assertAllowed(ApiTokenScope.WRITE, "POST", "/api/v1/budgets");
        assertAllowed(ApiTokenScope.WRITE, "PUT", "/api/v1/budgets/123");
    }

    @Test
    void writeScopedPatCannotDeleteAnySetupResource() {
        assertDenied(ApiTokenScope.WRITE, "DELETE", "/api/v1/accounts/123");
        assertDenied(ApiTokenScope.WRITE, "DELETE", "/api/v1/categories/123");
        assertDenied(ApiTokenScope.WRITE, "DELETE", "/api/v1/budgets/123");
    }

    @Test
    void readScopedPatCanReadHistoricalBudgetPerformance() {
        assertAllowed(ApiTokenScope.READ, "GET", "/api/v1/analytics/budget-history");
    }

    @Test
    void patCannotAccessSensitiveEndpointsRegardlessOfScope() {
        for (ApiTokenScope scope : ApiTokenScope.values()) {
            assertDenied(scope, "POST", "/api/v1/auth/login");
            assertDenied(scope, "GET", "/api/v1/tokens");
            assertDenied(scope, "POST", "/api/v1/tokens");
            assertDenied(scope, "GET", "/api/v1/vault/documents");
            assertDenied(scope, "POST", "/api/v1/vault/documents");
        }
    }

    private void assertAllowed(ApiTokenScope scope, String method, String uri) {
        assertThat(policy.isAllowed(method, uri, scope))
                .as("%s %s should be allowed for %s PATs", method, uri, scope)
                .isTrue();
    }

    private void assertDenied(ApiTokenScope scope, String method, String uri) {
        assertThat(policy.isAllowed(method, uri, scope))
                .as("%s %s should be denied for %s PATs", method, uri, scope)
                .isFalse();
    }
}
