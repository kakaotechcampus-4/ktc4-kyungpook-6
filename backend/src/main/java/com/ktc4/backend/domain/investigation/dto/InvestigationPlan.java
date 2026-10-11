package com.ktc4.backend.domain.investigation.dto;

import java.util.List;

/**
 * 실행기에 넘기는 조사 한 건의 할 일. 실행기는 다른 스레드에서 돌아 영속성 컨텍스트가 없으므로 엔티티가 아니라 값만 담는다.
 *
 * <p>대상 수({@code Job.targetCount})는 두 목록을 합친 수다 — 제외된 가게는 들어 있지 않다.
 *
 * @param jobId      조사 ID
 * @param ntsTargets AI 없이 1차 수정안을 저장할 가게 (먼저 처리)
 * @param aiTargets  AI 에 조사시킬 가게
 */
public record InvestigationPlan(Long jobId, List<NtsTarget> ntsTargets, List<InvestigationTarget> aiTargets) {

    public InvestigationPlan {
        ntsTargets = List.copyOf(ntsTargets);
        aiTargets = List.copyOf(aiTargets);
    }

    /** 처리할 가게 수 — {@code Job.targetCount} 와 같다. */
    public int targetCount() {
        return ntsTargets.size() + aiTargets.size();
    }
}
