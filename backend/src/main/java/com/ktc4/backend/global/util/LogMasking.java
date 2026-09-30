package com.ktc4.backend.global.util;

/**
 * 로그에 남을 수 있는 개인정보를 일부만 보이게 가린다.
 *
 * <p>요청 객체의 {@code toString()} 처럼 로그에 찍힐 수 있는 곳에서 쓴다. 전부 지우지 않고 앞 한 글자와
 * 도메인을 남기는 이유는, 문제를 추적할 때 "어느 계정 쪽 요청이었는지" 정도는 가늠할 수 있게 하기 위해서다.
 */
public final class LogMasking {

    private static final String MASK = "***";

    private LogMasking() {
    }

    /**
     * 이메일의 아이디 부분을 첫 글자만 남기고 가린다. 예: {@code owner@example.com} → {@code o***@example.com}
     *
     * @param email 이메일. {@code null} 이면 {@code null}
     * @return 가린 이메일. {@code @} 가 없으면 전체를 가린다
     */
    public static String maskEmail(String email) {
        if (email == null) {
            return null;
        }
        int at = email.lastIndexOf('@');
        if (at <= 0) {
            return MASK;
        }
        return email.charAt(0) + MASK + email.substring(at);
    }
}
