package com.ktc4.backend.domain.store.ntscheck.service;

import com.ktc4.backend.domain.business.dto.BusinessStatus;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsChange;
import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsCheck;
import com.ktc4.backend.domain.store.ntscheck.repository.StoreNtsChangeRepository;
import com.ktc4.backend.domain.store.ntscheck.repository.StoreNtsCheckRepository;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.global.util.BizNoNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 국세청 조회 결과를 가게별 기록({@link StoreNtsCheck})과 변경 이력({@link StoreNtsChange})에 저장한다.
 *
 * <p>메서드 하나가 트랜잭션 하나다. 배치는 100건 단위 묶음마다 이 서비스를 한 번씩 부르므로,
 * 한 묶음이 실패해도 앞서 커밋된 묶음은 그대로 남는다.
 *
 * <p>가게의 실제 상태({@code Store.status})나 검증 시각({@code Store.lastCheckedAt})은 절대 바꾸지
 * 않는다 — 국세청의 폐업은 사업자 기준이라 실제 매장 운영과 다를 수 있어, 사람이나 AI 조사를
 * 거치지 않고 가게 정보를 자동으로 고치면 안 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreNtsCheckService {

    private final StoreNtsCheckRepository storeNtsCheckRepository;
    private final StoreNtsChangeRepository storeNtsChangeRepository;
    private final StoreRepository storeRepository;

    /**
     * 국세청 조회에 성공한 묶음의 결과를 기록한다.
     *
     * <p>응답에 없는 가게는 조회는 했으나 답을 못 받은 것이므로 {@code UNCONFIRMED} 로 남기고,
     * 이전에 확인해 둔 국세청 상태는 지우지 않는다. 확인된 가게 중 국세청 상태가 이전과 달라진
     * 건만 변경 이력을 추가한다(처음 확인한 경우도 "이전 상태 없음 → 현재" 로 남는다).
     *
     * @param stores          이 묶음에 속한 가게 목록. 비어 있으면 아무것도 하지 않는다
     * @param statusesByBizNo 사업자등록번호(하이픈 없는 숫자)별 국세청 상태
     * @param attemptedAt     이번 배치 회차의 시각
     */
    @Transactional
    public void markChecked(List<Store> stores, Map<String, BusinessStatus> statusesByBizNo,
                            LocalDateTime attemptedAt) {
        if (stores.isEmpty()) {
            return;
        }

        Map<Long, StoreNtsCheck> existingChecks = findExistingChecks(stores);
        List<StoreNtsCheck> checks = new ArrayList<>();
        List<StoreNtsChange> changes = new ArrayList<>();

        for (Store store : stores) {
            String bizNo = BizNoNormalizer.normalize(store.getBizNo());
            StoreNtsCheck check = findOrCreate(existingChecks, store);
            BusinessStatus status = statusesByBizNo.get(bizNo);

            if (status == null) {
                check.applyUnconfirmed(bizNo, attemptedAt);
            } else {
                if (check.getNtsState() != status.state()) {
                    changes.add(StoreNtsChange.builder()
                            .store(check.getStore())
                            .fromState(check.getNtsState())
                            .toState(status.state())
                            .detectedAt(attemptedAt)
                            .build());
                }
                check.applyConfirmed(bizNo, status.state(), status.closedAt(), attemptedAt);
            }
            checks.add(check);
        }

        storeNtsCheckRepository.saveAll(checks);
        if (!changes.isEmpty()) {
            storeNtsChangeRepository.saveAll(changes);
            log.info("국세청 상태 변경 {}건을 이력에 기록함", changes.size());
        }
    }

    /**
     * 묶음 전체의 국세청 조회가 실패했을 때 그 사실만 기록한다.
     *
     * <p>결과가 하나도 없는 조회와 같은 처리라 {@link #markChecked} 에 빈 결과를 넘긴 것과 같다.
     * 이전 국세청 상태와 마지막 성공 시각은 보존된다.
     *
     * @param stores      실패한 묶음에 속한 가게 목록
     * @param attemptedAt 이번 배치 회차의 시각
     */
    @Transactional
    public void markUnconfirmed(List<Store> stores, LocalDateTime attemptedAt) {
        markChecked(stores, Map.of(), attemptedAt);
    }

    /**
     * 사업자등록번호가 없거나 형식이 틀려 조회하지 않은 가게들을 기록한다.
     *
     * <p>조회를 아예 하지 않았으므로 국세청 관련 값은 건드리지 않고 조회 결과 구분만 바꾼다.
     * {@code UNCONFIRMED}(다시 조회하면 확인될 수 있음)와 달리 이쪽은 데이터를 고쳐야 하는 건이라
     * 따로 구분한다.
     *
     * @param stores      사업자등록번호를 쓸 수 없는 가게 목록. 비어 있으면 아무것도 하지 않는다
     * @param attemptedAt 이번 배치 회차의 시각
     */
    @Transactional
    public void markNoBizNo(List<Store> stores, LocalDateTime attemptedAt) {
        if (stores.isEmpty()) {
            return;
        }

        Map<Long, StoreNtsCheck> existingChecks = findExistingChecks(stores);
        List<StoreNtsCheck> checks = stores.stream()
                .map(store -> {
                    StoreNtsCheck check = findOrCreate(existingChecks, store);
                    check.applyNoBizNo(attemptedAt);
                    return check;
                })
                .toList();

        storeNtsCheckRepository.saveAll(checks);
    }

    private Map<Long, StoreNtsCheck> findExistingChecks(List<Store> stores) {
        List<Long> storeIds = stores.stream().map(Store::getStoreId).toList();
        return storeNtsCheckRepository.findAllById(storeIds).stream()
                .collect(Collectors.toMap(StoreNtsCheck::getStoreId, Function.identity()));
    }

    /**
     * 이미 있는 기록을 찾고, 없으면 새로 만든다.
     *
     * <p>새로 만들 때 배치가 넘겨준 {@code store} 를 그대로 쓰지 않고 {@code getReferenceById} 로
     * 바꾸는 이유: 배치는 트랜잭션 밖에서 가게를 읽어 오므로 그 객체는 영속성 컨텍스트에서 분리된
     * (detached) 상태다. {@link StoreNtsCheck} 는 {@code @MapsId} 공유 PK 라 Hibernate 가 저장 시
     * 연관된 Store 까지 persist 대상으로 훑는데, 거기에 detached 객체가 있으면
     * {@code EntityExistsException: detached entity passed to persist} 로 저장 자체가 실패한다.
     * {@code getReferenceById} 는 DB 를 치지 않고 현재 트랜잭션에 붙은 프록시만 만들어 준다.
     */
    private StoreNtsCheck findOrCreate(Map<Long, StoreNtsCheck> existingChecks, Store store) {
        StoreNtsCheck existing = existingChecks.get(store.getStoreId());
        if (existing != null) {
            return existing;
        }
        return StoreNtsCheck.initial(storeRepository.getReferenceById(store.getStoreId()));
    }
}
