package com.ktc4.backend.domain.store.dto;

import com.ktc4.backend.domain.store.enums.StoreStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 가게 정보 수정 요청 DTO.
 *
 * <p>부분 수정이라 모든 필드가 선택이다. 담아 보낸 필드만 바뀌고 빠뜨린 필드는 기존 값을 유지한다.
 * 길이 제한은 {@code Store} 엔티티의 컬럼 길이와 맞췄다.
 *
 * <p>이름·주소는 NOT NULL 컬럼이라 빈 값으로 지울 수 없다. {@code @Size(min = 1)} 만으로는
 * 공백뿐인 문자열({@code "   "})이 통과하므로 {@code @Pattern} 으로 한 번 더 막는다.
 * 전화번호는 빈 문자열로 지우는 것을 허용한다.
 *
 * <p>주입({@code POST /api/admin/stores/imports})은 9칸을 받는데 수정은 네 칸뿐이어서, 사업자등록번호에
 * 오타가 나면 가게를 지우고 다시 넣는 수밖에 없었다 — 그러면 쌓인 조사 이력까지 사라진다. 그래서
 * {@code bizNo}·{@code lat}·{@code lng}·{@code category}·{@code ownerPhone} 을 더했다(PROMPT-161).
 *
 * <p>위경도는 {@code null} 이 "유지" 라서 이 요청으로는 지울 수 없다. 나머지 문자열 칸은 빈 문자열로 지운다.
 *
 * <p>확인 시각({@code lastCheckedAt})은 서버가 정해야 하는 값이라
 * {@code POST /api/stores/{storeId}/confirm} 으로 분리했다.
 */
public record StoreUpdateRequest(
        @Schema(description = "가게명", example = "맛나 치킨")
        @Size(min = 1, max = 200, message = "가게명은 1~200자여야 합니다")
        @Pattern(regexp = "(?s).*\\S.*", message = "가게명은 공백만으로 채울 수 없습니다")
        String name,

        @Schema(description = "도로명 주소", example = "대구광역시 북구 대학로 80")
        @Size(min = 1, max = 500, message = "도로명 주소는 1~500자여야 합니다")
        @Pattern(regexp = "(?s).*\\S.*", message = "도로명 주소는 공백만으로 채울 수 없습니다")
        String addressRoad,

        @Schema(description = "전화번호", example = "010-1234-5678")
        @Size(max = 20, message = "전화번호는 20자를 넘을 수 없습니다")
        String phone,

        @Schema(description = "영업 상태 (OPEN: 영업중, SUSPENDED: 휴업, CLOSED: 폐업, UNKNOWN: 미확인)",
                example = "OPEN")
        StoreStatus status,

        @Schema(description = "위도", example = "35.8884")
        @DecimalMin(value = "-90.0", message = "위도는 -90~90 이어야 합니다")
        @DecimalMax(value = "90.0", message = "위도는 -90~90 이어야 합니다")
        Double lat,

        @Schema(description = "경도", example = "128.6107")
        @DecimalMin(value = "-180.0", message = "경도는 -180~180 이어야 합니다")
        @DecimalMax(value = "180.0", message = "경도는 -180~180 이어야 합니다")
        Double lng,

        @Schema(description = "업종", example = "분식")
        @Size(max = 50, message = "업종은 50자를 넘을 수 없습니다")
        String category,

        @Schema(description = "점주 개인 휴대폰 번호 — 숫자만 남겨 저장한다. 개인정보라 가게 응답에 내보내지 않고 "
                + "변경 기록에도 남기지 않는다", example = "010-1234-5678")
        @Size(max = 20, message = "점주 휴대폰 번호는 20자를 넘을 수 없습니다")
        String ownerPhone,

        @Schema(description = "사업자등록번호 — 하이픈이 있어도 숫자만 남겨 저장하며 숫자 10자리여야 한다. "
                + "바꾸면 국세청 확인 결과가 '아직 확인 안 됨' 으로 돌아간다 (옛 번호로 받은 답이기 때문)",
                example = "123-45-67890")
        @Size(max = 20, message = "사업자등록번호는 20자를 넘을 수 없습니다")
        String bizNo
) {

    /**
     * 기본 네 칸만 담는 요청. 나머지는 유지({@code null})다.
     *
     * <p>칸이 늘 때마다 호출부가 {@code null} 을 줄줄이 더하지 않도록 모았다. 조사 수정안 반영은
     * 사업자등록번호나 좌표를 바꾸지 않아 이 길로 간다.
     *
     * @param name        바꿀 가게명
     * @param addressRoad 바꿀 도로명 주소
     * @param phone       바꿀 전화번호
     * @param status      바꿀 영업 상태
     * @return 네 칸만 담긴 수정 요청
     */
    public static StoreUpdateRequest ofBasic(String name, String addressRoad, String phone, StoreStatus status) {
        return new StoreUpdateRequest(name, addressRoad, phone, status, null, null, null, null, null);
    }
}
