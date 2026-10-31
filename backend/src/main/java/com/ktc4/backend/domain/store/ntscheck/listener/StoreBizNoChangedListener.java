package com.ktc4.backend.domain.store.ntscheck.listener;

import com.ktc4.backend.domain.business.dto.BusinessStatus;
import com.ktc4.backend.domain.business.dto.NtsBusinessStatus;
import com.ktc4.backend.domain.business.service.BusinessLookupService;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.event.StoreBizNoChanged;
import com.ktc4.backend.domain.store.ntscheck.service.StoreNtsCheckService;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.global.config.AsyncConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 사업자등록번호를 고치면 그 한 건만 국세청에 바로 다시 묻는다.
 *
 * <p>전에는 새벽 배치(3시)만 채워서, 오타를 고친 담당자가 결과를 <b>하루 뒤에야</b> 봤다. 조회를
 * 아끼려던 것이었는데, 그 한도는 상호 검색(비즈노) 쪽이고 배치가 쓰는 국세청 상태조회와는 다른
 * 경로였다(11주차 멘토 리뷰 🔴 5). 한 건을 더 묻는 비용이 하루를 기다리는 값보다 싸다.
 *
 * <p><b>커밋된 뒤에 돈다</b>({@code AFTER_COMMIT}). 커밋 전에 조회하면 아직 저장되지 않은 번호를
 * 읽을 수 있고, 외부 호출이 느린 만큼 수정 트랜잭션을 붙잡는다. 수정 요청은 조회를 기다리지 않고
 * 바로 끝난다({@code @Async}).
 *
 * <p><b>실패하면 아무것도 쓰지 않는다.</b> 수정 경로가 이미 확인 기록을 되돌려 "확인 대기" 로
 * 비워 두었으므로, 그대로 두면 화면이 사실대로 보이고 새벽 배치가 다음 회차에 채운다. 여기서 억지로
 * 무언가 적으면 조회한 적 없는 상태를 조회한 것처럼 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoreBizNoChangedListener {

    private final BusinessLookupService businessLookupService;
    private final StoreNtsCheckService storeNtsCheckService;
    private final StoreRepository storeRepository;

    @Async(AsyncConfig.NTS_RECHECK_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void recheck(StoreBizNoChanged event) {
        Store store = storeRepository.findById(event.storeId()).orElse(null);
        if (store == null) {
            // 고친 직후 지워졌다. 조회할 대상이 없다
            return;
        }

        BusinessStatus status;
        try {
            status = lookUp(event.bizNo());
        } catch (RuntimeException e) {
            // 외부 호출·해석 실패. 비워 둔 채로 두고 배치에 맡긴다
            log.warn("사업자등록번호 수정 뒤 국세청 재조회 실패 - 가게 {}, 예외 {}",
                    event.storeId(), e.getClass().getSimpleName());
            return;
        }

        LocalDateTime attemptedAt = LocalDateTime.now();
        try {
            // 답을 못 받았으면 빈 map 이고, 그러면 markChecked 가 "확인 대기" 로 적는다
            Map<String, BusinessStatus> statuses = status == null
                    ? Map.of() : Map.of(event.bizNo(), status);
            storeNtsCheckService.markChecked(List.of(store), statuses, attemptedAt);
        } catch (RuntimeException e) {
            log.error("사업자등록번호 수정 뒤 국세청 재조회 결과 저장 실패 - 가게 {}, 예외 {}",
                    event.storeId(), e.getClass().getSimpleName());
        }
    }

    /**
     * 그 번호 하나의 국세청 상태. 응답에 없거나 해석하지 못하면 {@code null}.
     *
     * <p>응답을 순서가 아니라 {@code b_no} 로 짝지어 고른다 — 국세청 응답은 요청보다 짧게 오거나
     * 요청하지 않은 번호를 담을 수 있다({@code NtsClient} 계약).
     */
    private BusinessStatus lookUp(String bizNo) {
        List<NtsBusinessStatus> items = businessLookupService.getNtsStatuses(List.of(bizNo));
        for (NtsBusinessStatus item : items) {
            if (!bizNo.equals(item.bNo())) {
                continue;
            }
            try {
                return BusinessStatus.from(item);
            } catch (IllegalArgumentException e) {
                log.warn("국세청 응답 해석 실패: {}", e.getMessage());
                return null;
            }
        }
        return null;
    }
}
