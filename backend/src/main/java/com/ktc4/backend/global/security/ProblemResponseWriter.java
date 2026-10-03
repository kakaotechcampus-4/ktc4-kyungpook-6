package com.ktc4.backend.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktc4.backend.global.error.ApiProblemDetail;
import com.ktc4.backend.global.error.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 보안 필터 단계에서 거절한 요청에 팀 에러 규격(RFC 9457) 본문을 써 준다.
 *
 * <p>보안 필터는 컨트롤러보다 앞에서 돌아 {@code GlobalExceptionHandler} 를 거치지 않는다. 그래서
 * {@code type}/{@code title} 을 채우는 규칙을 여기서 한 번 더 따른다 — 그 핸들러의 {@code decorate}
 * 규칙이 바뀌면 여기도 같이 고쳐야 한다({@code SecurityConfigTest} 가 두 응답 모양을 대조한다).
 */
@RequiredArgsConstructor
class ProblemResponseWriter {

    private final ObjectMapper objectMapper;

    void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        ApiProblemDetail body = new ApiProblemDetail(ProblemDetail.forStatus(errorCode.getHttpStatus()));
        body.setType(errorCode.getType());
        body.setTitle(errorCode.getTitle());
        body.setDetail(null);

        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
