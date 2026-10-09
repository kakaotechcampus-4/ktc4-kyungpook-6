package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.store.dto.StoreCheckResponse;
import com.ktc4.backend.domain.store.dto.StoreResponse;
import com.ktc4.backend.domain.store.dto.StoreUpdateRequest;
import com.ktc4.backend.domain.store.dto.StoreWithNtsCheck;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.NtsCheckFilter;
import com.ktc4.backend.domain.store.enums.NtsLookupResult;
import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsCheck;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.domain.store.util.StoreNormalizer;
import com.ktc4.backend.global.dto.PageResponse;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreService {

    /** 후보 가게를 이만큼만 보여준다. 흔한 이름으로 신청해도 관리자 화면이 넘치지 않게 하기 위해서다. */
    static final int MAX_OWNER_CANDIDATES = 20;

    /**
     * 후보를 찾을 때 "이 단서로는 찾지 않는다"를 뜻하는 값. 숫자만 남긴 번호에도, 한글·영문·숫자만 남긴
     * 이름에도 나올 수 없는 글자라 어떤 가게와도 맞지 않는다.
     */
    static final String NO_MATCH = "#";

    /** 정규화한 상호명이 이보다 짧으면 이름으로는 후보를 찾지 않는다. */
    public static final int MIN_NAME_LENGTH_FOR_SEARCH = 2;

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
     * <p>두 상태가 같은지·어떻게 다른지는 값 비교라 코드가 계산해 담는다. 불일치는 국세청 상태로 바꾸자는
     * 1차 수정안이 되고, 받아들일지는 담당자가 정한다 — 국세청의 폐업은 사업자 기준이라 실제와 다를 수 있다.
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

    /**
     * 담당자가 수정한 가게 기본 정보를 반영한다. 담아 보낸 필드만 바뀐다.
     *
     * <p>name/addressRoad 가 바뀌면 정규화 값도 함께 다시 계산해 저장한다 — 정규화 책임은
     * 엔티티가 아니라 서비스 계층에 있다({@code 데이터_정규화_가이드.md}).
     *
     * @param storeId 수정할 가게 ID
     * @param request 담아 보낸 필드만 채워진 부분 수정 요청
     * @return 수정된 가게 정보
     * @throws CustomException storeId 에 해당하는 가게가 없으면 {@code STORE_NOT_FOUND}
     */
    @Transactional
    public StoreResponse updateStore(Long storeId, StoreUpdateRequest request) {
        Store store = findStore(storeId);

        String nameNormalized = request.name() == null ? null : StoreNormalizer.normalizeName(request.name());
        String addressNormalized = request.addressRoad() == null ? null
                : StoreNormalizer.normalizeAddress(request.addressRoad());

        store.updateBasicInfo(request.name(), nameNormalized,
                request.addressRoad(), addressNormalized,
                request.phone(), request.status());

        return StoreResponse.from(store);
    }

    /**
     * 가게 엔티티를 찾는다. 다른 도메인(체크인 등)이 가게를 확인할 때 이 메서드를 거친다.
     *
     * @param storeId 찾을 가게 ID
     * @return 가게 엔티티
     * @throws CustomException storeId 에 해당하는 가게가 없으면 {@code STORE_NOT_FOUND}
     */
    public Store findStore(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));
    }

    /**
     * 점주 가입 신청에 연결할 후보 가게를 찾는다. 사업자등록번호가 같은 가게, 점주 휴대폰 번호가 같은 가게,
     * 이름이 겹치는 가게 순서다. 휴대폰 번호는 가게 전화번호가 아니라 가게의 점주 개인 번호와 비교한다.
     *
     * <p>이름은 가게 이름과 같은 규칙으로 정규화해 비교한다. 정규화하고 남는 글자가
     * {@value #MIN_NAME_LENGTH_FOR_SEARCH}자보다 적으면 이름으로는 찾지 않는다 — 빈 값은 모든 가게와,
     * 한 글자는 사실상 모든 가게와 겹쳐 맞는 가게가 건수 제한에 잘린다.
     *
     * <p>비어 있는 단서로는 찾지 않는다. 빈 값으로 찾으면 그 칸이 비어 있는 가게가 전부 후보가 된다.
     * 사업자등록번호는 가입할 때 10자리를 확인하므로 빌 일이 없지만, 다른 단서와 똑같이 막아 둔다.
     *
     * @param bizNo     신청서의 사업자등록번호 (숫자 10자리)
     * @param phone     신청서의 휴대폰 번호 (숫자만). 이 칸이 생기기 전에 가입한 점주는 {@code null}
     * @param storeName 신청서의 상호명
     * @return 후보 가게. 최대 {@value #MAX_OWNER_CANDIDATES}곳
     */
    public List<Store> findOwnerCandidates(String bizNo, String phone, String storeName) {
        String nameNormalized = StoreNormalizer.normalizeName(storeName);
        return storeRepository.findOwnerCandidates(
                orNoMatch(bizNo),
                orNoMatch(phone),
                nameNormalized.length() < MIN_NAME_LENGTH_FOR_SEARCH ? NO_MATCH : nameNormalized,
                MAX_OWNER_CANDIDATES);
    }

    private static String orNoMatch(String clue) {
        return clue == null || clue.isEmpty() ? NO_MATCH : clue;
    }

    /**
     * 가게들의 국세청 상태를 읽는다 — 배치가 마지막으로 확인해 둔 값이다. 국세청을 새로 조회하지 않는다.
     *
     * <p>확인한 적이 없는 가게와, 사업자등록번호가 지워진(NO_BIZ_NO) 가게는 결과에 없다. 뒤의 경우 남아 있는
     * 상태는 옛 번호 기준이라 다른 사업자의 것일 수 있다.
     *
     * @param storeIds 가게 ID 들
     * @return 가게 ID 별 국세청 상태. 알 수 없는 가게는 빠져 있다
     */
    public Map<Long, BusinessState> findNtsStates(Collection<Long> storeIds) {
        if (storeIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, BusinessState> states = new HashMap<>();
        for (StoreWithNtsCheck row : storeRepository.findWithNtsCheckByStoreIdIn(storeIds)) {
            StoreNtsCheck check = row.check();
            if (check != null && check.getNtsState() != null && check.getCheckResult() != NtsLookupResult.NO_BIZ_NO) {
                states.put(row.store().getStoreId(), check.getNtsState());
            }
        }
        return states;
    }

    /**
     * 담당자가 가게 정보를 직접 확인했음을 현재 시각으로 기록한다.
     *
     * @param storeId 확인 완료 처리할 가게 ID
     * @return 확인 시각이 갱신된 가게 정보
     * @throws CustomException storeId 에 해당하는 가게가 없으면 {@code STORE_NOT_FOUND}
     */
    @Transactional
    public StoreResponse confirmStore(Long storeId) {
        return confirmStore(storeId, LocalDateTime.now());
    }

    /**
     * 담당자가 가게 정보를 직접 확인했음을 넘겨받은 시각으로 기록한다. 확인 기록(Verification)과 가게 확인일을
     * 같은 시각으로 맞출 때 쓴다.
     *
     * @param storeId     확인 완료 처리할 가게 ID
     * @param confirmedAt 확인 완료로 기록할 시각
     * @return 확인 시각이 갱신된 가게 정보
     * @throws CustomException storeId 에 해당하는 가게가 없으면 {@code STORE_NOT_FOUND}
     */
    @Transactional
    public StoreResponse confirmStore(Long storeId, LocalDateTime confirmedAt) {
        Store store = findStore(storeId);

        store.confirm(confirmedAt);

        return StoreResponse.from(store);
    }
}
