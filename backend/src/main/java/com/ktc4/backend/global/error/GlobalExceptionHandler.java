package com.ktc4.backend.global.error;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;

/**
 * 모든 에러 응답을 RFC 9457(Problem Details) 규격으로 통일한다.
 *
 * <p>{@code type} 이 에러 종류의 유일한 식별자다(§3.1.1 MUST). 별도 {@code code} 필드는 없다.
 *
 * <p>주입 자리는 세 곳이다 — {@link #handleCustomException}(우리 비즈니스 예외),
 * {@link #handleUnexpected}(예상 못 한 예외), {@link #handleExceptionInternal}
 * (REEH 가 처리하는 Spring MVC 내장 예외 20종). 셋 다 {@link #decorate} 를 거쳐
 * {@code type}/{@code title} 을 함께 채운다 — 둘 중 하나만 채우면 표준상 가장 이상한 상태가 된다
 * (아래 decorate 참고).
 *
 * <p>REEH 가 이미 잡는 예외에 {@code @ExceptionHandler} 를 새로 추가하면 기동이 실패한다
 * ("Ambiguous @ExceptionHandler method mapped"). 다르게 처리하려면 {@code handleXxx} 를
 * {@code @Override} 하라 — {@link #handleExceptionInternal} 이 그 예시다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /**
     * RFC 9457 의 {@code type}/{@code title} 을 함께 채운다.
     *
     * <p>둘을 같이 채워야 하는 이유 — Spring 의 {@code ProblemDetail.getTitle()} 은 title 이
     * {@code null} 일 때 상태코드의 영어 reason phrase 로 폴백하며, 이 폴백은 {@code type} 값과
     * 무관하게 일어난다. {@code setType} 만 하고 {@code setTitle} 을 빠뜨리면 type 은 커스텀 URI인데
     * title 은 여전히 영어인, 표준상 가장 어색한 상태가 된다.
     *
     * <p>{@code detail} 은 명시적으로 비운다 — REEH 의 기본 핸들러들은 {@code ProblemDetail} 을
     * 만들며 "Failed to read request" 같은 영어 문구를 이미 {@code detail} 에 채워 넣는데,
     * 그건 종류 이름(title)이 할 일이지 "이번 건에 대한 구체적 설명"(RFC §3.1.4)이 아니다.
     * 채워도 되는 값이 아니라 지금은 그런 값 자체가 없다 — 비어 있으면 직렬화에서 키가 사라진다.
     *
     * <p>⚠️ {@code CustomException} 이 나중에 건별 상세 정보(예: "가게 5를 찾을 수 없습니다")를
     * 싣게 되면, 그 값이 이 줄의 무조건 {@code setDetail(null)} 에 조용히 지워진다.
     * 그날이 오면 {@code decorate(body, errorCode, detail)} 오버로드를 추가하고
     * {@code handleCustomException} 만 그 오버로드를 쓰게 바꿔라 — 다른 두 경로(예상 못 한 예외,
     * REEH 프레임워크 예외)는 건별 정보가 없으니 계속 무조건 비워야 한다.
     */
    private static ApiProblemDetail decorate(ProblemDetail body, ErrorCode errorCode) {
        ApiProblemDetail problem = new ApiProblemDetail(body);   // ProblemDetail 복사 생성자
        problem.setType(errorCode.getType());
        problem.setTitle(errorCode.getTitle());
        problem.setDetail(null);
        return problem;
    }

    /**
     * 상태코드를 {@link ErrorCode} 로 매핑한다. 매핑되지 않은 상태(예: 406)도 안전하게 떨어진다.
     * 패키지 전용 — 5xx 분기는 REEH 경로로 재현하기 어려워 같은 패키지의 테스트가 직접 호출한다.
     */
    static ErrorCode errorCodeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.INVALID_REQUEST;
        };
    }

    // ── 경로 ① 우리 비즈니스 예외 ───────────────────────────────────────
    @ExceptionHandler(CustomException.class)
    public ApiProblemDetail handleCustomException(CustomException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("CustomException 발생: {}", errorCode, e);
        return decorate(ProblemDetail.forStatus(errorCode.getHttpStatus()), errorCode);
    }

    // ── 경로 ② 예상 못 한 예외 (5주차 멘토 지적, 2주 이월) ──────────────
    @ExceptionHandler(Exception.class)
    public ApiProblemDetail handleUnexpected(Exception e, HttpServletRequest request) {
        // 스택트레이스를 반드시 남긴다 — 이걸 놓치면 Tomcat 이 남기던 ERROR 로그를 잃는다.
        log.error("예상하지 못한 예외 - method={}, uri={}",
                request.getMethod(), request.getRequestURI(), e);
        return decorate(ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR), ErrorCode.INTERNAL_ERROR);
    }

    // ── 경로 ③ REEH 가 처리하는 MVC 내장 예외 20종 ─────────────────────
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        // body 가 null 로 들어오는 경로가 20개 중 14개다. super 가 ProblemDetail 로 만들어준다.
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, status, request);
        if (response == null) {                      // 응답이 이미 커밋된 경우
            return null;
        }

        // 응답 title 은 고정 문구로 나가므로, 원본 예외 정보는 로그에 남긴다.
        log.warn("{} 처리 - status={}, uri={}, 원본={}",
                ex.getClass().getSimpleName(), status.value(),
                request.getDescription(false).replaceFirst("^uri=", ""), ex.getMessage());

        if (response.getBody() instanceof ProblemDetail problem) {
            ApiProblemDetail decorated = decorate(problem, errorCodeFor(status));
            if (ex instanceof MethodArgumentNotValidException validationEx) {
                decorated.setErrors(toFieldErrors(validationEx));   // 필드별 오류
            }
            return new ResponseEntity<>(decorated, response.getHeaders(), response.getStatusCode());
        }
        return response;
    }

    private static List<ApiProblemDetail.FieldError> toFieldErrors(MethodArgumentNotValidException ex) {
        return ex.getBindingResult().getFieldErrors().stream()
                // defaultMessage 는 @Nullable 이다. String.valueOf 를 쓰면 null 이 "null" 문자열로 나간다.
                .map(fe -> new ApiProblemDetail.FieldError(
                        fe.getField(),
                        fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "올바르지 않은 값입니다"))
                .toList();
    }
}
