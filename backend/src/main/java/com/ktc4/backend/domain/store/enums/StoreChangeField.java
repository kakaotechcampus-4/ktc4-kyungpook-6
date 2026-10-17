package com.ktc4.backend.domain.store.enums;

/**
 * 변경 기록에 남기는 가게 정보 항목. 기록은 항목마다 한 줄이다.
 *
 * <p>조사 수정안의 키({@code ChangeField})와 따로 둔다 — 그쪽은 AI 가 고칠 수 있는 칸만 담고, 영업시간·메뉴는 없다.
 *
 * <p>⚠️ 값을 늘리면 DB 의 CHECK 제약을 직접 고쳐야 한다. Hibernate 가 이 칸에 허용 값 목록 CHECK 를 만드는데,
 * {@code ddl-auto: update} 는 이미 있는 제약을 고치지 않아 새 값을 넣는 순간 거절된다.
 */
public enum StoreChangeField {
    NAME,
    ADDRESS_ROAD,
    PHONE,
    STATUS,
    BUSINESS_HOURS,
    MENUS
}
