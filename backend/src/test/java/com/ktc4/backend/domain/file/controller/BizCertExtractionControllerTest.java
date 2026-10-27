package com.ktc4.backend.domain.file.controller;

import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.global.security.JwtProvider;
import com.ktc4.backend.global.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Locale;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사업자등록증 읽기 API 의 진입점 확인. 실제 보안 규칙({@link SecurityConfig})으로 본다 — 가입 전이라 로그인 없이 부를 수
 * 있어야 하고, 열린 것은 POST 와 파일 ID 모양(소문자 UUID)뿐이어야 한다.
 *
 * <p>아직 구현 전이라 501 을 돌려준다. 읽기를 구현하면(PROMPT-172) 501 확인은 실제 응답 확인으로 바뀐다.
 */
@WebMvcTest(BizCertExtractionController.class)
@Import(SecurityConfig.class)
@DisplayName("사업자등록증 읽기 API")
class BizCertExtractionControllerTest {

    private static final String FILE_ID = "3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f";
    private static final String URL = "/api/files/" + FILE_ID + "/biz-cert-extraction";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    private String bearer(MemberRole role) {
        return "Bearer " + jwtProvider.issue(1L, role).value();
    }

    @Test
    @DisplayName("토큰 없이 부를 수 있고, 아직 구현 전이라 본문 없이 501 을 준다")
    void opensWithoutTokenAndAnswersNotImplemented() throws Exception {
        mockMvc.perform(post(URL))
                .andExpect(status().isNotImplemented())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("점주 토큰·가입 상태 확인용 토큰·깨진 토큰을 붙여도 부를 수 있다 — 가입 화면이 무엇을 들고 있든 막을 이유가 없다")
    void opensWithAnyToken() throws Exception {
        List<String> headers = List.of(bearer(MemberRole.OWNER),
                "Bearer " + jwtProvider.issueSignupStatus(3L).value(),
                "Bearer not-a-jwt");
        for (String header : headers) {
            mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, header))
                    .andExpect(status().isNotImplemented());
        }
    }

    @Test
    @DisplayName("파일 ID 모양이 아니면 열린 규칙에 걸리지 않는다 — 관리자 전용 규칙으로 떨어져 막힌다")
    void malformedFileIdFallsToAdminOnlyRule() throws Exception {
        String owner = bearer(MemberRole.OWNER);
        List<String> malformed = List.of(
                "/api/files/11/biz-cert-extraction",
                "/api/files/abc/biz-cert-extraction",
                "/api/files/" + FILE_ID.toUpperCase(Locale.ROOT) + "/biz-cert-extraction",
                "/api/files/" + FILE_ID.substring(1) + "/biz-cert-extraction",   // 첫 묶음 7자리
                "/api/files/" + FILE_ID + "0/biz-cert-extraction",               // 마지막 묶음 13자리
                "/api/files/biz-cert-extraction",
                URL + "/",
                URL + "-x",
                URL + "/extra");
        for (String url : malformed) {
            mockMvc.perform(post(url)).andExpect(status().isUnauthorized());
            mockMvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, owner)).andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("열린 것은 POST 뿐이다 — 같은 경로의 GET·PUT·PATCH·DELETE 는 관리자 전용 규칙에 걸린다")
    void onlyPostIsOpen() throws Exception {
        String owner = bearer(MemberRole.OWNER);

        for (var request : List.of(get(URL), put(URL), patch(URL), delete(URL))) {
            mockMvc.perform(request).andExpect(status().isUnauthorized());
        }
        for (var request : List.of(get(URL), put(URL), patch(URL), delete(URL))) {
            mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, owner)).andExpect(status().isForbidden());
        }
    }
}
