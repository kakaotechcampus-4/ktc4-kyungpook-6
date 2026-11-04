package com.ktc4.backend.domain.call.service;

import com.ktc4.backend.domain.call.entity.CallAnswer;
import com.ktc4.backend.domain.call.enums.CallField;
import com.ktc4.backend.domain.signal.enums.ChangeField;
import com.ktc4.backend.domain.store.dto.StoreUpdateRequest;
import com.ktc4.backend.domain.store.entity.Store;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 통화에서 들은 답 중 <b>가게에 그대로 반영할 것</b>을 고른다.
 *
 * <p>끝까지 간 통화의 답은 믿을 만하다 — 전화 API 가 상대가 맞는지 먼저 확인하고
 * ({@code WRONG_TARGET}·{@code IDENTITY_UNCONFIRMED} 가 따로 있다), 알아들은 값을 다시 읽어 주고
 * 확인받는다. 그래서 담당자를 거치지 않고 바로 반영한다.
 *
 * <h3>영업 여부만 반영하지 않는다</h3>
 *
 * 우리 영업 상태는 세 값인데({@code OPEN}·{@code SUSPENDED}·{@code CLOSED}) 전화로는
 * "지금도 영업하십니까" 참·거짓 하나만 묻는다. <b>"안 합니다" 를 들어도 휴업인지 폐업인지 모른다.</b>
 *
 * <ul>
 *   <li>휴업인데 {@code CLOSED} 로 넣으면 멀쩡한 가게가 목록에서 사라진다</li>
 *   <li>폐업인데 {@code SUSPENDED} 로 넣으면 목록에 남아 <b>아이가 헛걸음한다</b></li>
 * </ul>
 *
 * 게다가 질문의 시점이 애매하다 — 정기휴무일이나 하루 쉬는 날에 받은 사장님은 "오늘은 안 해요"
 * 라고 답한다. 그것을 폐업으로 찍으면 멀쩡한 가게가 사라진다.
 *
 * <p>영업 상태는 <b>국세청 대조라는 더 나은 출처</b>가 따로 있다(매일 새벽, 휴업과 폐업을 구분해
 * 준다). 그래서 전화의 영업 여부 답은 화면에만 보여 주고 판단은 담당자가 한다.
 */
final class CallAnswers {

    private CallAnswers() {
    }

    /**
     * 답에서 가게에 반영할 값을 고른다. 지금 값과 같거나, 모르는 항목이거나, 영업 여부면 뺀다.
     *
     * @param store   지금 가게. 같은 값을 다시 쓰지 않으려고 본다
     * @param answers 통화에서 들은 답
     * @return 항목 키({@code ChangeField.key()}) → 새 값. 반영할 것이 없으면 빈 맵
     */
    static Map<String, Object> toApply(Store store, List<CallAnswer> answers) {
        if (answers == null || answers.isEmpty()) {
            return Map.of();
        }
        Map<ChangeField, String> picked = new EnumMap<>(ChangeField.class);
        for (CallAnswer answer : answers) {
            CallField field = CallField.fromApiField(answer.field()).orElse(null);
            // 모르는 항목은 반영하지 않는다. 화면에는 받은 키 그대로 보여 준다
            if (field == null || field.isBoolean()) {
                continue;
            }
            String value = answer.value();
            if (value == null || value.isBlank()) {
                continue;
            }
            String now = current(store, field);
            if (value.equals(now)) {
                continue;
            }
            // 같은 항목을 두 번 들었으면 나중 답을 쓴다 — 통화에서 고쳐 말한 경우다
            picked.put(field.changeField(), value);
        }
        Map<String, Object> applied = new LinkedHashMap<>();
        picked.forEach((field, value) -> applied.put(field.key(), value));
        return applied;
    }

    /**
     * 반영할 값들을 가게 수정 요청으로 바꾼다.
     *
     * <p>영업 상태는 항상 비운다 — {@link #toApply} 가 이미 걸렀지만, 여기서도 넘기지 않아
     * 전화가 영업 상태를 바꾸는 길이 아예 없게 한다.
     */
    static StoreUpdateRequest toUpdateRequest(Map<String, Object> applied) {
        return StoreUpdateRequest.ofBasic(
                (String) applied.get(ChangeField.NAME.key()),
                (String) applied.get(ChangeField.ADDRESS_ROAD.key()),
                (String) applied.get(ChangeField.PHONE.key()),
                null);
    }

    private static String current(Store store, CallField field) {
        return switch (field) {
            case STORE_NAME -> store.getName();
            case STORE_LOCATION -> store.getAddressRoad();
            case PHONE_NUMBER -> store.getPhone();
            case OPEN -> null;  // 반영하지 않는다
        };
    }
}
