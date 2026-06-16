package com.fintrack.auth.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.auth.service.AuthService;
import com.fintrack.auth.web.dto.TokenResponse;
import com.fintrack.common.config.SecurityConfig;
import com.fintrack.common.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class AuthControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean AuthService authService;

    @Test
    void register_validRequest_returns201WithTokens() throws Exception {
        TokenResponse.UserInfo userInfo = new TokenResponse.UserInfo(1L, "a@b.com", "Alice", "Smith");
        TokenResponse response = new TokenResponse("access", "refresh", "Bearer", 900L, userInfo);

        when(authService.register(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "a@b.com",
                                "password", "password123",
                                "firstName", "Alice",
                                "lastName", "Smith"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value("access"))
                .andExpect(jsonPath("$.user.email").value("a@b.com"));
    }

    @Test
    void register_blankEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "",
                                "password", "password123",
                                "firstName", "A",
                                "lastName", "B"
                        ))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_invalidBody_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
