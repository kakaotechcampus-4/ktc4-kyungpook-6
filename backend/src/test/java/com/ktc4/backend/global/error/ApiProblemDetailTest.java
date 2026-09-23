package com.ktc4.backend.global.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ApiProblemDetail} 직렬화 계약 확인.
 *
 * <p>{@code ProblemDetail} 은 Spring 이 {@code ProblemDetailJacksonMixin} 을 통해
 * {@code properties} 맵을 최상위 JSON 으로 펼치도록 해 둔다. 그 mixin 은 상위 타입인
 * {@code ProblemDetail.class} 에 등록돼 있어 상속 클래스에도 그대로 적용된다 —
 * 즉 하위 클래스에서 {@code @JsonIgnore} 만 붙여서는 막히지 않는다.
 * 우리는 {@code code}/{@code errors} 를 정식 필드로 두므로 그 통로를 닫았고, 이 테스트가 그걸 고정한다.
 */
@JsonTest
class ApiProblemDetailTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("errors 가 최상위 필드로 직렬화된다 — RFC 9457 이 허용하는 확장 멤버")
    void serializesOurFields() throws Exception {
        ApiProblemDetail problem = new ApiProblemDetail(
                ProblemDetail.forStatus(HttpStatus.BAD_REQUEST));
        problem.setErrors(java.util.List.of(
                new ApiProblemDetail.FieldError("name", "가게명은 1~200자여야 합니다")));

        String json = objectMapper.writeValueAsString(problem);

        assertThat(json).contains("\"field\":\"name\"");
    }

    @Test
    @DisplayName("code 필드는 없다 — 에러 종류 식별자는 표준 필드인 type 이다")
    void hasNoCodeField() throws Exception {
        ApiProblemDetail problem = new ApiProblemDetail(
                ProblemDetail.forStatus(HttpStatus.BAD_REQUEST));

        String json = objectMapper.writeValueAsString(problem);

        assertThat(json).doesNotContain("\"code\"");
    }

    @Test
    @DisplayName("setProperty 로 넣은 값은 응답에 새지 않는다 — 스펙에 없는 필드가 나가면 안 된다")
    void doesNotLeakArbitraryProperties() throws Exception {
        ApiProblemDetail problem = new ApiProblemDetail(
                ProblemDetail.forStatus(HttpStatus.BAD_REQUEST));
        problem.setProperty("debugCause", "내부 스택 정보");

        String json = objectMapper.writeValueAsString(problem);

        assertThat(json)
                .as("OpenAPI 스펙에는 없는 필드라, 응답에 나가면 스펙과 런타임이 어긋난다")
                .doesNotContain("debugCause")
                .doesNotContain("내부 스택 정보")
                .doesNotContain("properties");
    }
}
