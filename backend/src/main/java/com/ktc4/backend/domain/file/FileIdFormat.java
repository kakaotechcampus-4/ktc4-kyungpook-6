package com.ktc4.backend.domain.file;

/**
 * 바깥에 알려 주는 파일 ID 의 모양 — 소문자 16진수 UUID({@code 8-4-4-4-12}).
 *
 * <p>요청에서 파일 ID 를 받는 칸마다 이 모양을 검사한다. 모양이 틀린 값은 DB 를 찾아보기 전에 거절된다.
 */
public final class FileIdFormat {

    public static final String PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
    public static final String MESSAGE = "파일 ID 형식이 올바르지 않습니다";
    /** 경로 변수에 쓰는 모양 — {@code SecurityConfig} 의 경로 규칙이 같은 모양만 받는다. */
    public static final String PATH_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private FileIdFormat() {
    }
}
