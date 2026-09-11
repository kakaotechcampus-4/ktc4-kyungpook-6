package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.store.dto.StoreResponse;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.global.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
}
