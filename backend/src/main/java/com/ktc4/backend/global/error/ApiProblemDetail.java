package com.ktc4.backend.global.error;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.ProblemDetail;

import java.util.List;
import java.util.Map;

/**
 * RFC 9457 ProblemDetail 에 필드별 검증 오류({@code errors})를 더한 응답 본문.
 *
 * <p>에러 종류 식별자는 표준 필드인 {@code type}(URI) 하나다(RFC §3.1.1 MUST) — 별도 {@code code}
 * 필드를 두지 않는다. {@code errors} 는 §3.2 가 명시적으로 허용하는 확장 멤버이고, 그 이름 자체가
 * RFC 자신의 validation error 예제에서 왔다.
 */
@Schema(name = "ErrorResponse", description = "RFC 9457 기반 에러 응답. type 이 에러 종류의 유일한 식별자다")
public class ApiProblemDetail extends ProblemDetail {

    @Schema(description = "필드별 검증 오류. 요청 본문 검증에 실패했을 때만 실린다")
    private List<FieldError> errors;

    public ApiProblemDetail(ProblemDetail original) {
        super(original);
    }

    /**
     * 상속받은 확장 필드 맵을 닫는다. 우리는 {@code errors} 를 정식 필드로 두므로
     * {@code setProperty} 통로가 열려 있으면 스펙에 없는 필드가 응답에 섞일 수 있다.
     *
     * <p>{@code @JsonIgnore} 로는 막히지 않는다 — Spring 이 {@code ProblemDetailJacksonMixin} 을
     * 상위 타입 {@code ProblemDetail.class} 에 등록해 두어 상속 클래스까지 적용되기 때문이다.
     * 애노테이션이 둘 다 필요하다 — {@code @JsonAnyGetter(enabled = false)} 로 mixin 의 펼치기를 끄면
     * 이번엔 {@code properties} 라는 평범한 필드로 직렬화되므로, {@code @JsonIgnore} 로 그것까지 막는다.
     * 이 동작은 {@code ApiProblemDetailTest} 가 고정한다.
     */
    @Override
    @JsonAnyGetter(enabled = false)
    @JsonIgnore
    @Schema(hidden = true)
    public Map<String, Object> getProperties() {
        return super.getProperties();
    }

    public List<FieldError> getErrors() {
        return errors;
    }

    public void setErrors(List<FieldError> errors) {
        this.errors = errors;
    }

    @Schema(description = "검증에 실패한 필드 하나")
    public record FieldError(
            @Schema(description = "필드명", example = "name") String field,
            @Schema(description = "실패 사유", example = "가게명은 1~200자여야 합니다") String message
    ) {
    }
}
