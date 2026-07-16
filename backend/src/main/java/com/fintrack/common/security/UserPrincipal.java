package com.fintrack.common.security;

import com.fintrack.auth.domain.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

/**
 * Adapts the domain User into Spring Security's UserDetails contract.
 * Exposes userId so services can scope queries without an extra DB call.
 */
public class UserPrincipal implements UserDetails {

    @Getter
    private final Long userId;
    private final String email;
    private final String passwordHash;
    private final Collection<? extends GrantedAuthority> authorities;
    @Getter
    private final AuthMethod authMethod;
    @Getter
    private final Long apiTokenId;
    /** Set only for AuthMethod.AGENT — the single run this token is scoped to. */
    @Getter
    private final Long agentRunId;

    public UserPrincipal(User user) {
        this(user, AuthMethod.JWT, null);
    }

    /** Used by PatAuthenticationFilter so the audit trail can attribute the action to a specific token. */
    public UserPrincipal(User user, AuthMethod authMethod, Long apiTokenId) {
        this(user, authMethod, apiTokenId, null);
    }

    /** Used by AgentAuthenticationFilter — agentRunId scopes this principal to exactly one run. */
    public UserPrincipal(User user, AuthMethod authMethod, Long apiTokenId, Long agentRunId) {
        this.userId = user.getId();
        this.email = user.getEmail();
        this.passwordHash = user.getPasswordHash();
        this.authorities = user.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority(role.getName()))
                .toList();
        this.authMethod = authMethod;
        this.apiTokenId = apiTokenId;
        this.agentRunId = agentRunId;
    }

    @Override public String getUsername() { return email; }
    @Override public String getPassword() { return passwordHash; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}
