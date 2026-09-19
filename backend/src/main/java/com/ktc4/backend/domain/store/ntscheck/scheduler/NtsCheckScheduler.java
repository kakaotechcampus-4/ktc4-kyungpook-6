package com.ktc4.backend.domain.store.ntscheck.scheduler;

import com.ktc4.backend.domain.business.client.NtsClient;
import com.ktc4.backend.domain.business.dto.BusinessStatus;
import com.ktc4.backend.domain.business.dto.NtsBusinessStatus;
import com.ktc4.backend.domain.business.service.BusinessLookupService;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.ntscheck.service.StoreNtsCheckService;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.util.BizNoNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 모든 가게의 국세청 사업자 상태를 하루 한 번 조회해 기록하는 배치.
 *
 * <p>담당자가 화면에서 누를 때마다 외부 API 를 호출하면 국세청 쿼터를 화면 조작에 쓰게 되고,
 * 응답이 느리면 화면도 같이 느려진다. 조회는 새벽에 미리 해 두고 화면은 저장된 결과만 읽도록 나눈다.
 *
 * <p>이 메서드에는 트랜잭션을 걸지 않는다. 외부 API 응답을 기다리는 동안 DB 커넥션을 붙잡지 않기
 * 위해서이며, 저장은 {@link StoreNtsCheckService} 쪽 트랜잭션에서 묶음 단위로 커밋된다.
 *
 * <p>가게 상태({@code Store.status})는 이 배치가 바꾸지 않는다 — 국세청의 폐업은 사업자 기준이라
 * 실제 매장 운영과 다를 수 있어서, 판단은 사람이나 AI 조사에 맡긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NtsCheckScheduler {

    // @Scheduled 의 zone 은 컴파일 상수여야 해서 문자열로 둔다. 실행 시각과 기록 시각이 서로 다른
    // 기준을 쓰지 않도록 한 곳에서만 정의한다.
    private static final String BATCH_ZONE = "Asia/Seoul";

    private final StoreRepository storeRepository;
    private final BusinessLookupService businessLookupService;
    private final StoreNtsCheckService storeNtsCheckService;

    /**
     * 모든 가게를 사업자등록번호 단위로 묶어 국세청 상태를 조회하고 결과를 기록한다.
     *
     * <p>새벽 3시(한국 시간)에 도는 이유는 담당자가 화면을 쓰지 않는 시간대라서다. 서버 시간대에
     * 상관없이 같은 시각에 돌도록 {@code zone} 을 명시한다.
     */
    @Scheduled(cron = "0 0 3 * * *", zone = BATCH_ZONE)
    public void checkAllStores() {
        // 기록 시각은 JVM 기본 시간대로 둔다 — created_at/updated_at 을 채우는 JPA Auditing 과 같은
        // 기준이어야 한 행 안에서 시각끼리 비교가 된다. 실행 시점만 BATCH_ZONE 으로 고정한다.
        LocalDateTime attemptedAt = LocalDateTime.now();
        List<Store> stores = storeRepository.findAll();
        if (stores.isEmpty()) {
            log.info("국세청 상태 조회 배치: 대상 가게가 없어 건너뜀");
            return;
        }

        Map<Boolean, List<Store>> partitioned = stores.stream()
                .collect(Collectors.partitioningBy(NtsCheckScheduler::hasUsableBizNo));
        List<Store> withoutBizNo = partitioned.get(false);
        List<Store> withBizNo = partitioned.get(true);

        // 이 저장이 실패해도 아래 조회는 계속한다 — 번호 없는 가게 때문에 번호 있는 수천 건까지
        // 같이 날리지 않기 위해서다.
        boolean noBizNoSaved = saveChunkResult(
                () -> storeNtsCheckService.markNoBizNo(withoutBizNo, attemptedAt), withoutBizNo.size());

        // 같은 사업자등록번호를 여러 가게가 쓸 수 있어(Store.bizNo 에 유니크 제약 없음) 번호로 묶어
        // 한 번만 조회하고, 그 응답을 그 번호를 쓰는 모든 가게에 똑같이 적는다.
        Map<String, List<Store>> storesByBizNo = withBizNo.stream()
                .collect(Collectors.groupingBy(
                        store -> BizNoNormalizer.normalize(store.getBizNo()),
                        LinkedHashMap::new,
                        Collectors.toList()));
        List<String> bizNos = List.copyOf(storesByBizNo.keySet());

        log.info("국세청 상태 조회 배치 시작 - 가게 {}건, 사업자번호 {}건, 번호 없음 {}건",
                stores.size(), bizNos.size(), withoutBizNo.size());

        int failedChunks = noBizNoSaved ? 0 : 1;
        for (int from = 0; from < bizNos.size(); from += NtsClient.MAX_BATCH_SIZE) {
            List<String> chunk = bizNos.subList(from, Math.min(from + NtsClient.MAX_BATCH_SIZE, bizNos.size()));
            List<Store> chunkStores = chunk.stream()
                    .flatMap(bizNo -> storesByBizNo.get(bizNo).stream())
                    .toList();
            if (!lookupAndRecord(chunk, chunkStores, attemptedAt)) {
                failedChunks++;
            }
        }

        if (failedChunks > 0) {
            // 배치는 끝까지 돌았지만 일부 묶음은 기록되지 않았다는 뜻 — 그 가게들은 last_attempt_at 이
            // 지난 회차 값 그대로라, 이 로그가 없으면 "왜 어제 값이지?"의 답을 찾을 단서가 없다.
            log.warn("국세청 상태 조회 배치 종료 - 가게 {}건 중 저장하지 못한 묶음 {}건",
                    stores.size(), failedChunks);
            return;
        }
        log.info("국세청 상태 조회 배치 종료 - 가게 {}건 처리", stores.size());
    }

    // 한 묶음이 실패해도 그 묶음만 남기고 다음 묶음으로 넘어간다. 한 번의 실패로 나머지 수천 건까지
    // 포기하면 다음 회차까지 하루를 통째로 잃는다. 조회 실패와 저장 실패는 뜻이 다르다 — 조회 실패는
    // "다시 물어보면 될 수도 있다"라서 UNCONFIRMED 로 남기고, 저장 실패는 다시 조회한다고 풀리지
    // 않으므로 아무것도 남기지 못한 채 넘어간다.
    //
    // @return 이 묶음의 결과를 저장까지 마쳤으면 true
    private boolean lookupAndRecord(List<String> bizNos, List<Store> stores, LocalDateTime attemptedAt) {
        List<NtsBusinessStatus> items;
        try {
            items = businessLookupService.getNtsStatuses(bizNos);
        } catch (CustomException e) {
            log.warn("국세청 조회 실패 - 사업자번호 {}건, 가게 {}건, 오류 {}",
                    bizNos.size(), stores.size(), e.getErrorCode());
            return saveChunkResult(() -> storeNtsCheckService.markUnconfirmed(stores, attemptedAt), stores.size());
        }
        Map<String, BusinessStatus> statuses = toStatusesByBizNo(items, Set.copyOf(bizNos));
        return saveChunkResult(() -> storeNtsCheckService.markChecked(stores, statuses, attemptedAt), stores.size());
    }

    // DB 쪽 실패(커넥션 고갈, 제약 위반, 락 타임아웃)를 여기서 끊는다. 그대로 두면 예외가 배치 루프를
    // 뚫고 나가 그 뒤 묶음은 조회조차 안 되는데, @Scheduled 는 예외를 로그 한 줄로 삼켜서 "배치는
    // 돌았는데 절반만 갱신됨" 상태가 조용히 남는다.
    private static boolean saveChunkResult(Runnable save, int storeCount) {
        try {
            save.run();
            return true;
        } catch (RuntimeException e) {
            log.error("국세청 조회 결과 저장 실패 - 가게 {}건, 예외 타입 {}",
                    storeCount, e.getClass().getSimpleName());
            return false;
        }
    }

    // StoreService.collect 와 같은 규칙이다 — checkWithNts 를 이 배치 결과에 연결하는 다음 티켓에서
    // 한쪽이 사라진다. 그 전까지 매칭 규칙을 고칠 일이 생기면 두 곳을 같이 고쳐야 한다.
    //
    // 응답을 순서가 아니라 b_no 로 짝지어 담는다. 순서로 맞추면 응답이 한 건만 밀려도 엉뚱한 가게에
    // 붙는다(국세청 응답이 요청보다 짧게 올 수 있다 — NtsClient 계약 참고).
    private static Map<String, BusinessStatus> toStatusesByBizNo(List<NtsBusinessStatus> items,
                                                                 Set<String> requested) {
        Map<String, BusinessStatus> statuses = new HashMap<>();
        for (NtsBusinessStatus item : items) {
            if (item.bNo() == null || !requested.contains(item.bNo())) {
                log.warn("요청하지 않은 사업자등록번호가 국세청 응답에 있어 건너뜁니다");
                continue;
            }
            try {
                statuses.put(item.bNo(), BusinessStatus.from(item));
            } catch (IllegalArgumentException e) {
                // 해석 못 한 건은 담지 않는다 — 그 가게는 UNCONFIRMED 로 남아 다음 회차에 다시 조회된다.
                log.warn("국세청 응답 해석 실패: {}", e.getMessage());
            }
        }
        return statuses;
    }

    private static boolean hasUsableBizNo(Store store) {
        return BizNoNormalizer.isValid(BizNoNormalizer.normalize(store.getBizNo()));
    }
}
