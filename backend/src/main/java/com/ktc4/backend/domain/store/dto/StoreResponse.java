package com.ktc4.backend.domain.store.dto;

import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.InvestigationExclusionReason;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 가게 정보 응답 DTO.
 *
 * <p>{@code nameNormalized} / {@code addressNormalized} 는 중복 판별·외부 데이터 매칭에만 쓰는
 * 내부 값이라 의도적으로 응답에서 제외했다.
 *
 * <p>{@code investigationExcluded} / {@code investigationExclusionReason} 은 <b>서버가 판정해</b> 담는다.
 * 화면이 {@code lastCheckedAt} 이 비었는지 같은 자기 기준으로 다시 판정하면, 목록이 미리 알려 준 제외와
 * 조사가 실제로 빼는 가게가 어긋난다 — {@code StoreCheckResponse} 가 {@code statusMismatch} /
 * {@code dataProblem} 을 서버에서 계산해 담는 것과 같은 이유다. 사유는 코드 값 그대로 내려 보내고
 * 문구는 화면이 정한다(에러 응답의 {@code type} 과 같은 방향).
 */
public record StoreResponse(
        @Schema(description = "가게 ID", example = "1")
        Long storeId,

        @Schema(description = "가게명", example = "예시가게")
        String name,

        @Schema(description = "도로명 주소", example = "가상특별시 예시구 샘플로 123")
        String addressRoad,

        @Schema(description = "위도", example = "12.3456")
        Double lat,

        @Schema(description = "경도", example = "123.4567")
        Double lng,

        @Schema(description = "영업 상태 (OPEN: 영업중, SUSPENDED: 휴업, CLOSED: 폐업, UNKNOWN: 미확인)", example = "OPEN")
        StoreStatus status,

        @Schema(description = "업종", example = "분식")
        String category,

        @Schema(description = "전화번호", example = "000-1234-5678")
        String phone,

        @Schema(description = "사업자등록번호", example = "123-45-67890")
        String bizNo,

        @Schema(description = "마지막으로 정보가 확인된 시각", example = "2026-09-01T10:00:00")
        LocalDateTime lastCheckedAt,

        @Schema(description = """
                마지막으로 값이 바뀐 시각. 한 번도 바뀌지 않았으면 비어 있습니다.
                위의 lastCheckedAt(사람이 직접 본 날)과 다릅니다 — 수정안을 반영해도 확인일은 오르지 않습니다.""",
                example = "2026-09-02T14:30:00")
        LocalDateTime lastChangedAt,

        @Schema(description = """
                지금 조사를 시작하면 이 가게가 조사에서 빠지는지. 빠지는 이유는 investigationExclusionReason 에 담깁니다.
                화면은 이 값으로 표시만 하고 판정을 다시 하지 마세요 — 판정은 조사 생성과 같은 코드가 합니다.""",
                example = "true")
        boolean investigationExcluded,

        @Schema(description = """
                조사에서 빠지는 이유. 빠지지 않으면 비어 있습니다. 문구는 화면이 정합니다.
                - NO_NTS_CHECK: 국세청과 비교할 수 없음(아직 대조하지 않았거나 우리 상태가 미확인)
                - DATA_PROBLEM: 사업자등록번호가 없거나 국세청에 없는 번호
                - ALREADY_CLOSED: 우리도 국세청도 폐업
                - ALREADY_SUSPENDED: 우리도 국세청도 휴업
                - STORE_NOT_FOUND: 없는 가게 번호 — 목록 응답에는 나오지 않습니다(조사 생성 결과에만 쓰입니다)""",
                example = "NO_NTS_CHECK")
        InvestigationExclusionReason investigationExclusionReason
) {

    /**
     * 가게 한 곳을 돌려주는 응답(수정·확인 완료). {@code lastChangedAt} 은 비어 나간다.
     *
     * <p>화면에 날짜 둘을 보여주는 목록 조회에서는 {@link #of} 로 채워 보낸다.
     *
     * @param store           가게
     * @param exclusionReason 그 가게가 조사에서 빠지는 이유. 빠지지 않으면 {@code null}.
     *                        부르는 쪽이 {@code InvestigationTargetSelector.exclusionReason} 으로 구해 넘긴다
     */
    public static StoreResponse from(Store store, InvestigationExclusionReason exclusionReason) {
        return of(store, null, exclusionReason);
    }

    /**
     * @param store           가게
     * @param lastChangedAt   그 가게가 마지막으로 바뀐 시각. 바뀐 적이 없으면 {@code null}
     * @param exclusionReason 그 가게가 조사에서 빠지는 이유. 빠지지 않으면 {@code null}
     */
    public static StoreResponse of(Store store, LocalDateTime lastChangedAt,
                                   InvestigationExclusionReason exclusionReason) {
        return new StoreResponse(
                store.getStoreId(),
                store.getName(),
                store.getAddressRoad(),
                store.getLat(),
                store.getLng(),
                store.getStatus(),
                store.getCategory(),
                store.getPhone(),
                store.getBizNo(),
                store.getLastCheckedAt(),
                lastChangedAt,
                exclusionReason != null,
                exclusionReason
        );
    }
}
