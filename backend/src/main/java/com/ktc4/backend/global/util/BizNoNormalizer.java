package com.ktc4.backend.global.util;

import java.text.Normalizer;

/**
 * 사업자등록번호를 국세청 API 가 받는 형식(하이픈 없는 숫자 10자리)으로 정리한다.
 *
 * <p>DB·CSV 에는 {@code 123-45-67890}, 공백 섞인 값, 전각 숫자 등 여러 모양으로 들어올 수 있어
 * 조회·비교 전에 반드시 이 클래스를 거친다. store·business 도메인이 함께 쓰므로 global 에 둔다.
 */
public final class BizNoNormalizer {

    private static final int BIZ_NO_LENGTH = 10;

    private BizNoNormalizer() {
    }

    /**
     * 숫자만 남긴 사업자등록번호를 반환한다.
     *
     * <p>전각 숫자({@code １２３})는 일반 숫자로 바꾼 뒤 숫자 외 문자를 모두 제거한다.
     * 자릿수 검사는 하지 않으므로 {@link #isValid(String)} 로 따로 확인한다.
     *
     * @param raw 원본 사업자등록번호 (null 가능)
     * @return 숫자만 남긴 문자열. 입력이 null 이거나 숫자가 없으면 빈 문자열
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return Normalizer.normalize(raw, Normalizer.Form.NFKC).replaceAll("[^0-9]", "");
    }

    /**
     * 정리된 사업자등록번호가 숫자 10자리인지 확인한다.
     *
     * @param normalized {@link #normalize(String)} 를 거친 값
     * @return 숫자 10자리이면 true
     */
    public static boolean isValid(String normalized) {
        return normalized != null
                && normalized.length() == BIZ_NO_LENGTH
                && normalized.chars().allMatch(c -> c >= '0' && c <= '9');
    }
}
