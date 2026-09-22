package com.ktc4.backend.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.util.Locale;

/**
 * RFC 9457(Problem Details for HTTP APIs) 의 problem type 정의 대장.
 *
 * <p>§4 가 problem type 문서화에 요구하는 세 가지 — type URI, title, HTTP status — 를 그대로 담는다.
 * 이 enum 은 우리가 지어낸 카탈로그가 아니라 표준이 요구하는 문서화 항목 자체다.
 *
 * <p>{@code title} 은 에러 종류의 사람이 읽는 이름이며, 같은 종류면 항상 같은 문구여야 한다
 * (§3.1.3 "SHOULD NOT change from occurrence to occurrence"). 이번 건에 대한 구체적인 설명은
 * {@code title} 이 아니라 응답의 {@code detail} 필드가 맡는다 — {@link GlobalExceptionHandler} 참고.
 *
 * <p>식별자는 {@code code} 같은 별도 필드가 아니라 {@link #getType()} 이 만드는 {@code type} URI
 * 하나다(§3.1.1 "Consumers MUST use the 'type' URI ... as the problem type's primary identifier").
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다"),
    BIZNO_API_ERROR(HttpStatus.BAD_GATEWAY, "비즈노 API 호출에 실패했습니다"),
    NTS_API_ERROR(HttpStatus.BAD_GATEWAY, "국세청 API 호출에 실패했습니다"),

    // 아래 넷은 GlobalExceptionHandler.errorCodeFor 가 프레임워크 예외의 상태코드를 매핑할 때 쓴다.
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 형식입니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다");

    /**
     * problem type 문서가 실제로 호스팅되기 전까지는 열리지 않는 자리표시자다.
     * RFC 9457 §3.1.1 이 비역참조(non-resolvable) type URI 를 명시적으로 허용한다
     * ("The type URI is allowed to be a non-resolvable URI").
     *
     * <p>{@code urn:} 스킴은 의도적으로 안 쓴다 — IANA URN Namespace 레지스트리에 등록 절차 없이
     * 자가 배정할 방법이 없다(자체 확인: 등록된 NID 중 "problem" 없음). §4 가 https 스킴을 권장한다.
     */
    private static final String TYPE_BASE = "https://kakaotechcampus-4.github.io/ktc4-kyungpook-6/errors/";

    private final HttpStatus httpStatus;

    /**
     * 에러 종류의 사람이 읽는 이름. RFC 9457 응답의 {@code title} 로 나간다.
     * 같은 종류면 항상 같은 문구다 — 이번 건에 대한 구체적인 설명은 응답의 {@code detail} 이 맡는다.
     */
    private final String title;

    /**
     * RFC 9457 응답의 {@code type} — 에러 종류를 식별하는 유일한 필드.
     * enum 이름을 케밥케이스로 바꿔 {@link #TYPE_BASE} 에 붙인다.
     * 예: {@code INVALID_REQUEST} → {@code .../errors/invalid-request}
     */
    public URI getType() {
        return URI.create(TYPE_BASE + name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }
}
