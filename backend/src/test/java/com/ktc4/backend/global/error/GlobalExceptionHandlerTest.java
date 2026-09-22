package com.ktc4.backend.global.error;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ktc4.backend.domain.store.controller.StoreController;
import com.ktc4.backend.domain.store.service.StoreService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 에러 응답 규격(RFC 9457) 계약 확인.
 *
 * <p>{@code type} 이 에러 종류의 유일한 식별자다(RFC §3.1.1 MUST). {@code code} 필드는 없다.
 * {@code title} 은 그 종류의 한글 이름이고, Spring 이 기본으로 채우는 영어 status phrase 로
 * 남아 있으면 안 된다 — {@code setType()} 만 하고 {@code setTitle()} 을 빠뜨리면 이 상태가 되므로
 * 여기서 명시적으로 고정한다.
 *
 * <p>로그를 함께 확인하는 이유 — REEH 상속으로 전환하면서 Spring 이 자동으로 남기던
 * WARN 로그가 사라지는 것을 실측으로 확인했다. 그 회귀를 테스트로 고정한다.
 */
@WebMvcTest(StoreController.class)
class GlobalExceptionHandlerTest {

    private static final String STORE_PATH = "/api/stores/1";
    private static final String TYPE_INVALID_REQUEST =
            "https://kakaotechcampus-4.github.io/ktc4-kyungpook-6/errors/invalid-request";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StoreService storeService;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger handlerLogger;

    @BeforeEach
    void attachLogAppender() {
        // Lombok @Slf4j 는 private static final 필드라 Mockito 로 잡을 수 없다. ListAppender 를 쓴다.
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        handlerLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        handlerLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Nested
    @DisplayName("모든 에러가 RFC 9457 한 가지 모양으로 나간다 — type 이 유일한 식별자다")
    class ResponseShape {

        @Test
        @DisplayName("요청 본문 검증 실패 — type·title 이 채워지고 필드별 오류가 실린다")
        void bodyValidationFailure() throws Exception {
            mockMvc.perform(patch(STORE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"\",\"phone\":\"012345678901234567890\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(TYPE_INVALID_REQUEST))
                    .andExpect(jsonPath("$.title").value("요청 값이 올바르지 않습니다"))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.instance").value(STORE_PATH))
                    .andExpect(jsonPath("$.code").doesNotExist())
                    .andExpect(jsonPath("$.errors").isArray())
                    .andExpect(jsonPath("$.errors[*].field").exists())
                    .andExpect(jsonPath("$.errors[*].message").exists())
                    // $.code 는 최상위만 본다 — errors[] 안에 code 가 다시 생겨도 위 단언은 못 잡는다.
                    // 본문 전체 문자열로 한 번 더 막는다 (옛 code 필드가 nested 경로로 되살아나는 것 방지).
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                            .as("errors[] 안에도 code 필드가 있으면 안 된다 — 식별자는 최상위 type 하나뿐이다")
                            .doesNotContain("\"code\""));
        }

        @Test
        @DisplayName("깨진 JSON — 같은 모양이되 errors 도 detail 도 실리지 않는다")
        void malformedJson() throws Exception {
            mockMvc.perform(patch(STORE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(TYPE_INVALID_REQUEST))
                    .andExpect(jsonPath("$.title").value("요청 값이 올바르지 않습니다"))
                    .andExpect(jsonPath("$.errors").doesNotExist())
                    // 이번 건에 대한 구체 정보가 없으므로 detail 은 키 자체가 사라진다 (RFC §3 예제와 동일)
                    .andExpect(jsonPath("$.detail").doesNotExist());
        }

        @Test
        @DisplayName("잘못된 enum 값 — 같은 모양으로 나간다")
        void unknownEnumValue() throws Exception {
            mockMvc.perform(patch(STORE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"영업중\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(TYPE_INVALID_REQUEST));
        }

        @Test
        @DisplayName("쿼리 파라미터 검증 실패 — 같은 모양으로 나간다")
        void queryParameterValidationFailure() throws Exception {
            mockMvc.perform(get("/api/stores").param("page", "-1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(TYPE_INVALID_REQUEST))
                    .andExpect(jsonPath("$.title").value("요청 값이 올바르지 않습니다"));
        }

        @Test
        @DisplayName("경로변수 타입 불일치 — 같은 모양으로 나간다")
        void pathVariableTypeMismatch() throws Exception {
            mockMvc.perform(patch("/api/stores/abc")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"맛나 치킨\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(TYPE_INVALID_REQUEST));
        }

        @Test
        @DisplayName("없는 경로 — 404 와 전용 type 이 나간다")
        void notFound() throws Exception {
            mockMvc.perform(get("/api/존재하지-않는-경로"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value(
                            "https://kakaotechcampus-4.github.io/ktc4-kyungpook-6/errors/not-found"))
                    .andExpect(jsonPath("$.title").value("요청한 경로를 찾을 수 없습니다"));
        }

        @Test
        @DisplayName("지원하지 않는 HTTP 메서드 — 405 와 전용 type 이 나간다")
        void methodNotAllowed() throws Exception {
            mockMvc.perform(delete("/api/stores"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.type").value(
                            "https://kakaotechcampus-4.github.io/ktc4-kyungpook-6/errors/method-not-allowed"))
                    .andExpect(jsonPath("$.title").value("지원하지 않는 요청 방식입니다"));
        }

        @Test
        @DisplayName("Content-Type 누락 — 415 와 전용 type 이 나간다")
        void unsupportedMediaType() throws Exception {
            mockMvc.perform(patch(STORE_PATH).content("{\"name\":\"맛나 치킨\"}"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.type").value(
                            "https://kakaotechcampus-4.github.io/ktc4-kyungpook-6/errors/unsupported-media-type"));
        }

        @Test
        @DisplayName("title 이 Spring 기본 status phrase 로 남지 않는다 — setType 만 하고 setTitle 을 빠뜨리는 함정")
        void titleIsNeverTheDefaultStatusPhrase() throws Exception {
            // Spring 의 ProblemDetail.getTitle() 은 title 이 null 이면 상태코드의 영어 reason phrase 로
            // 폴백한다(HttpStatus.METHOD_NOT_ALLOWED.getReasonPhrase() == "Method Not Allowed").
            // type 을 커스텀 URI 로 채웠다고 이 폴백이 자동으로 안 걸리는 게 아니다 — setTitle 을 직접 불러야 한다.
            mockMvc.perform(delete("/api/stores"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                            .as("영어 status phrase 가 남아있으면 setTitle 을 빠뜨린 것이다")
                            .doesNotContain(HttpStatus.METHOD_NOT_ALLOWED.getReasonPhrase()));
        }

        @Test
        @DisplayName("응답 Content-Type 은 application/problem+json 이다 — 가이드가 계약이라 적은 값")
        void contentTypeIsProblemJson() throws Exception {
            mockMvc.perform(patch(STORE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
    }

    @Nested
    @DisplayName("우리 예외도 같은 모양으로 나간다")
    class OurExceptions {

        @Test
        @DisplayName("CustomException — type·title 이 ErrorCode 를 따르고 instance 가 채워진다")
        void customException() throws Exception {
            given(storeService.getStores(anyInt(), anyInt()))
                    .willThrow(new CustomException(ErrorCode.NTS_API_ERROR));

            mockMvc.perform(get("/api/stores"))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.status").value(502))
                    .andExpect(jsonPath("$.type").value(
                            "https://kakaotechcampus-4.github.io/ktc4-kyungpook-6/errors/nts-api-error"))
                    .andExpect(jsonPath("$.title").value("국세청 API 호출에 실패했습니다"))
                    .andExpect(jsonPath("$.code").doesNotExist())
                    // REEH 를 거치지 않는 경로에서도 instance 가 채워지는지 — 이전 계획서의 미확인 항목
                    .andExpect(jsonPath("$.instance").value("/api/stores"));
        }

        @Test
        @DisplayName("예상 못 한 예외 — 500 으로 나가되 내부 메시지는 응답에 노출하지 않는다")
        void unexpectedException() throws Exception {
            given(storeService.getStores(anyInt(), anyInt()))
                    .willThrow(new RuntimeException("DB 커넥션 풀 고갈 - 내부 정보"));

            mockMvc.perform(get("/api/stores"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.status").value(500))
                    .andExpect(jsonPath("$.type").value(
                            "https://kakaotechcampus-4.github.io/ktc4-kyungpook-6/errors/internal-error"))
                    .andExpect(jsonPath("$.title").value("서버 오류가 발생했습니다"))
                    // detail 만 보면 다른 필드로 새는 것을 놓친다. 본문 전체를 확인한다.
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                            .doesNotContain("DB 커넥션 풀 고갈"));
        }

        @Test
        @DisplayName("CustomException 도 WARN 으로 남는다 — 주입 자리 세 곳이 모두 로그를 남겨야 한다")
        void customExceptionIsLogged() throws Exception {
            given(storeService.getStores(anyInt(), anyInt()))
                    .willThrow(new CustomException(ErrorCode.BIZNO_API_ERROR));

            mockMvc.perform(get("/api/stores")).andExpect(status().isBadGateway());

            assertThat(logAppender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("BIZNO_API_ERROR");
            });
        }

        @Test
        @DisplayName("예상 못 한 예외는 스택트레이스와 함께 ERROR 로 남는다")
        void unexpectedExceptionIsLoggedWithStackTrace() throws Exception {
            given(storeService.getStores(anyInt(), anyInt()))
                    .willThrow(new RuntimeException("DB 커넥션 풀 고갈 - 내부 정보"));

            mockMvc.perform(get("/api/stores")).andExpect(status().isInternalServerError());

            assertThat(logAppender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getThrowableProxy()).isNotNull();
                assertThat(event.getFormattedMessage()).contains("/api/stores");
            });
        }
    }

    @Nested
    @DisplayName("상태코드 → ErrorCode 매핑")
    class StatusMapping {

        @ParameterizedTest(name = "{0} → {1}")
        @MethodSource("statusToErrorCode")
        @DisplayName("전용 매핑이 있으면 그 코드로, 없으면 4xx/5xx 기본값으로 떨어진다")
        void mapsStatusToErrorCode(HttpStatus status, ErrorCode expected) {
            assertThat(GlobalExceptionHandler.errorCodeFor(status)).isEqualTo(expected);
        }

        static Stream<Arguments> statusToErrorCode() {
            return Stream.of(
                    // 전용 매핑이 있는 상태
                    Arguments.of(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND),
                    Arguments.of(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED),
                    Arguments.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE),
                    // 매핑이 없는 4xx — 406 처럼 우리가 아직 안 본 상태도 INVALID_REQUEST 로
                    Arguments.of(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST),
                    Arguments.of(HttpStatus.NOT_ACCEPTABLE, ErrorCode.INVALID_REQUEST),
                    Arguments.of(HttpStatus.CONFLICT, ErrorCode.INVALID_REQUEST),
                    // 5xx — REEH 경로로는 재현이 어려워 여기서 덮는다
                    Arguments.of(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR),
                    Arguments.of(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.INTERNAL_ERROR),
                    Arguments.of(HttpStatus.GATEWAY_TIMEOUT, ErrorCode.INTERNAL_ERROR)
            );
        }
    }

    @Nested
    @DisplayName("에러가 서버 로그에 남는다 — REEH 전환으로 잃었던 것")
    class Logging {

        @Test
        @DisplayName("프레임워크가 처리하는 예외도 원본 메시지와 함께 WARN 으로 남는다")
        void frameworkExceptionIsLogged() throws Exception {
            mockMvc.perform(patch(STORE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"\"}"))
                    .andExpect(status().isBadRequest());

            assertThat(logAppender.list)
                    .as("응답 title 은 고정 문구로 나가므로 원본 예외 정보는 로그에 남아야 한다")
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage())
                                .contains("MethodArgumentNotValidException")
                                .contains(STORE_PATH);
                    });
        }
    }
}
