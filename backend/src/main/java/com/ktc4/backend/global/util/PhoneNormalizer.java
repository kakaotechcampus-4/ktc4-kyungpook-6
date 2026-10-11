package com.ktc4.backend.global.util;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * 전화번호를 비교할 수 있는 형식(숫자만)으로 정리한다.
 *
 * <p>{@code 010-0000-0000}, 공백 섞인 값, 전각 숫자, {@code +82 10-0000-0000} 처럼 여러 모양으로 들어와도
 * 같은 번호면 같은 값이 되게 한다. 점주 가입 신청의 휴대폰 번호를 가게의 전화번호와 맞춰 볼 때 쓴다.
 */
public final class PhoneNormalizer {

    private static final String KOREA_COUNTRY_CODE = "82";
    // 국내 번호는 가장 길어야 11자리다. 그보다 길고 82 로 시작하면 국가번호가 붙은 것으로 본다.
    private static final int MAX_DOMESTIC_LENGTH = 11;
    private static final Pattern MOBILE = Pattern.compile("01[016789]\\d{7,8}");
    private static final String MASKED = "****";
    private static final int MASK_KEEP_HEAD = 3;
    private static final int MASK_KEEP_TAIL = 4;

    private PhoneNormalizer() {
    }

    /**
     * 숫자만 남긴 전화번호를 반환한다. 국가번호(+82)가 붙어 있으면 국내 표기({@code 010…})로 바꾼다.
     *
     * @param raw 원본 전화번호 (null 가능)
     * @return 숫자만 남긴 문자열. 입력이 null 이거나 숫자가 없으면 빈 문자열
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String digits = Normalizer.normalize(raw, Normalizer.Form.NFKC).replaceAll("[^0-9]", "");
        if (digits.length() > MAX_DOMESTIC_LENGTH && digits.startsWith(KOREA_COUNTRY_CODE)) {
            digits = digits.substring(KOREA_COUNTRY_CODE.length());
            if (!digits.startsWith("0")) {
                digits = "0" + digits;
            }
        }
        return digits;
    }

    /**
     * 정리된 번호가 휴대폰 번호인지 확인한다 — 010·011·016·017·018·019 로 시작하는 10~11자리.
     *
     * @param normalized {@link #normalize(String)} 를 거친 값
     * @return 휴대폰 번호 형식이면 true
     */
    public static boolean isValidMobile(String normalized) {
        return normalized != null && MOBILE.matcher(normalized).matches();
    }

    /**
     * 전화번호의 가운데 자리를 가린다. 예: {@code 010-0000-0001} → {@code 010-****-0001}
     *
     * <p>남의 번호일 수 있는 값을 화면에 내려줄 때 쓴다. 앞 3자리와 끝 4자리만 남겨, 같은 번호인지 짐작은 되지만
     * 전화를 걸 수는 없게 한다.
     *
     * @param raw 원본 전화번호 (null 가능)
     * @return 가린 번호. {@code null} 이거나 숫자가 없으면 {@code null}, 남길 자리가 모자라게 짧으면 전체를 가린다
     */
    public static String mask(String raw) {
        String digits = normalize(raw);
        if (digits.isEmpty()) {
            return null;
        }
        if (digits.length() < MASK_KEEP_HEAD + MASK_KEEP_TAIL + 1) {
            return MASKED;
        }
        return digits.substring(0, MASK_KEEP_HEAD) + "-" + MASKED + "-"
                + digits.substring(digits.length() - MASK_KEEP_TAIL);
    }
}
