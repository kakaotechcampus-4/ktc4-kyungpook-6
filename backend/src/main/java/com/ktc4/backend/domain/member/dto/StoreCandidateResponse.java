package com.ktc4.backend.domain.member.dto;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.member.entity.OwnerInfo;
import com.ktc4.backend.domain.member.enums.NameMatch;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.util.StoreNormalizer;
import com.ktc4.backend.global.util.BizNoNormalizer;
import com.ktc4.backend.global.util.PhoneNormalizer;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 가입 신청한 점주에게 연결할 수 있는 후보 가게 한 곳. 관리자가 이 중에서 고른다.
 *
 * <p>서버는 후보를 찾아 줄 뿐 확정하지 않는다. 신청서의 사업자등록번호·휴대폰 번호·상호명은 본인이 적은 값이라
 * 그것만으로 가게를 정하면 남의 가게에 연결될 수 있다. 대신 신청서와 가게 정보가 어디까지 같은지
 * ({@code bizNoMatched}, {@code phoneMatched}, {@code nameMatch})와 국세청 상태를 함께 보여 줘 관리자의 판단을 돕는다.
 */
public record StoreCandidateResponse(
        @Schema(description = "가게 ID — 승인 API 의 storeId 로 쓴다", example = "123") Long storeId,
        @Schema(description = "가게명", example = "예시분식") String name,
        @Schema(description = "도로명 주소") String addressRoad,
        @Schema(description = "가게에 등록된 사업자등록번호. 없을 수 있음", example = "1234567890") String bizNo,
        @Schema(description = "가게 전화번호(매장 번호). 없을 수 있음. 관리자가 확인 전화를 걸 때 쓴다. "
                + "신청서의 휴대폰 번호와는 다른 값이다", example = "053-000-0000") String phone,
        @Schema(description = "영업 상태", example = "OPEN") StoreStatus status,
        @Schema(description = "신청서의 사업자등록번호와 가게의 번호가 같은지")
        boolean bizNoMatched,
        @Schema(description = "신청서의 휴대폰 번호와 가게에 등록된 점주 휴대폰 번호가 같은지. 점주 휴대폰 번호 자체는 내려주지 않는다")
        boolean phoneMatched,
        @Schema(description = "신청서의 상호명과 가게명이 얼마나 같은지. EXACT(같음) / PARTIAL(가게명에 포함됨) / NONE(겹치지 않음)",
                example = "EXACT")
        NameMatch nameMatch,
        @Schema(description = "이 가게에 이미 연결된 점주 수. 1 이상이면 재가입·양수·사칭 여부를 확인한다", example = "0")
        long linkedOwnerCount,
        @Schema(description = "국세청 기준 이 가게 사업자의 상태 — 배치가 마지막으로 확인한 값. 확인 전이거나 "
                + "사업자등록번호가 없으면 비어 있음. bizNoMatched 가 true 면 신청서 번호의 상태와 같다", example = "ACTIVE")
        BusinessState ntsState
) {
    /**
     * @param store            후보 가게
     * @param applicant        가입 신청한 점주의 정보
     * @param linkedOwnerCount 그 가게에 이미 연결된 점주 수
     * @param ntsState         그 가게의 국세청 상태. 알 수 없으면 {@code null}
     */
    public static StoreCandidateResponse of(Store store, OwnerInfo applicant, long linkedOwnerCount,
                                            BusinessState ntsState) {
        // 가게의 번호들은 표기가 제각각일 수 있어, 질의와 같은 방식(숫자만)으로 맞춘 뒤 비교한다.
        return new StoreCandidateResponse(
                store.getStoreId(),
                store.getName(),
                store.getAddressRoad(),
                store.getBizNo(),
                // 매장 번호라 그대로 내려준다. 점주 개인 번호(ownerPhone)는 내려주지 않고 일치 여부만 알려 준다 —
                // 신청서를 미끼로 남의 개인 번호를 찾아내는 창구가 되지 않게.
                store.getPhone(),
                store.getStatus(),
                sameNumber(applicant.getBizNo(), BizNoNormalizer.normalize(store.getBizNo())),
                sameNumber(applicant.getPhone(), PhoneNormalizer.normalize(store.getOwnerPhone())),
                NameMatch.of(StoreNormalizer.normalizeName(applicant.getStoreName()), store.getNameNormalized()),
                linkedOwnerCount,
                ntsState
        );
    }

    // 신청서의 값이 비어 있으면 일치로 보지 않는다 — 가게 쪽도 비어 있을 때 "빈 값끼리 같다"가 되지 않게.
    private static boolean sameNumber(String applicantValue, String storeValue) {
        return applicantValue != null && !applicantValue.isEmpty() && applicantValue.equals(storeValue);
    }
}
