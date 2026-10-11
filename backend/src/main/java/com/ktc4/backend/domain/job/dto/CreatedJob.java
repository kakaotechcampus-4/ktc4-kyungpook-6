package com.ktc4.backend.domain.job.dto;

import com.ktc4.backend.domain.investigation.dto.InvestigationPlan;
import com.ktc4.backend.domain.store.dto.ExcludedStore;

import java.util.List;

/**
 * 조사를 만든 결과. 컨트롤러가 응답을 만들고, 저장(커밋)이 끝난 뒤 {@code plan} 으로 실행기를 부른다.
 *
 * @param jobId       만든 조사 ID
 * @param targetCount 처리할 가게 수 (1차 대상 + AI 대상)
 * @param excluded    조사에서 뺀 가게와 이유
 * @param plan        실행기에 넘길 할 일
 */
public record CreatedJob(Long jobId, int targetCount, List<ExcludedStore> excluded, InvestigationPlan plan) {

    public CreatedJob {
        excluded = List.copyOf(excluded);
    }
}
