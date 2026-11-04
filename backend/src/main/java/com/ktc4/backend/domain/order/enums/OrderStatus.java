package com.ktc4.backend.domain.order.enums;

import java.util.Set;

// 주문 상태. RECEIVED → COOKING → DONE, 접수 대기에서만 CANCELED. 전이 규칙은 ChildOrder 가 지킨다.
public enum OrderStatus {
    RECEIVED,  // 접수 대기 — 아동이 주문했고 아직 가게에서 확인하지 않음
    COOKING,   // 조리 중 — 점주가 방문을 확인(QR·주문번호)하고 조리를 시작함
    DONE,      // 전달 완료
    CANCELED;  // 취소 — 아동이나 점주가 접수 대기일 때 취소함

    // 끝나지 않은 주문. 아동 한 명에게 이 상태의 주문은 하나뿐이다(DB 부분 unique 인덱스 uk_child_order_active 와 같은 목록)
    public static final Set<OrderStatus> ACTIVE = Set.of(RECEIVED, COOKING);
}
