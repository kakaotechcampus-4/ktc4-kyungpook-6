package com.ktc4.backend.domain.file.controller;

import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.JwtProvider;
import com.ktc4.backend.global.security.SecurityConfig;
import com.ktc4.backend.domain.file.dto.BizCertExtractionResponse;
import com.ktc4.backend.domain.file.enums.NtsVerification;
import com.ktc4.backend.domain.file.service.BizCertExtractionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사업자등록증 읽기 API 의 진입점 확인. 실제 보안 규칙({@link SecurityConfig})으로 본다 — 가입 전이라 로그인 없이 부를 수
 * 있어야 하고, 열린 것은 POST 와 파일 ID 모양(소문자 UUID)뿐이어야 한다.
 *
 * <p>읽기 구현({@link BizCertExtractionService})은 가짜로 두고 진입점·권한만 본다. 읽기는 {@code BizCertExtractionServiceTest} 가 본다.
 *
 * <p>횟수 제한은 넉넉히 푼다 — 여기서는 진입점·권한만 본다. 횟수 제한은 {@code BizCertReadRateLimitFilterTest} 가 본다.
 */
@WebMvcTest(BizCertExtractionController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {"biz-cert-extraction.rate-limit.max-attempts=1000",
        "biz-cert-extraction.rate-limit.max-attempts-per-day=1000",
        "biz-cert-extraction.rate-limit.max-attempts-per-file=1000"})
@DisplayName("사업자등록증 읽기 API")
class BizCertExtractionControllerTest {

    private static final String FILE_ID = "3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f";
    private static final String URL = "/api/files/" + FILE_ID + "/biz-cert-extraction";

    @Autowired
    private MockMvc mockMvc;

    // 읽기 구현은 가짜로 둔다 — 여기서는 앞의 규칙(권한·횟수)만 본다
    @MockitoBean
    private BizCertExtractionService bizCertExtractionService;

    @Autowired
    private JwtProvider jwtProvider;

    private String bearer(MemberRole role) {
        return "Bearer " + jwtProvider.issue(1L, role).value();
    }

    @Test
    @DisplayName("토큰 없이 부를 수 있고, 읽은 값을 응답 모양 그대로 준다")
    void opensWithoutTokenAndReturnsFields() throws Exception {
        when(bizCertExtractionService.extract(FILE_ID)).thenReturn(new BizCertExtractionResponse("1234567890", "예시분식",
                "홍길동", null, LocalDate.of(2019, 3, 2), null, null, null, NtsVerification.UNVERIFIED));

        mockMvc.perform(post(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bizNo").value("1234567890"))
                .andExpect(jsonPath("$.openDate").value("2019-03-02"))
                .andExpect(jsonPath("$.storeAddress").value(nullValue()))
                .andExpect(jsonPath("$.ntsVerification").value("UNVERIFIED"));
    }

    @Test
    @DisplayName("서비스가 정한 실패를 팀 에러 규격으로 낸다 — 쓸 수 없는 파일 400, 읽지 못함 503")
    void mapsServiceFailures() throws Exception {
        when(bizCertExtractionService.extract(FILE_ID)).thenThrow(new CustomException(ErrorCode.INVALID_FILE_REFERENCE));
        mockMvc.perform(post(URL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(ErrorCode.INVALID_FILE_REFERENCE.getType().toString()));

        doThrow(new CustomException(ErrorCode.BIZ_CERT_UNREADABLE)).when(bizCertExtractionService).extract(FILE_ID);
        mockMvc.perform(post(URL))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value(ErrorCode.BIZ_CERT_UNREADABLE.getType().toString()));
    }

    @Test
    @DisplayName("점주 토큰·가입 상태 확인용 토큰·깨진 토큰을 붙여도 부를 수 있다 — 가입 화면이 무엇을 들고 있든 막을 이유가 없다")
    void opensWithAnyToken() throws Exception {
        List<String> headers = List.of(bearer(MemberRole.OWNER),
                "Bearer " + jwtProvider.issueSignupStatus(3L).value(),
                "Bearer not-a-jwt");
        for (String header : headers) {
            mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, header))
                    .andExpect(status().isOk());
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
