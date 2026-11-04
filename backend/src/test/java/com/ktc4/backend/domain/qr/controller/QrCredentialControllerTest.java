package com.ktc4.backend.domain.qr.controller;

import com.ktc4.backend.domain.qr.dto.QrTokenResponse;
import com.ktc4.backend.domain.qr.service.QrCredentialService;
import com.ktc4.backend.global.security.JwtProvider;
import com.ktc4.backend.global.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QR 발급 API 의 HTTP 계약 확인. 발급 로직 자체는 {@code QrCredentialServiceTest} 몫이고, 누가 부를 수 있는지는
 * {@code SecurityConfigTest} 가 본다.
 *
 * <p>아동 토큰은 이 컨텍스트의 실제 {@link JwtProvider} 로 발급한다 — 토큰에서 꺼낸 아동 번호로 발급되는지 보려고.
 */
@WebMvcTest(QrCredentialController.class)
@Import(SecurityConfig.class)
@DisplayName("QrCredentialController")
class QrCredentialControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private QrCredentialService qrCredentialService;

    @Test
    @DisplayName("토큰의 아동 번호로 발급을 요청하고, 200 과 함께 qrPayload 를 내려준다 — 요청으로 아동 번호를 받지 않는다")
    void issuesForTokenHolder() throws Exception {
        when(qrCredentialService.issue(7L)).thenReturn(new QrTokenResponse("v1.token"));

        mockMvc.perform(post("/api/children/me/qr-token")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.issueChild(7L).value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qrPayload").value("v1.token"));

        verify(qrCredentialService).issue(7L);
    }
}
