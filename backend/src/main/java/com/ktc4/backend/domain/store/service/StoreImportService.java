package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.store.dto.NtsCheckSeed;
import com.ktc4.backend.domain.store.dto.StoreImportItem;
import com.ktc4.backend.domain.store.dto.StoreImportRequest;
import com.ktc4.backend.domain.store.dto.StoreImportResponse;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.NtsLookupResult;
import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsCheck;
import com.ktc4.backend.domain.store.ntscheck.repository.StoreNtsCheckRepository;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.domain.store.util.StoreNormalizer;
import com.ktc4.backend.global.util.BizNoNormalizer;
import com.ktc4.backend.global.util.PhoneNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 가게 명단을 JSON 으로 받아 넣는다. 배포 환경에 자료가 없어 관리자 프로세스를 끝까지 돌려 볼 수 없는 것을
 * 푸는 경로다(PROMPT-149).
 *
 * <p>DB 에 직접 INSERT 하지 않고 이 경로로 넣는 이유가 두 가지다.
 * <ul>
 *   <li>SQL 로 넣으면 정규화가 적용되지 않는다 — {@code backend/docs/데이터_정규화_가이드.md} 가
 *       적재 경로는 반드시 정규화 클래스를 거치라고 못박아 둔 것이 이 때문이다. 정규화 값이 비면
 *       중복 판별·국세청 대조·점주 후보 찾기가 전부 어긋난다</li>
 *   <li>무엇을 넣었는지 응답으로 남는다. DB 비밀번호를 사람 손으로 돌려쓸 필요도 없어진다</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreImportService {

    static final String REASON_NAME_NOT_COMPARABLE = "가게명을 정규화하면 남는 글자가 없습니다";
    static final String REASON_ADDRESS_NOT_COMPARABLE = "도로명 주소를 정규화하면 남는 글자가 없습니다";
    static final String REASON_NTS_STATUS_REQUIRED = "ntsCheck.lookup 이 CONFIRMED 면 status 가 있어야 합니다";
    static final String REASON_NTS_STATUS_NOT_ALLOWED =
            "ntsCheck.lookup 이 CONFIRMED 가 아니면 status 를 넣을 수 없습니다";
    static final String REASON_NTS_CLOSED_AT_NOT_ALLOWED = "ntsCheck.closedAt 은 status 가 CLOSED 일 때만 넣습니다";

    private final StoreRepository storeRepository;
    private final StoreNtsCheckRepository storeNtsCheckRepository;

    /**
     * 가게 명단을 넣는다. 같은 요청을 두 번 보내도 가게가 두 배로 늘지 않는다.
     *
     * <p>같은 가게인지는 <b>정규화한 이름과 정규화한 주소가 둘 다 같은가</b>로 본다. 사업자등록번호를
     * 쓰지 않는 이유는 명단에 번호가 없는 가게가 많고(경산 232곳 중 상당수), 편의점처럼 한 자리에서
     * 인허가를 여러 개 낸 곳은 번호가 여러 개이기 때문이다. 이름·주소는 모든 줄에 있다.
     *
     * <p>이미 있는 가게는 <b>덮어쓰지 않고 건너뛴다.</b> 담당자가 화면에서 고쳐 둔 값을 명단 재주입이
     * 조용히 되돌리면 안 된다. 고치는 길은 {@code PATCH /api/stores/{storeId}} 다.
     *
     * <p>정규화하면 남는 글자가 없는 이름·주소는 넣지 않는다. 빈 정규화 값은 NOT NULL 을 통과하지만,
     * 점주 후보 찾기의 {@code like '%%'} 가 모든 가게와 맞고 중복 판별도 무의미해진다.
     *
     * <p>{@code ntsCheck} 를 함께 보내면 국세청 확인 기록도 만든다. 그래야
     * {@code GET /api/stores/nts-checks} 가 "아직 확인 안 됨"(UNCONFIRMED)이 아닌 값을 내려주고
     * 조사 화면을 돌려 볼 수 있다. 기록은 엔티티의 {@code applyXxx} 를 거쳐 만든다 — 결과별로
     * 채워야 하는 칸이 다르고, 그 규칙은 배치와 같은 곳에 한 번만 적혀 있어야 한다.
     *
     * <p>건너뛴 가게(이미 있는 가게)에는 {@code ntsCheck} 도 넣지 않는다. 가게를 덮어쓰지 않는 것과
     * 같은 이유다 &mdash; 배치가 실제로 확인해 둔 기록을 명단 재주입이 되돌리면 안 된다.
     *
     * @param request 넣을 가게 목록 (최대 {@value StoreImportRequest#MAX_STORES}건)
     * @return 넣은 건수 · 건너뛴 건수 · 넣지 못한 줄과 이유
     */
    @Transactional
    public StoreImportResponse importStores(StoreImportRequest request) {
        List<StoreImportItem> items = request.stores();
        List<StoreImportResponse.Rejection> rejections = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();

        for (int index = 0; index < items.size(); index++) {
            StoreImportItem item = items.get(index);
            String nameNormalized = StoreNormalizer.normalizeName(item.name());
            String addressNormalized = StoreNormalizer.normalizeAddress(item.addressRoad());

            if (nameNormalized.isBlank()) {
                rejections.add(new StoreImportResponse.Rejection(index, item.name(), REASON_NAME_NOT_COMPARABLE));
                continue;
            }
            if (addressNormalized.isBlank()) {
                rejections.add(new StoreImportResponse.Rejection(index, item.name(), REASON_ADDRESS_NOT_COMPARABLE));
                continue;
            }
            String ntsProblem = validateNtsCheck(item.ntsCheck());
            if (ntsProblem != null) {
                rejections.add(new StoreImportResponse.Rejection(index, item.name(), ntsProblem));
                continue;
            }
            candidates.add(new Candidate(item, new StoreKey(nameNormalized, addressNormalized)));
        }

        Set<StoreKey> takenKeys = findExistingKeys(candidates);
        List<Store> newStores = new ArrayList<>();
        List<Candidate> saved = new ArrayList<>();
        int skipped = 0;
        int bizNoNotTenDigits = 0;

        for (Candidate candidate : candidates) {
            // 이미 DB 에 있거나, 같은 요청 안에서 앞줄이 이미 차지한 가게는 건너뛴다.
            if (!takenKeys.add(candidate.key())) {
                skipped++;
                continue;
            }
            String bizNo = BizNoNormalizer.normalize(candidate.item().bizNo());
            if (!bizNo.isEmpty() && !BizNoNormalizer.isValid(bizNo)) {
                bizNoNotTenDigits++;
            }
            newStores.add(toStore(candidate, bizNo));
            saved.add(candidate);
        }

        storeRepository.saveAll(newStores);
        int ntsChecks = saveNtsChecks(newStores, saved);

        // 명단에는 제3자 가게 정보가 들어 있다 — 건수만 남기고 가게 이름·번호는 로그에 남기지 않는다.
        log.info("가게 자료 주입: 요청 {}건, 넣음 {}건, 건너뜀 {}건, 넣지 못함 {}건, "
                        + "국세청 기록 {}건, 사업자번호가 10자리가 아닌 것 {}건",
                items.size(), newStores.size(), skipped, rejections.size(), ntsChecks, bizNoNotTenDigits);

        return StoreImportResponse.of(items.size(), newStores.size(), skipped, rejections);
    }

    /**
     * 국세청 기록 칸이 서로 모순되지 않는지 본다. 모양이 틀린 값을 그대로 넣으면 조사 화면이
     * 있을 수 없는 상태를 보여준다 — 그래서 저장 전에 거른다.
     *
     * @return 문제가 있으면 거절 사유, 없으면 {@code null}
     */
    private static String validateNtsCheck(NtsCheckSeed seed) {
        if (seed == null) {
            return null;
        }
        boolean confirmed = seed.lookup() == NtsLookupResult.CONFIRMED;
        if (confirmed && seed.status() == null) {
            return REASON_NTS_STATUS_REQUIRED;
        }
        // CONFIRMED 가 아닌 결과는 엔티티가 상태 칸을 건드리지 않는다. 받아 두면 조용히 버려지므로 거절한다.
        if (!confirmed && seed.status() != null) {
            return REASON_NTS_STATUS_NOT_ALLOWED;
        }
        if (seed.closedAt() != null && seed.status() != BusinessState.CLOSED) {
            return REASON_NTS_CLOSED_AT_NOT_ALLOWED;
        }
        return null;
    }

    /**
     * 새로 넣은 가게의 국세청 확인 기록을 만든다. {@code ntsCheck} 를 보내지 않은 가게는 건너뛴다.
     *
     * <p>{@code newStores} 와 {@code saved} 는 같은 순서의 같은 길이다 &mdash; 한쪽은 저장된
     * 엔티티, 한쪽은 그 엔티티를 만든 요청 줄이다.
     *
     * @return 만든 기록 수
     */
    private int saveNtsChecks(List<Store> newStores, List<Candidate> saved) {
        List<StoreNtsCheck> checks = new ArrayList<>();
        for (int i = 0; i < newStores.size(); i++) {
            NtsCheckSeed seed = saved.get(i).item().ntsCheck();
            if (seed == null) {
                continue;
            }
            Store store = newStores.get(i);
            LocalDateTime checkedAt = seed.checkedAt() != null ? seed.checkedAt() : LocalDateTime.now();
            StoreNtsCheck check = StoreNtsCheck.initial(store);
            switch (seed.lookup()) {
                case CONFIRMED -> check.applyConfirmed(store.getBizNo(), seed.status(), seed.closedAt(), checkedAt);
                case UNCONFIRMED -> check.applyUnconfirmed(store.getBizNo(), checkedAt);
                case NO_BIZ_NO -> check.applyNoBizNo(checkedAt);
            }
            checks.add(check);
        }
        storeNtsCheckRepository.saveAll(checks);
        return checks.size();
    }

    /**
     * 요청에 담긴 가게들 중 이미 DB 에 있는 것의 키를 읽는다.
     *
     * <p>정규화한 이름으로 한 번에 읽어 와 주소까지 자바에서 맞춘다. 이름이 같고 주소가 다른 가게
     * (같은 체인의 다른 지점)는 각각 들어가야 하므로 이름만으로 건너뛰면 안 된다.
     */
    private Set<StoreKey> findExistingKeys(List<Candidate> candidates) {
        Set<String> names = new HashSet<>();
        for (Candidate candidate : candidates) {
            names.add(candidate.key().nameNormalized());
        }
        if (names.isEmpty()) {
            return new HashSet<>();
        }
        Set<StoreKey> keys = new HashSet<>();
        for (Store store : storeRepository.findByNameNormalizedIn(names)) {
            keys.add(new StoreKey(store.getNameNormalized(), store.getAddressNormalized()));
        }
        return keys;
    }

    private static Store toStore(Candidate candidate, String bizNo) {
        StoreImportItem item = candidate.item();
        String ownerPhone = PhoneNormalizer.normalize(item.ownerPhone());

        return Store.builder()
                .name(item.name())
                .nameNormalized(candidate.key().nameNormalized())
                .addressRoad(item.addressRoad())
                .addressNormalized(candidate.key().addressNormalized())
                .lat(item.lat())
                .lng(item.lng())
                .status(item.status())
                .category(blankToNull(item.category()))
                // 가게 전화번호는 명단 표기를 그대로 둔다 — 담당자가 보고 거는 번호다.
                .phone(blankToNull(item.phone()))
                // 점주 개인 번호는 가입 신청서의 번호와 맞춰 보는 값이라 숫자만 남긴다.
                .ownerPhone(blankToNull(ownerPhone))
                .bizNo(blankToNull(bizNo))
                // 명단에서 온 가게는 담당자가 확인한 적이 없다 — 확인일을 비워 둬야 재조사 대상에 들어온다.
                .lastCheckedAt(null)
                .build();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 같은 가게인지 보는 키 — 정규화한 이름과 주소의 짝. */
    private record StoreKey(String nameNormalized, String addressNormalized) {
    }

    /** 정규화까지 끝난 한 줄. */
    private record Candidate(StoreImportItem item, StoreKey key) {
    }
}
