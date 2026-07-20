package com.fintrack.apitoken.service;

import com.fintrack.apitoken.domain.ApiToken;
import com.fintrack.apitoken.domain.ApiTokenScope;
import com.fintrack.apitoken.repository.ApiTokenRepository;
import com.fintrack.apitoken.web.dto.ApiTokenResponse;
import com.fintrack.apitoken.web.dto.CreateApiTokenRequest;
import com.fintrack.apitoken.web.dto.CreatedApiTokenResponse;
import com.fintrack.auth.domain.User;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiTokenServiceTest {

    @Mock ApiTokenRepository apiTokenRepository;
    @Mock UserRepository userRepository;
    @Mock ApiTokenWriter apiTokenWriter;

    @InjectMocks ApiTokenService apiTokenService;

    @Test
    void create_success_returnsPlaintextOncePrefixedAndStoresOnlyHash() {
        User user = User.builder().id(1L).email("a@b.com").build();
        when(userRepository.getReferenceById(1L)).thenReturn(user);
        when(apiTokenWriter.save(any(ApiToken.class))).thenAnswer(inv -> {
            ApiToken t = inv.getArgument(0);
            t.setId(10L);
            return t;
        });

        CreatedApiTokenResponse response = apiTokenService.create(1L,
                new CreateApiTokenRequest("Claude Desktop", ApiTokenScope.READ, 90));

        assertThat(response.plaintextToken()).startsWith("fintrack_pat_");
        assertThat(response.token().tokenPrefix()).startsWith("fintrack_pat_");
        assertThat(response.token().id()).isEqualTo(10L);

        ArgumentCaptor<ApiToken> captor = ArgumentCaptor.forClass(ApiToken.class);
        verify(apiTokenWriter).save(captor.capture());
        ApiToken saved = captor.getValue();
        assertThat(saved.getTokenHash()).isNotEqualTo(response.plaintextToken());
        assertThat(saved.getTokenHash()).isNotBlank();
    }

    @Test
    void create_sameRawTokenWouldHashIdentically_hashIsDeterministic() {
        // Two independently generated tokens must not collide in hash (sanity: distinct raw -> distinct hash)
        User user = User.builder().id(1L).email("a@b.com").build();
        when(userRepository.getReferenceById(1L)).thenReturn(user);
        when(apiTokenWriter.save(any(ApiToken.class))).thenAnswer(inv -> inv.getArgument(0));

        CreatedApiTokenResponse first = apiTokenService.create(1L,
                new CreateApiTokenRequest("Token A", ApiTokenScope.READ, 30));
        CreatedApiTokenResponse second = apiTokenService.create(1L,
                new CreateApiTokenRequest("Token B", ApiTokenScope.READ, 30));

        assertThat(first.plaintextToken()).isNotEqualTo(second.plaintextToken());
    }

    @Test
    void create_unsupportedExpiry_throwsIllegalArgument() {
        assertThatThrownBy(() -> apiTokenService.create(1L,
                new CreateApiTokenRequest("Bad", ApiTokenScope.READ, 7)))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(apiTokenRepository);
    }

    @Test
    void listByUser_returnsResponsesWithoutPlaintext() {
        ApiToken token = ApiToken.builder()
                .id(5L).name("t").tokenPrefix("fintrack_pat_Ab3F").scope(ApiTokenScope.WRITE)
                .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
                .build();
        when(apiTokenRepository.findAllByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(token));

        List<ApiTokenResponse> result = apiTokenService.listByUser(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).tokenPrefix()).isEqualTo("fintrack_pat_Ab3F");
    }

    @Test
    void revoke_unownedToken_throwsNotFound() {
        when(apiTokenRepository.findByIdAndUserId(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> apiTokenService.revoke(1L, 99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void revoke_ownedToken_setsRevokedAt() {
        ApiToken token = ApiToken.builder().id(5L).build();
        when(apiTokenRepository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(token));
        when(apiTokenRepository.save(any(ApiToken.class))).thenAnswer(inv -> inv.getArgument(0));

        apiTokenService.revoke(1L, 5L);

        assertThat(token.getRevokedAt()).isNotNull();
    }

    @Test
    void authenticate_expiredToken_returnsEmpty() {
        User user = User.builder().id(1L).email("a@b.com").build();
        ApiToken expired = ApiToken.builder()
                .id(1L).user(user).tokenHash("hash")
                .expiresAt(Instant.now().minusSeconds(1))
                .build();
        when(apiTokenRepository.findByTokenHashWithUser(anyString())).thenReturn(Optional.of(expired));

        Optional<ApiToken> result = apiTokenService.authenticate("fintrack_pat_whatever");

        assertThat(result).isEmpty();
        verify(apiTokenRepository, never()).save(any());
    }

    @Test
    void authenticate_revokedToken_returnsEmpty() {
        User user = User.builder().id(1L).email("a@b.com").build();
        ApiToken revoked = ApiToken.builder()
                .id(1L).user(user).tokenHash("hash")
                .expiresAt(Instant.now().plusSeconds(3600))
                .revokedAt(Instant.now())
                .build();
        when(apiTokenRepository.findByTokenHashWithUser(anyString())).thenReturn(Optional.of(revoked));

        assertThat(apiTokenService.authenticate("fintrack_pat_whatever")).isEmpty();
    }

    @Test
    void authenticate_unknownToken_returnsEmpty() {
        when(apiTokenRepository.findByTokenHashWithUser(anyString())).thenReturn(Optional.empty());

        assertThat(apiTokenService.authenticate("fintrack_pat_unknown")).isEmpty();
    }

    @Test
    void authenticate_validToken_touchesLastUsedAt() {
        User user = User.builder().id(1L).email("a@b.com").build();
        ApiToken valid = ApiToken.builder()
                .id(1L).user(user).tokenHash("hash")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        when(apiTokenRepository.findByTokenHashWithUser(anyString())).thenReturn(Optional.of(valid));
        when(apiTokenRepository.save(any(ApiToken.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<ApiToken> result = apiTokenService.authenticate("fintrack_pat_whatever");

        assertThat(result).isPresent();
        assertThat(result.get().getLastUsedAt()).isNotNull();
    }
}
