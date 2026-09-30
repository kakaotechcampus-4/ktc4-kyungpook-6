package com.ktc4.backend.domain.qr.controller;

import com.ktc4.backend.domain.qr.dto.QrTokenResponse;
import com.ktc4.backend.domain.qr.service.QrCredentialService;
import com.ktc4.backend.global.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QR 발급 API 의 HTTP 계약 확인. 발급 로직 자체는 {@code QrCredentialServiceTest} 몫이다.
 */
@WebMvcTest(QrCredentialController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "ADMIN")
@DisplayName("QrCredentialController")
class QrCredentialControllerTest {

    private static final String TYPE_INVALID_REQUEST =
            "https://kakaotechcampus-4.github.io/ktc4-kyungpook-6/errors/invalid-request";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QrCredentialService qrCredentialService;

    @Test
    @DisplayName("경로의 아동 번호로 발급을 요청하고, 200 과 함께 qrPayload 를 내려준다")
    void issuesQrToken() throws Exception {
        when(qrCredentialService.issue(7L)).thenReturn(new QrTokenResponse("v1.token"));

        mockMvc.perform(post("/api/children/7/qr-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qrPayload").value("v1.token"));

        verify(qrCredentialService).issue(7L);
    }

    @ParameterizedTest(name = "[{index}] childId={0}")
    @ValueSource(strings = {"abc", "0", "-1"})
    @DisplayName("아동 번호가 양의 정수가 아니면 서비스를 부르지 않고 400 을 반환한다")
    void rejectsInvalidChildId(String childId) throws Exception {
        mockMvc.perform(post("/api/children/" + childId + "/qr-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(TYPE_INVALID_REQUEST));

        verify(qrCredentialService, never()).issue(anyLong());
    }
}
