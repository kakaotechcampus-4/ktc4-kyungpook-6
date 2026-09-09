package com.ktc4.backend.store;

import com.ktc4.backend.common.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가게 정보 조회 API.
 */
@RestController
@RequestMapping("/api/stores")
@RequiredArgsConstructor
public class StoreController {

    /** 한 번에 가져갈 수 있는 최대 건수. 상한이 없으면 limit 를 크게 넣어 전체를 긁어갈 수 있다. */
    private static final int MAX_LIMIT = 100;

    private final StoreRepository storeRepository;

    /**
     * 가게 목록을 페이지 단위로 조회한다.
     *
     * @param page  0부터 시작하는 페이지 번호
     * @param limit 한 페이지당 건수 (1 ~ {@value #MAX_LIMIT}, 범위를 벗어나면 잘라낸다)
     */
    @GetMapping
    public PageResponse<StoreResponse> getStores(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int limit) {

        int safePage = Math.max(page, 0);
        int safeLimit = Math.clamp(limit, 1, MAX_LIMIT);

        Page<Store> stores = storeRepository.findAll(
                PageRequest.of(safePage, safeLimit, Sort.by(Sort.Direction.ASC, "storeId")));

        return PageResponse.of(stores, StoreResponse::from);
    }
}
