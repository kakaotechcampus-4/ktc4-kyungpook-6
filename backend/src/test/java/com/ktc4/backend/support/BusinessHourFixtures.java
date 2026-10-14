package com.ktc4.backend.support;

import java.util.ArrayList;
import java.util.List;

/**
 * 요청 본문에 넣을 영업시간 목록(JSON)을 만든다.
 *
 * <p>같은 줄을 여러 번 넣으면 겹침 검증에 걸린다. 개수 경계(21줄 통과, 22줄 거절)를 볼 때 겹침 때문에 거절되면
 * 개수 제한을 지워도 테스트가 통과하므로, 겹치지 않는 줄을 만든다 — 요일을 돌며 하루 네 칸까지 채운다.
 */
public final class BusinessHourFixtures {

    private static final String[] DAYS = {"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"};
    private static final String[][] SLOTS = {
            {"06:00", "09:00"}, {"10:00", "13:00"}, {"14:00", "17:00"}, {"18:00", "21:00"}};

    private BusinessHourFixtures() {
    }

    /**
     * 서로 겹치지 않는 영업시간 줄을 JSON 배열로 만든다.
     *
     * @param count 줄 수 (최대 28)
     * @return 예: {@code [{"day":"MON","open":"06:00","close":"09:00"}, ...]}
     */
    public static String nonOverlappingJson(int count) {
        if (count > DAYS.length * SLOTS.length) {
            throw new IllegalArgumentException("겹치지 않게 만들 수 있는 줄은 " + DAYS.length * SLOTS.length + "줄까지다");
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String[] slot = SLOTS[i / DAYS.length];
            lines.add("{\"day\":\"" + DAYS[i % DAYS.length] + "\",\"open\":\"" + slot[0] + "\",\"close\":\"" + slot[1] + "\"}");
        }
        return "[" + String.join(",", lines) + "]";
    }
}
