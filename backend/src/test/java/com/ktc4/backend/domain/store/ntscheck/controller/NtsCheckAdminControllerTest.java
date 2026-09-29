package com.ktc4.backend.domain.store.ntscheck.controller;

import com.ktc4.backend.domain.store.ntscheck.scheduler.NtsCheckScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.ktc4.backend.global.security.SecurityConfig;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code local}/{@code dev} 프로필에서만 뜨는 배치 수동 실행 진입점의 HTTP 계약 확인.
 */
@WebMvcTest(NtsCheckAdminController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "ADMIN")
@ActiveProfiles("local")
class NtsCheckAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NtsCheckScheduler ntsCheckScheduler;

    @Test
    @DisplayName("POST /internal/nts-check/run 은 배치를 즉시 실행한다")
    void runTriggersBatchImmediately() throws Exception {
        mockMvc.perform(post("/internal/nts-check/run"))
                .andExpect(status().isOk());

        verify(ntsCheckScheduler).checkAllStores();
    }
}
