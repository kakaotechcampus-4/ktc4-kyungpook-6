package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.business.client.NtsClient;
import com.ktc4.backend.domain.business.dto.BusinessStatus;
import com.ktc4.backend.domain.business.dto.NtsBusinessStatus;
import com.ktc4.backend.domain.business.service.BusinessLookupService;
import com.ktc4.backend.domain.store.dto.StoreCheckResponse;
import com.ktc4.backend.domain.store.dto.StoreResponse;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.NtsLookupResult;
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
     * <p>두 상태가 같은지·어떻게 다른지는 값 비교라 코드가 계산해 담는다({@code StatusComparison}).
     * 그 불일치가 실제 매장 폐업을 뜻하는지는 AI 조사에 맡긴다. 국세청의 폐업은 사업자 기준이라 실제와 다를 수 있다.
     * 결과는 저장하지 않고 가게 상태도 바꾸지 않는다.
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

    // 국세청 조회를 API 한도(NtsClient.MAX_BATCH_SIZE)만큼씩 나눠 수행한다. 한 묶음이 실패하면 그 묶음의 번호만 결과에서 빠지고,
    // 해당 가게는 toCheckResponse 에서 UNCONFIRMED 로 기록된다.
    private Map<String, BusinessStatus> lookupNtsStatuses(List<String> bizNos) {
        Map<String, BusinessStatus> result = new HashMap<>();
        for (int from = 0; from < bizNos.size(); from += NtsClient.MAX_BATCH_SIZE) {
            List<String> chunk = bizNos.subList(from,
                    Math.min(from + NtsClient.MAX_BATCH_SIZE, bizNos.size()));
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

    // 조회하지 않은 가게(NO_BIZ_NO)와 조회했지만 답을 못 받은 가게(UNCONFIRMED)를 구분해 둔다.
    // 앞은 데이터를 고쳐야 하고, 뒤는 다시 조회하면 확인될 수 있어서 대응이 다르다.
    private StoreCheckResponse toCheckResponse(Store store, Map<String, BusinessStatus> ntsStatuses) {
        String bizNo = BizNoNormalizer.normalize(store.getBizNo());
        if (!BizNoNormalizer.isValid(bizNo)) {
            return StoreCheckResponse.of(store, NtsLookupResult.NO_BIZ_NO, null);
        }
        BusinessStatus nts = ntsStatuses.get(bizNo);
        if (nts == null) {
            return StoreCheckResponse.of(store, NtsLookupResult.UNCONFIRMED, null);
        }
        return StoreCheckResponse.of(store, NtsLookupResult.CONFIRMED, nts);
    }
}
