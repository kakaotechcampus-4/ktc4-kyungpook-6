package com.ktc4.backend.domain.business.dto;

import com.ktc4.backend.domain.business.enums.BusinessState;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

// 국세청 원본 응답(NtsBusinessStatus)을 우리 말로 해석한 결과.
// closedAt 은 폐업자(CLOSED)일 때만 채운다.
public record BusinessStatus(
        String bizNo,
        BusinessState state,
        LocalDate closedAt
) {

    /**
     * 국세청 응답 한 건을 해석한다.
     *
     * <p>상태는 {@code b_stt_cd} 로만 판단한다. 폐업자여도 {@code tax_type} 은 "일반과세자" 로 오고,
     * 미등록이면 {@code tax_type} 에 안내 문구가 들어오기 때문에 다른 필드는 판단에 쓰지 않는다.
     *
     * <p>값이 이상하면 조용히 넘기지 않고 예외를 던진다. 틀린 상태가 AI 조사 자료에 섞이는 것보다
     * 그 건을 "국세청 정보 없음" 으로 두는 편이 안전하기 때문이다.
     *
     * @param item 국세청 원본 응답 한 건
     * @return 해석된 사업자 상태
     * @throws IllegalArgumentException 번호({@code b_no})나 상태 코드가 없거나, 알 수 없는 상태 코드이거나,
     *                                  폐업일 형식이 YYYYMMDD 가 아닐 때
     */
    public static BusinessStatus from(NtsBusinessStatus item) {
        if (item.bNo() == null || item.bNo().isBlank()) {
            throw new IllegalArgumentException("국세청 응답에 사업자등록번호가 없습니다");
        }
        BusinessState state = BusinessState.fromCode(item.bSttCd());
        LocalDate closedAt = state == BusinessState.CLOSED ? parseDate(item.endDt()) : null;
        return new BusinessStatus(item.bNo(), state, closedAt);
    }

    private static LocalDate parseDate(String yyyymmdd) {
        if (yyyymmdd == null || yyyymmdd.isBlank()) {
            return null;
        }
        String value = yyyymmdd.strip();
        try {
            return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("국세청 날짜 형식이 아님: " + value, e);
        }
    }
}
