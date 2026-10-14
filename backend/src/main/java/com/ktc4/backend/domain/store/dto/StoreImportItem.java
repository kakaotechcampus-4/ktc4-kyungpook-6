package com.ktc4.backend.domain.store.dto;

import com.ktc4.backend.domain.store.enums.StoreStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 주입할 가게 한 곳.
 *
 * <p>길이 제한은 {@code Store} 엔티티의 컬럼 길이와 맞췄다. 이름·주소는 NOT NULL 컬럼이라 필수다.
 *
 * <p>정규화 값({@code nameNormalized}·{@code addressNormalized})은 받지 않는다 — 서비스가 계산한다.
 * 호출자가 넘기게 하면 규칙이 바뀌었을 때 옛 규칙으로 정규화된 값이 들어온다.
 *
 * <p>위경도 범위를 검사하지만, 범위 안의 엉뚱한 좌표는 걸러지지 않는다. 경산 명단에는 경산 가게에
 * 서울 좌표가 붙은 건이 2건 있었다 — 그건 사람이 지도에서 봐야 보인다.
 */
public record StoreImportItem(
        @Schema(description = "가게명 (화면에 보여주는 원본)", example = "맛나 치킨")
        @NotBlank(message = "가게명은 비울 수 없습니다")
        @Size(max = 200, message = "가게명은 200자를 넘을 수 없습니다")
        String name,

        @Schema(description = "도로명 주소 (화면에 보여주는 원본)", example = "대구광역시 북구 대학로 80")
        @NotBlank(message = "도로명 주소는 비울 수 없습니다")
        @Size(max = 500, message = "도로명 주소는 500자를 넘을 수 없습니다")
        String addressRoad,

        @Schema(description = "위도", example = "35.8884")
        @DecimalMin(value = "-90.0", message = "위도는 -90~90 이어야 합니다")
        @DecimalMax(value = "90.0", message = "위도는 -90~90 이어야 합니다")
        Double lat,

        @Schema(description = "경도", example = "128.6107")
        @DecimalMin(value = "-180.0", message = "경도는 -180~180 이어야 합니다")
        @DecimalMax(value = "180.0", message = "경도는 -180~180 이어야 합니다")
        Double lng,

        @Schema(description = "영업 상태 (OPEN: 영업중, SUSPENDED: 휴업, CLOSED: 폐업, UNKNOWN: 미확인). "
                + "없으면 UNKNOWN", example = "UNKNOWN")
        StoreStatus status,

        @Schema(description = "업종", example = "분식")
        @Size(max = 50, message = "업종은 50자를 넘을 수 없습니다")
        String category,

        @Schema(description = "가게 전화번호 — 확인 전화를 걸 때 쓴다. 명단의 표기를 그대로 저장한다",
                example = "053-810-5344")
        @Size(max = 20, message = "전화번호는 20자를 넘을 수 없습니다")
        String phone,

        @Schema(description = "점주 개인 휴대폰 번호 — 가입 신청자와 가게를 짝지을 단서. 숫자만 남겨 저장한다",
                example = "010-1234-5678")
        @Size(max = 20, message = "점주 휴대폰 번호는 20자를 넘을 수 없습니다")
        String ownerPhone,

        @Schema(description = "사업자등록번호 — 하이픈이 있어도 숫자만 남겨 저장한다", example = "123-45-67890")
        @Size(max = 20, message = "사업자등록번호는 20자를 넘을 수 없습니다")
        String bizNo,

        @Schema(description = "국세청 확인 기록. 없으면 가게만 들어가고 조사 자료에서는 "
                + "'아직 확인하지 않은 가게'(UNCONFIRMED)로 나온다")
        @Valid
        NtsCheckSeed ntsCheck
) {
}
