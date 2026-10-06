package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.store.dto.ExcludedStore;
import com.ktc4.backend.domain.store.dto.InvestigationTargets;
import com.ktc4.backend.domain.store.dto.StoreCheckResponse;
import com.ktc4.backend.domain.store.enums.InvestigationExclusionReason;
import com.ktc4.backend.domain.store.enums.StatusComparison;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 담당자가 고른 가게를 1차 조사(국세청 대조) 결과로 나눈다.
 *
 * <p>국세청과 상태가 다른 가게는 국세청이 답을 이미 줬으므로 1차 수정안으로 끝내고, 상태가 같은 가게만
 * AI 조사로 넘긴다 — 국세청이 "정상"이라 해도 전화번호·주소 같은 변화는 알려 주지 못하기 때문이다.
 *
 * <p>판단은 {@link StoreCheckResponse} 가 계산한 값을 그대로 쓴다. 화면 목록의 {@code statusMismatch} /
 * {@code dataProblem} 과 여기서 나누는 기준이 어긋나지 않게 하기 위해서다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InvestigationTargetSelector {

    private final StoreRepository storeRepository;

    /**
     * @param storeIds 담당자가 고른 가게 번호. 같은 번호가 여러 번 와도 한 번만 다룬다
     * @return 고른 순서를 지킨 세 묶음. 없는 가게 번호가 섞여 있어도 그 가게만 제외하고 나머지는 나눈다
     */
    public InvestigationTargets select(Collection<Long> storeIds) {
        List<Long> ids = storeIds.stream().distinct().toList();
        if (ids.isEmpty()) {
            return new InvestigationTargets(List.of(), List.of(), List.of());
        }

        Map<Long, StoreCheckResponse> checks = storeRepository.findWithNtsCheckByStoreIdIn(ids).stream()
                .map(StoreCheckResponse::from)
                .collect(Collectors.toMap(StoreCheckResponse::storeId, Function.identity()));

        List<StoreCheckResponse> aiTargets = new ArrayList<>();
        List<StoreCheckResponse> resolvedByNts = new ArrayList<>();
        List<ExcludedStore> excluded = new ArrayList<>();
        for (Long storeId : ids) {
            StoreCheckResponse check = checks.get(storeId);
            if (check == null) {
                excluded.add(new ExcludedStore(storeId, InvestigationExclusionReason.STORE_NOT_FOUND));
                continue;
            }
            Optional<InvestigationExclusionReason> reason = exclusionReason(check);
            if (reason.isPresent()) {
                excluded.add(new ExcludedStore(storeId, reason.get()));
            } else if (check.statusMismatch()) {
                resolvedByNts.add(check);
            } else {
                aiTargets.add(check);
            }
        }
        return new InvestigationTargets(
                List.copyOf(aiTargets), List.copyOf(resolvedByNts), List.copyOf(excluded));
    }

    // 번호 문제를 가장 먼저 본다 — 번호가 틀린 가게는 비교 결과가 무엇이든 엉뚱한 사업자의 상태다.
    private static Optional<InvestigationExclusionReason> exclusionReason(StoreCheckResponse check) {
        if (check.dataProblem()) {
            return Optional.of(InvestigationExclusionReason.DATA_PROBLEM);
        }
        if (check.statusComparison() == StatusComparison.NOT_COMPARABLE) {
            return Optional.of(InvestigationExclusionReason.NO_NTS_CHECK);
        }
        if (check.statusComparison() == StatusComparison.MATCH && check.internalStatus() == StoreStatus.CLOSED) {
            return Optional.of(InvestigationExclusionReason.ALREADY_CLOSED);
        }
        return Optional.empty();
    }
}
