package com.ktc4.backend.domain.task.service;

import com.ktc4.backend.domain.store.enums.StatusComparison;

import java.util.Map;

/**
 * 1차 조사(국세청 대조) 결과를 {@code Task.proposedChanges} 모양으로 옮긴다.
 *
 * <p>AI 를 거치지 않는다. 비교 결과가 곧 수정안이라 같은 입력에 항상 같은 값이 나오고,
 * AI 서버가 멈춰 있어도 1차 수정안은 만들 수 있다. 2차 조사(AI)의 수정안과 모양이 같아
 * 담당자 화면은 둘을 구분 없이 받는다.
 */
public final class NtsProposedChanges {

    /** 수정안에서 가게 상태를 가리키는 이름. AI 가 보내는 수정안과 같은 이름을 쓴다. */
    public static final String STATUS_FIELD = "status";

    private NtsProposedChanges() {
    }

    /** 제안할 것이 없으면 빈 Map 이다 ({@link StatusComparison#proposedStatus()} 참고). */
    public static Map<String, Object> from(StatusComparison comparison) {
        return comparison.proposedStatus()
                .<Map<String, Object>>map(status -> Map.of(STATUS_FIELD, status.name()))
                .orElseGet(Map::of);
    }
}
