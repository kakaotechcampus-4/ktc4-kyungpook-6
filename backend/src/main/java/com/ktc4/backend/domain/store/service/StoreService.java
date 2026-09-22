package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.store.dto.StoreCheckResponse;
import com.ktc4.backend.domain.store.dto.StoreResponse;
import com.ktc4.backend.domain.store.dto.StoreWithNtsCheck;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.NtsCheckFilter;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.global.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreService {

    private final StoreRepository storeRepository;

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
     * 가게 정보와 국세청 확인 결과를 나란히 정리해 AI 1차 조사 자료를 만든다.
     *
     * <p>국세청은 이 메서드가 직접 호출하지 않는다. 매일 도는 배치가 확인해 저장해 둔 기록을 읽을 뿐이라,
     * 외부 API 장애나 호출 한도와 무관하게 응답한다. 값이 언제 기준인지는 {@code ntsCheckedAt} 으로 알 수 있다.
     *
     * <p>두 상태가 같은지·어떻게 다른지는 값 비교라 코드가 계산해 담는다. 그 불일치가 실제 매장 폐업을
     * 뜻하는지는 AI 조사에 맡긴다 — 국세청의 폐업은 사업자 기준이라 실제와 다를 수 있다.
     *
     * @param filter 없으면 전체, {@code STATUS_MISMATCH} 는 상태가 다른 가게, {@code DATA_PROBLEM} 은
     *               사업자번호가 없거나 틀린 가게
     * @param page   0부터 시작하는 페이지 번호
     * @param limit  한 페이지당 건수
     * @return storeId 오름차순의 가게별 조사 자료
     */
    public PageResponse<StoreCheckResponse> getNtsChecks(NtsCheckFilter filter, int page, int limit) {
        Pageable pageable = PageRequest.of(page, limit);
        Page<StoreWithNtsCheck> rows = findRows(filter, pageable);

        return PageResponse.of(rows, StoreCheckResponse::from);
    }

    private Page<StoreWithNtsCheck> findRows(NtsCheckFilter filter, Pageable pageable) {
        if (filter == null) {
            return storeRepository.findAllWithNtsCheck(pageable);
        }
        return switch (filter) {
            case STATUS_MISMATCH -> storeRepository.findStatusMismatch(pageable);
            case DATA_PROBLEM -> storeRepository.findDataProblem(pageable);
        };
    }
}
