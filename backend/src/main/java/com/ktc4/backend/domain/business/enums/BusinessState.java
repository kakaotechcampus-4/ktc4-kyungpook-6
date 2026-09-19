package com.ktc4.backend.domain.business.enums;

// 국세청이 사업자등록번호를 보고 판단한 상태. 가게의 실제 영업 상태(StoreStatus)와는 뜻이 다르므로
// 이 값으로 가게 상태를 바로 바꾸지 않는다. 가게 상태와 함께 AI 조사 자료로만 넘기고, 판단은 AI 조사에 맡긴다.
public enum BusinessState {
    ACTIVE,         // 계속사업자 (b_stt_cd "01")
    SUSPENDED,      // 휴업자 (b_stt_cd "02")
    CLOSED,         // 폐업자 (b_stt_cd "03")
    NOT_REGISTERED; // 국세청에 등록되지 않은 번호 (b_stt_cd "")

    // 미등록은 코드가 "" 로 올 때뿐이다. 필드 자체가 없으면(null) 응답이 깨진 것이라 미등록으로 넘기지 않는다.
    public static BusinessState fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("국세청 상태 코드가 없습니다");
        }
        return switch (code) {
            case "" -> NOT_REGISTERED;
            case "01" -> ACTIVE;
            case "02" -> SUSPENDED;
            case "03" -> CLOSED;
            default -> throw new IllegalArgumentException("알 수 없는 국세청 상태 코드: " + code);
        };
    }
}
