package com.ktc4.backend.domain.store.dto;

import com.ktc4.backend.domain.store.enums.BusinessDay;

/**
 * 영업시간 한 줄의 값만 — 엔티티를 영속성 컨텍스트에 올리지 않고 읽을 때 쓴다(변경 기록의 스냅숏).
 *
 * @param day         요일
 * @param openMinute  여는 시간 — 자정부터 지난 분 (0 ~ 1439)
 * @param closeMinute 닫는 시간 — 자정부터 지난 분 (1 ~ 1440)
 */
public record BusinessHourMinutes(BusinessDay day, int openMinute, int closeMinute) {
}
