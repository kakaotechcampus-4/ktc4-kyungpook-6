package com.ktc4.backend.domain.store.enums;

/**
 * 점주가 고른 영업 상태 방식. 점주 앱 홈의 "영업시간대로 / 직접 켜고 끄기" 다.
 *
 * <p>영업중·영업 전·쉬는중 같은 상태는 서버가 계산하지 않는다 — 앱이 이 값과 영업시간으로 계산한다(PROMPT-185).
 */
public enum OpenMode {
    SCHEDULE,  // 영업시간대로 — 영업시간 안이면 영업중
    MANUAL     // 직접 켜고 끄기 — manualOpen 이 켜져 있으면 영업중, 꺼져 있으면 쉬는중
}
