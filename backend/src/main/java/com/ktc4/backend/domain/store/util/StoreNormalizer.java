package com.ktc4.backend.domain.store.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 가게 이름·주소를 중복 판별·외부 데이터 매칭용 비교 값으로 정규화한다.
 *
 * <p>규칙은 매칭률을 보며 계속 조정될 값이라 이 클래스 한 곳에서만 관리한다.
 * 호출은 서비스 계층에서 하며, 규칙을 바꾸면 기존 데이터를 전부 다시 계산해야 한다.
 * 규칙 설명은 {@code backend/docs/데이터_정규화_가이드.md} 참고.
 */
public final class StoreNormalizer {

    // 법인 표기. 괄호를 지우기 전에 먼저 제거해야 "(주)" 가 "주" 로 남지 않는다.
    // ㈜ ㈔ 같은 기호는 NFKC 변환에서 "(주)" "(사)" 로 풀리므로 약칭 목록이 함께 처리한다.
    // 표기가 늘어나면 아래 두 목록에만 추가하면 된다.
    private static final List<String> CORPORATE_WORDS = List.of("주식회사", "유한회사", "사단법인", "재단법인");
    private static final List<String> CORPORATE_ABBREVIATIONS = List.of("주", "유", "사", "재");
    private static final List<Pattern> CORPORATE_MARKS = buildCorporateMarks();

    private static final Pattern NOT_HANGUL_ALPHA_DIGIT = Pattern.compile("[^가-힣a-z0-9]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    // 워드·한글 프로그램이 바꿔 넣는 하이픈·대시·빼기 기호 (U+2010~U+2014, U+2212)
    private static final Pattern DASH_VARIANTS = Pattern.compile("[\\u2010-\\u2014\\u2212]");
    // \s 로 잡히지 않는 보이지 않는 문자 (zero-width space·non-joiner·joiner, BOM)
    private static final Pattern INVISIBLE = Pattern.compile("[\\u200B-\\u200D\\uFEFF]");

    private StoreNormalizer() {
    }

    // "주식 회사" 처럼 띄어 써도 잡히도록 글자 사이에 공백을 허용하고, 약칭은 "( 사 )" 형태까지 만든다.
    private static List<Pattern> buildCorporateMarks() {
        List<Pattern> marks = new ArrayList<>();
        for (String word : CORPORATE_WORDS) {
            marks.add(Pattern.compile(String.join("\\s*", word.split(""))));
        }
        for (String abbreviation : CORPORATE_ABBREVIATIONS) {
            marks.add(Pattern.compile("\\(\\s*" + abbreviation + "\\s*\\)"));
        }
        return List.copyOf(marks);
    }

    /**
     * 가게 이름을 정규화한다.
     *
     * <p>전각 문자 변환 → 법인 표기 제거 → 영문 소문자 → 한글·영문·숫자 외 문자 제거 순으로 적용한다.
     *
     * @param raw 원본 가게 이름 (null 가능)
     * @return 정규화된 이름. 입력이 null 이면 빈 문자열
     */
    public static String normalizeName(String raw) {
        if (raw == null) {
            return "";
        }
        String value = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        for (Pattern mark : CORPORATE_MARKS) {
            value = mark.matcher(value).replaceAll("");
        }
        value = value.toLowerCase(Locale.ROOT);
        return NOT_HANGUL_ALPHA_DIGIT.matcher(value).replaceAll("");
    }

    /**
     * 도로명 주소를 정규화한다.
     *
     * <p>전각 문자 변환 → 보이지 않는 문자 제거 → 대시 문자를 하이픈으로 통일 → 공백 제거 순으로 적용한다.
     * 하이픈은 남기는데, 지우면 {@code 80-1} 과 {@code 801} 이 같은 주소가 되기 때문이다.
     *
     * @param raw 원본 도로명 주소 (null 가능)
     * @return 정규화된 주소. 입력이 null 이면 빈 문자열
     */
    public static String normalizeAddress(String raw) {
        if (raw == null) {
            return "";
        }
        String value = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        value = INVISIBLE.matcher(value).replaceAll("");
        value = DASH_VARIANTS.matcher(value).replaceAll("-");
        return WHITESPACE.matcher(value).replaceAll("");
    }
}
