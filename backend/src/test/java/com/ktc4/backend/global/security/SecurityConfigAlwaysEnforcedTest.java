package com.ktc4.backend.global.security;

import com.ktc4.backend.domain.store.controller.StoreController;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 권한 검사는 끌 수 없다 — 예전 임시 스위치(PROMPT-146 에서 제거)를 다시 만들지 못하게 고정한다.
 *
 * <p>로그인 기능을 넣을 때 프론트·AI 가 준비되기 전이라 {@code AUTH_ENFORCE=false} 로 권한 검사를 끌 수 있게 했고,
 * 꺼진 동안에는 기존 API 를 로그인 없이 누구나 부를 수 있었다. 그 값을 그대로 넣어 띄워도 이제는 막혀야 한다.
 * 켜진 상태의 규칙 전체는 {@link SecurityConfigTest} 가 맡는다.
 *
 * <p>두 이름을 다 넣는 이유: 예전에는 {@code application.yml} 의 {@code auth.enforce: ${AUTH_ENFORCE:true}} 로
 * 서버 환경변수 {@code AUTH_ENFORCE} 를 읽었다. 설정 키({@code auth.enforce})로 되살리든 환경변수를 읽는 자리로
 * 되살리든 이 테스트가 깨진다.
 */
@WebMvcTest(StoreController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {"auth.enforce=false", "AUTH_ENFORCE=false"})
@DisplayName("API 접근 권한 — 끌 수 없음")
class SecurityConfigAlwaysEnforcedTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StoreService storeService;

    // 보안 필터의 거절 응답이 docs/에러_처리_가이드.md 의 모양(RFC 9457)인지 본다 — SecurityConfigTest 와 같은 확인이다.
    private static void expectProblem(ResultActions result, ErrorCode errorCode) throws Exception {
        result.andExpect(status().is(errorCode.getHttpStatus().value()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(errorCode.getType().toString()))
                .andExpect(jsonPath("$.title").value(errorCode.getTitle()))
                .andExpect(jsonPath("$.status").value(errorCode.getHttpStatus().value()))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    @DisplayName("예전 스위치 값(AUTH_ENFORCE=false)을 넣고 띄워도 토큰 없는 요청은 401 이고 서비스에 닿지 않는다")
    void oldSwitchValueIsIgnored() throws Exception {
        // 스위치가 꺼져 있던 동안 토큰 없이 통과하던 API 들이다
        expectProblem(mockMvc.perform(get("/api/stores")), ErrorCode.UNAUTHORIZED);
        expectProblem(mockMvc.perform(get("/api/stores/nts-checks")), ErrorCode.UNAUTHORIZED);
        expectProblem(mockMvc.perform(patch("/api/stores/1")
                .contentType(MediaType.APPLICATION_JSON).content("{}")), ErrorCode.UNAUTHORIZED);

        verifyNoInteractions(storeService);
    }
}
