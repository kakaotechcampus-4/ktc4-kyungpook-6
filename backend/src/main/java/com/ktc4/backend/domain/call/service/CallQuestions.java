package com.ktc4.backend.domain.call.service;

import com.ktc4.backend.domain.call.dto.CallQuestion;
import com.ktc4.backend.domain.call.dto.CallQuestionField;
import com.ktc4.backend.domain.call.enums.CallField;
import com.ktc4.backend.domain.call.enums.CallReason;
import com.ktc4.backend.domain.signal.enums.ChangeField;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 가게와 조사 수정안을 보고 통화에서 물을 질문을 만든다.
 *
 * <p>전화를 건 이유에 따라 묻는 방식이 갈린다.
 *
 * <ul>
 *   <li>{@link CallReason#CHANGE_DETECTED} — 조사가 찾아낸 값이 있으니 항목마다 "이 값이 맞습니까" 를 묻는다
 *       (확인 질문). 조사와 점주의 답이 엇갈리면 그 자체가 담당자가 봐야 할 정보다</li>
 *   <li>{@link CallReason#PERIODIC_RECHECK} — 짚을 값이 없으니 "등록된 값이 그대로입니까" 를 한 질문으로 묻는다
 *       (변화 확인)</li>
 * </ul>
 *
 * <p><b>영업 여부를 가장 먼저 묻는다.</b> 전화 API 는 질문 배열의 순서대로 통화를 진행하고, 통화는 중간에
 * 끊길 수 있다. 이 서비스가 있는 이유가 "폐업한 가게에 아이가 헛걸음하는 것" 을 없애는 것이므로,
 * 한 가지만 듣고 끊긴다면 그 한 가지는 영업 여부여야 한다.
 *
 * <p><b>재확인 전화에서는 전화번호를 묻지 않는다.</b> 그 번호로 전화가 연결됐다는 것이 이미 답이다.
 * 변화가 잡혀 조사가 다른 번호를 제안한 경우에만 묻는다 — 그때는 옛 번호로 걸어 새 번호가 맞는지 확인한다.
 */
final class CallQuestions {

    /**
     * 영업 여부를 먼저, 그다음 사람이 알아듣기 쉬운 순서로 묻는다.
     * {@link ChangeField} 선언 순서(상태·전화·주소·상호)와 다른 것은 의도한 것이다 —
     * 그쪽은 화면에 줄을 세우는 순서고 이쪽은 사람에게 말로 묻는 순서다.
     */
    private static final List<CallField> ASK_ORDER =
            List.of(CallField.OPEN, CallField.STORE_NAME, CallField.STORE_LOCATION, CallField.PHONE_NUMBER);

    /** 재확인 전화에서 훑어 묻는 항목. 전화번호는 뺀다 — 연결된 것이 곧 답이다. */
    private static final List<CallField> RECHECK_FIELDS =
            List.of(CallField.OPEN, CallField.STORE_NAME, CallField.STORE_LOCATION);

    private CallQuestions() {
    }

    /**
     * 통화에서 물을 질문을 만든다.
     *
     * @param reason          전화를 건 이유
     * @param store           가게. 현재 등록 값을 여기서 읽는다
     * @param proposedChanges 조사 수정안({@code Task.proposedChanges}). 재확인 전화에서는 쓰지 않는다
     * @return 물을 순서대로의 질문. <b>물을 것이 없으면 빈 목록</b> — 부르는 쪽이 그때 전화를 걸지 않는다
     */
    static List<CallQuestion> of(CallReason reason, Store store, Map<String, Object> proposedChanges) {
        return reason == CallReason.CHANGE_DETECTED
                ? confirmations(store, proposedChanges)
                : recheck(store);
    }

    // 조사가 제안한 값마다 확인 질문 하나. 질문 id 는 q1, q2… 로 붙인다(전화 API 는 요청 안에서만 겹치지 않으면 된다).
    private static List<CallQuestion> confirmations(Store store, Map<String, Object> proposedChanges) {
        if (proposedChanges == null || proposedChanges.isEmpty()) {
            return List.of();
        }
        List<CallQuestion> questions = new ArrayList<>();
        for (CallField field : ASK_ORDER) {
            String proposed = proposedValue(proposedChanges, field);
            if (proposed == null) {
                continue;
            }
            String current = currentValue(store, field);
            /*
              현재 값이 없거나 제안 값과 같으면 묻지 않는다.
              - 현재 값이 없으면 "지금은 A 로 돼 있는데 B 가 맞습니까" 를 물을 수 없다
              - 같으면 물어도 얻을 것이 없다. 영업 여부에서 실제로 생긴다 — 휴업에서 폐업으로 바뀌었다는
                제안은 둘 다 "영업하지 않음" 이라, 전화로는 가를 수 없다. 그 판단은 담당자가 한다
            */
            if (current == null || current.equals(proposed)) {
                continue;
            }
            questions.add(CallQuestion.valueConfirmation(
                    "q" + (questions.size() + 1), new CallQuestionField(field, current), proposed));
        }
        return List.copyOf(questions);
    }

    // 등록된 값이 그대로인지 한 질문으로 묻는다. 현재 값이 없는 항목은 물을 수 없어 뺀다.
    private static List<CallQuestion> recheck(Store store) {
        List<CallQuestionField> fields = new ArrayList<>();
        for (CallField field : RECHECK_FIELDS) {
            String current = currentValue(store, field);
            if (current != null) {
                fields.add(new CallQuestionField(field, current));
            }
        }
        return fields.isEmpty() ? List.of() : List.of(CallQuestion.changeDetection("q1", fields));
    }

    // 우리 DB 의 지금 값. 영업 여부만 참·거짓으로 바꿔 내보낸다.
    private static String currentValue(Store store, CallField field) {
        return switch (field) {
            case OPEN -> openValue(store.getStatus());
            case STORE_NAME -> blankToNull(store.getName());
            case STORE_LOCATION -> blankToNull(store.getAddressRoad());
            case PHONE_NUMBER -> blankToNull(store.getPhone());
        };
    }

    // 수정안에서 그 항목의 제안 값. 값이 문자열이 아니면(직접 넣은 데이터 등) 없는 것으로 본다.
    private static String proposedValue(Map<String, Object> proposedChanges, CallField field) {
        Object value = proposedChanges.get(field.changeField().key());
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return field == CallField.OPEN ? openValue(parseStatus(text)) : text;
    }

    /**
     * 영업 상태를 전화로 물을 수 있는 참·거짓으로 바꾼다.
     *
     * <p>{@code UNKNOWN}(미확인)은 참도 거짓도 아니라 {@code null} 이다 — 아직 모르는 것을
     * "영업하지 않는다" 로 바꿔 물으면 점주가 "아니요" 라고 답하고, 그 답이 "영업 중" 으로 기록된다.
     */
    private static String openValue(StoreStatus status) {
        if (status == null || status == StoreStatus.UNKNOWN) {
            return null;
        }
        return String.valueOf(status == StoreStatus.OPEN);
    }

    private static StoreStatus parseStatus(String value) {
        try {
            return StoreStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            // 모르는 상태 값이면 묻지 않는다. 수정안을 반영할 때도 같은 값에서 막힌다(VerificationService.status).
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
