package com.ktc4.backend.domain.call.controller;

import com.ktc4.backend.domain.call.service.CallLogService;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.JwtProvider;
import com.ktc4.backend.global.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CallLogController.class)
@Import(SecurityConfig.class)
class CallLogControllerTest {

    private static final String URL = "/api/calls/7/logs";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private CallLogService callLogService;

    private String token(MemberRole role) {
        return "Bearer " + jwtProvider.issue(1L, role).value();
    }

    @Test
    void 원본_JSON을_관리자에게_그대로_전달한다() throws Exception {
        String body = "{\"status\":\"IN_PROGRESS\",\"logs\":[],\"nextCursor\":4}";
        given(callLogService.read(7L, 3)).willReturn(body);

        mockMvc.perform(get(URL).param("after", "3")
                        .header(HttpHeaders.AUTHORIZATION, token(MemberRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(body))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        verify(callLogService).read(7L, 3);
    }

    @Test
    void 기본_커서는_0이다() throws Exception {
        given(callLogService.read(7L, 0)).willReturn("{\"logs\":[],\"nextCursor\":0}");

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, token(MemberRole.ADMIN)))
                .andExpect(status().isOk());

        verify(callLogService).read(7L, 0);
    }

    @Test
    void 로그인과_관리자_권한이_필요하다() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, token(MemberRole.OWNER)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(callLogService);
    }

    @Test
    void 음수_커서를_거절한다() throws Exception {
        mockMvc.perform(get(URL).param("after", "-1")
                        .header(HttpHeaders.AUTHORIZATION, token(MemberRole.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(ErrorCode.INVALID_REQUEST.getType().toString()));
        verifyNoInteractions(callLogService);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"CALL_NOT_FOUND", "CALL_NOT_AVAILABLE", "CALL_API_ERROR"})
    void 조회_실패는_기존_오류_형식으로_응답한다(ErrorCode code) throws Exception {
        given(callLogService.read(7L, 0)).willThrow(new CustomException(code));

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, token(MemberRole.ADMIN)))
                .andExpect(status().is(code.getHttpStatus().value()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(code.getType().toString()));
    }
}
