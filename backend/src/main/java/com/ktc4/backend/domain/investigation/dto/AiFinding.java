package com.ktc4.backend.domain.investigation.dto;

import com.ktc4.backend.domain.signal.enums.ChangeField;
import com.ktc4.backend.domain.task.enums.TaskClassification;

import java.util.List;
import java.util.Map;

/**
 * AI 가 가게 한 곳을 조사한 결과. AI 응답 형식은 {@code AiApiClient} 만 알고, 그 밖의 코드는 이 값만 본다.
 *
 * <p>성공이면 {@code classification} 이 있고 {@code failure} 가 비어 있다. AI 가 그 가게를 실패로 답했으면
 * ({@code 200} + {@code failure}) 반대다 — 예외가 아니라 결과로 오는 실패라 다시 부르지 않는다.
 *
 * @param storeId         가게 ID
 * @param classification  AI 판정 ({@code PRIORITY_CHECK} 또는 {@code NO_CHANGE}). 실패면 null
 * @param proposedChanges 항목별 새 값. 변화가 없거나 실패면 비어 있다
 * @param signals         잡힌 변화마다 근거 하나. 변화가 없거나 실패면 비어 있다
 * @param failure         AI 가 알려 준 실패 이유. 성공이면 null
 */
public record AiFinding(
        Long storeId,
        TaskClassification classification,
        Map<ChangeField, String> proposedChanges,
        List<AiSignal> signals,
        String failure
) {

    public AiFinding {
        proposedChanges = Map.copyOf(proposedChanges);
        signals = List.copyOf(signals);
    }

    /** 판정이 있는 결과. */
    public static AiFinding success(Long storeId, TaskClassification classification,
                                    Map<ChangeField, String> proposedChanges, List<AiSignal> signals) {
        return new AiFinding(storeId, classification, proposedChanges, signals, null);
    }

    /** AI 가 그 가게를 실패로 답한 결과. */
    public static AiFinding failed(Long storeId, String failure) {
        return new AiFinding(storeId, null, Map.of(), List.of(), failure);
    }

    /** AI 가 그 가게를 실패로 답했는가. */
    public boolean isFailed() {
        return failure != null;
    }
}
