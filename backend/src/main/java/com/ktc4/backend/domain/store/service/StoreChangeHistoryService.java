package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.store.dto.StoreChange;
import com.ktc4.backend.domain.store.dto.StoreSnapshot;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.entity.StoreChangeHistory;
import com.ktc4.backend.domain.store.enums.StoreChangeField;
import com.ktc4.backend.domain.store.repository.StoreBusinessHourRepository;
import com.ktc4.backend.domain.store.repository.StoreChangeHistoryRepository;
import com.ktc4.backend.domain.store.repository.StoreMenuRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 가게 정보가 바뀐 기록을 남긴다. 가게를 바꾸는 서비스는 바꾸기 전과 뒤에 {@link #snapshot} 을 하나씩 찍고
 * {@link #recordChanges} 로 넘긴다 — 여섯 항목의 비교가 여기 한 곳에서 일어나, 경로마다 일부 항목을 빠뜨릴 수 없다.
 *
 * <p>가게를 바꾸는 트랜잭션 안에서만 부른다({@code MANDATORY}). 그 트랜잭션이 되돌려지면 기록도 함께 사라진다 —
 * 일어나지 않은 변경이 기록으로 남지 않게.
 *
 * <p>스냅숏이 맞으려면 호출자가 가게를 잠근 뒤에 찍어야 한다({@link StoreService#findStoreForUpdate}). 영업시간·메뉴를
 * 쓰는 곳은 모두 가게를 먼저 잠그므로, 잠금 뒤에 읽은 값은 그 사이 다른 저장이 끼어들지 않은 최신 값이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class StoreChangeHistoryService {

    private final StoreBusinessHourRepository businessHourRepository;
    private final StoreMenuRepository menuRepository;
    private final StoreChangeHistoryRepository historyRepository;

    /**
     * 가게 정보의 지금 모습을 찍는다. 아직 내보내지 않은 쓰기는 조회 전에 DB 로 내보내진다.
     *
     * <p>영업시간은 엔티티가 아니라 값만 읽는다 — 운영 정보 저장이 영업시간을 벌크 삭제로 지우는데, 그 전에 영업시간 엔티티를
     * 영속성 컨텍스트에 올려 두면 안 된다({@code StoreBusinessHourRepository#deleteAllByStoreId}).
     *
     * @param store 잠근 가게
     * @return 항목마다 JSON 문자열로 굳힌 스냅숏
     */
    public StoreSnapshot snapshot(Store store) {
        return StoreSnapshot.of(store,
                businessHourRepository.findMinutesByStoreId(store.getStoreId()),
                menuRepository.findByStoreStoreIdAndDeletedAtIsNullOrderByMenuIdAsc(store.getStoreId()));
    }

    /**
     * 두 스냅숏을 항목마다 비교해 달라진 항목만 한 줄씩 남긴다. 하나도 다르지 않으면 아무것도 남기지 않는다.
     *
     * @param store  가게
     * @param before 바꾸기 전 스냅숏
     * @param after  바꾼 뒤 스냅숏
     * @param change 어느 경로로·누가·언제
     */
    public void recordChanges(Store store, StoreSnapshot before, StoreSnapshot after, StoreChange change) {
        List<StoreChangeHistory> histories = new ArrayList<>();
        for (StoreChangeField field : StoreChangeField.values()) {
            String beforeValue = before.valueOf(field);
            String afterValue = after.valueOf(field);
            if (!Objects.equals(beforeValue, afterValue)) {
                histories.add(StoreChangeHistory.of(store, field, change, beforeValue, afterValue));
            }
        }
        if (histories.isEmpty()) {
            return;
        }
        historyRepository.saveAll(histories);
        log.info("가게 변경 기록 - storeId={}, source={}, changedBy={}, 항목={}", store.getStoreId(), change.source(),
                change.changedBy(), histories.stream().map(StoreChangeHistory::getField).toList());
    }
}
