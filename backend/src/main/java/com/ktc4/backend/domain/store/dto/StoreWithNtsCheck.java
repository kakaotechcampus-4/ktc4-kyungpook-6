package com.ktc4.backend.domain.store.dto;

import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsCheck;

/**
 * 가게와 그 가게의 국세청 확인 기록을 함께 담는 조회 결과.
 *
 * <p>배치가 아직 확인하지 않은 가게도 목록에 나와야 해서 바깥 조인(left join)으로 읽는다.
 * 그래서 {@code check} 는 null 일 수 있고, 그 경우 "아직 확인되지 않음"을 뜻한다.
 */
public record StoreWithNtsCheck(
        Store store,
        StoreNtsCheck check
) {
}
