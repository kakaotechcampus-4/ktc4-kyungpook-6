package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.business.dto.BusinessStatus;
import com.ktc4.backend.domain.business.dto.NtsBusinessStatus;
import com.ktc4.backend.domain.business.service.BusinessLookupService;
import com.ktc4.backend.domain.store.dto.StoreCheckResponse;
import com.ktc4.backend.domain.store.dto.StoreResponse;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.domain.store.util.StoreNormalizer;
import com.ktc4.backend.global.dto.PageResponse;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.util.BizNoNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreService {

    // 국세청 API 가 1회 호출당 받는 최대 건수. 한 묶음이 실패해도 나머지는 살리려고 직접 나눠 호출한다.
    private static final int NTS_BATCH_SIZE = 100;

    private final StoreRepository storeRepository;
    private final BusinessLookupService businessLookupService;

    /**
     * 가게 목록을 storeId 오름차순으로 페이지 단위 조회한다.
     *
     * <p>정렬 키를 storeId 로 고정하는 이유는 새 가게가 등록돼도 앞 페이지 항목이 밀리지 않게 하기 위해서다.
     *
     * @param page  0부터 시작하는 페이지 번호
     * @param limit 한 페이지당 건수
     * @return 가게 목록과 페이지 정보
     */
    public PageResponse<StoreResponse> getStores(int page, int limit) {
        Page<Store> stores = storeRepository.findAll(
                PageRequest.of(page, limit, Sort.by(Sort.Direction.ASC, "storeId")));

        return PageResponse.of(stores, StoreResponse::from);
    }

    /**
     * 가게 정보와 국세청 사업자 상태를 나란히 정리해 AI 1차 조사 자료를 만든다.
     *
     * <p>둘이 같은지·다른지는 판정하지 않는다. 국세청의 폐업은 사업자 기준이라 실제 매장 폐업과 다를 수 있어,
     * 판단은 AI 조사에 맡긴다. 결과는 저장하지 않고 가게 상태도 바꾸지 않는다.
     *
     * <p>외부 API 를 기다리는 동안 DB 커넥션을 붙잡지 않도록 트랜잭션 없이 실행한다.
     *
     * @param storeIds 확인할 가게 ID 목록. 없는 ID 는 결과에서 빠진다
     * @return storeId 오름차순의 가게별 조사 자료
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<StoreCheckResponse> checkWithNts(List<Long> storeIds) {
        List<Store> stores = storeRepository.findAllById(storeIds).stream()
                .sorted(Comparator.comparing(Store::getStoreId))
                .toList();

        // 번호가 없거나 형식이 틀린 가게는 국세청에 물어봐도 소용이 없어 미리 거르고, 중복은 한 번만 조회한다.
        List<String> bizNos = stores.stream()
                .map(store -> BizNoNormalizer.normalize(store.getBizNo()))
                .filter(BizNoNormalizer::isValid)
                .distinct()
                .toList();
        Map<String, BusinessStatus> ntsStatuses = lookupNtsStatuses(bizNos);

        return stores.stream()
                .map(store -> toCheckResponse(store, ntsStatuses))
                .toList();
    }

    /**
     * 모든 가게의 정규화 값을 현재 규칙으로 다시 계산한다.
     *
     * <p>정규화 규칙을 바꿨거나, SQL·CSV 로 직접 넣어 정규화 값이 틀릴 수 있을 때 실행한다.
     * 전체를 한 번에 메모리에 올리므로 가게 수가 수만 건을 넘으면 나눠 처리하도록 바꿔야 한다.
     *
     * @return 정규화 값이 실제로 바뀐 가게 수
     */
    @Transactional
    public int renormalizeAll() {
        int changed = 0;
        for (Store store : storeRepository.findAll()) {
            String bizNo = BizNoNormalizer.normalize(store.getBizNo());
            boolean updated = store.updateNormalized(
                    StoreNormalizer.normalizeName(store.getName()),
                    StoreNormalizer.normalizeAddress(store.getAddressRoad()),
                    bizNo.isEmpty() ? null : bizNo);
            if (updated) {
                changed++;
            }
        }
        return changed;
    }

    // 국세청 조회를 100건씩 나눠 수행한다. 한 묶음이 실패하면 그 묶음의 번호만 결과에서 빠진다.
    private Map<String, BusinessStatus> lookupNtsStatuses(List<String> bizNos) {
        Map<String, BusinessStatus> result = new HashMap<>();
        for (int from = 0; from < bizNos.size(); from += NTS_BATCH_SIZE) {
            List<String> chunk = bizNos.subList(from, Math.min(from + NTS_BATCH_SIZE, bizNos.size()));
            try {
                collect(businessLookupService.getNtsStatuses(chunk), Set.copyOf(chunk), result);
            } catch (CustomException e) {
                log.warn("국세청 조회 실패 ({}건): {}", chunk.size(), e.getErrorCode());
            }
        }
        return result;
    }

    // 응답을 순서가 아니라 b_no 로 짝지어 담는다. 순서로 맞추면 응답이 한 건만 밀려도 엉뚱한 가게에 붙는다.
    private void collect(List<NtsBusinessStatus> items, Set<String> requested, Map<String, BusinessStatus> result) {
        for (NtsBusinessStatus item : items) {
            if (item.bNo() == null || !requested.contains(item.bNo())) {
                log.warn("요청하지 않은 사업자등록번호가 국세청 응답에 있어 건너뜁니다");
                continue;
            }
            try {
                result.put(item.bNo(), BusinessStatus.from(item));
            } catch (IllegalArgumentException e) {
                log.warn("국세청 응답 해석 실패: {}", e.getMessage());
            }
        }
    }

    private StoreCheckResponse toCheckResponse(Store store, Map<String, BusinessStatus> ntsStatuses) {
        BusinessStatus nts = ntsStatuses.get(BizNoNormalizer.normalize(store.getBizNo()));
        if (nts == null) {
            return StoreCheckResponse.of(store, null, null);
        }
        return StoreCheckResponse.of(store, nts.state(), nts.closedAt());
    }
}
