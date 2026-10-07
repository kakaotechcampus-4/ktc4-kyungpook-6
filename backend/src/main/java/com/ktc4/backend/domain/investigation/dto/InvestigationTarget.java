package com.ktc4.backend.domain.investigation.dto;

import com.ktc4.backend.domain.store.dto.StoreCheckResponse;
import com.ktc4.backend.domain.store.enums.StoreStatus;

/**
 * AI 조사에 넘기는 가게 한 곳. 실행기 스레드로 넘어가므로 JPA 엔티티가 아니라 값만 담는다.
 *
 * <p>AI 가 이 값으로 웹을 검색하고, {@code phone}·{@code internalStatus} 는 웹에서 본 값과 비교할 우리 DB 값이다.
 *
 * @param storeId        가게 ID — 결과를 가게와 잇는 열쇠
 * @param name           상호
 * @param addressRoad    도로명 주소
 * @param bizNo          사업자등록번호 (없을 수 있음)
 * @param phone          전화번호 (없을 수 있음)
 * @param internalStatus 우리 DB 의 영업 상태
 * @param lat            위도 (없을 수 있음)
 * @param lng            경도 (없을 수 있음)
 */
public record InvestigationTarget(
        Long storeId,
        String name,
        String addressRoad,
        String bizNo,
        String phone,
        StoreStatus internalStatus,
        Double lat,
        Double lng
) {

    /** 조사 대상 나누기({@code InvestigationTargetSelector})가 돌려준 가게 자료에서 만든다. */
    public static InvestigationTarget from(StoreCheckResponse check) {
        return new InvestigationTarget(
                check.storeId(),
                check.name(),
                check.addressRoad(),
                check.bizNo(),
                check.phone(),
                check.internalStatus(),
                check.lat(),
                check.lng());
    }
}
