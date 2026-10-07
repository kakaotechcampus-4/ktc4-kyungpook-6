package com.ktc4.backend.domain.member.dto;

import com.ktc4.backend.domain.member.entity.OwnerInfo;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.global.util.BizNoNormalizer;
import com.ktc4.backend.global.util.PhoneNormalizer;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 가입 신청한 점주에게 연결할 수 있는 후보 가게 한 곳. 관리자가 이 중에서 고른다.
 *
 * <p>서버는 후보를 찾아 줄 뿐 확정하지 않는다. 신청서의 사업자등록번호·휴대폰 번호·상호명은 본인이 적은 값이라
 * 그것만으로 가게를 정하면 남의 가게에 연결될 수 있다.
 */
public record StoreCandidateResponse(
        @Schema(description = "가게 ID — 승인 API 의 storeId 로 쓴다", example = "123") Long storeId,
        @Schema(description = "가게명", example = "예시분식") String name,
        @Schema(description = "도로명 주소") String addressRoad,
        @Schema(description = "가게에 등록된 사업자등록번호. 없을 수 있음", example = "1234567890") String bizNo,
        @Schema(description = "가게에 등록된 전화번호 — 가운데 자리를 가린 값. 없을 수 있음. "
                + "신청서의 번호와 같은지는 phoneMatched 로 본다", example = "010-****-0000") String phone,
        @Schema(description = "영업 상태", example = "OPEN") StoreStatus status,
        @Schema(description = "신청서의 사업자등록번호와 가게의 번호가 같은지")
        boolean bizNoMatched,
        @Schema(description = "신청서의 휴대폰 번호와 가게의 전화번호가 같은지. 둘 다 false 면 상호명으로 찾은 후보")
        boolean phoneMatched,
        @Schema(description = "이 가게에 이미 연결된 점주 수. 1 이상이면 재가입·양수·사칭 여부를 확인한다", example = "0")
        long linkedOwnerCount
) {
    /**
     * @param store            후보 가게
     * @param applicant        가입 신청한 점주의 정보
     * @param linkedOwnerCount 그 가게에 이미 연결된 점주 수
     */
    public static StoreCandidateResponse of(Store store, OwnerInfo applicant, long linkedOwnerCount) {
        // 가게의 번호들은 표기가 제각각일 수 있어, 질의와 같은 방식(숫자만)으로 맞춘 뒤 비교한다.
        return new StoreCandidateResponse(
                store.getStoreId(),
                store.getName(),
                store.getAddressRoad(),
                store.getBizNo(),
                // 가게 전화번호 칸에는 점주 개인 휴대폰이 들어 있을 수 있다. 신청서와 맞지 않는 가게의 번호는
                // 남의 번호이므로 원문으로 내려주지 않는다 — 신청서를 미끼로 번호를 찾아내는 창구가 되지 않게.
                PhoneNormalizer.mask(store.getPhone()),
                store.getStatus(),
                sameNumber(applicant.getBizNo(), BizNoNormalizer.normalize(store.getBizNo())),
                sameNumber(applicant.getPhone(), PhoneNormalizer.normalize(store.getPhone())),
                linkedOwnerCount
        );
    }

    // 신청서의 값이 비어 있으면 일치로 보지 않는다 — 가게 쪽도 비어 있을 때 "빈 값끼리 같다"가 되지 않게.
    private static boolean sameNumber(String applicantValue, String storeValue) {
        return applicantValue != null && !applicantValue.isEmpty() && applicantValue.equals(storeValue);
    }
}
