package com.ktc4.backend.domain.store.enums;

/**
 * 변경 기록에 남기는 가게 정보 항목. 기록은 항목마다 한 줄이다.
 *
 * <p>조사 수정안의 키({@code ChangeField})와 따로 둔다 — 그쪽은 AI 가 고칠 수 있는 칸만 담고, 영업시간·메뉴는 없다.
 *
 * <p>⚠️ 값을 늘리면 DB 의 CHECK 제약도 같이 고쳐야 한다. Hibernate 가 이 칸에 허용 값 목록 CHECK 를 만들어 두어,
 * 제약을 그대로 두면 새 값을 넣는 순간 DB 가 거절한다. {@code db/migration} 에 마이그레이션을 하나 더하면 된다
 * (BIZ_NO 를 더한 {@code V4__add_biz_no_to_store_change_field.sql} 참고).
 *
 * <p>점주 개인 휴대폰({@code ownerPhone})은 일부러 넣지 않는다 — 개인정보라 가게 응답에도 내보내지 않는 칸이다.
 */
public enum StoreChangeField {
    NAME,
    ADDRESS_ROAD,
    PHONE,
    STATUS,
    BUSINESS_HOURS,
    MENUS,
    BIZ_NO
}
