package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.checkin.repository.CheckInRepository;
import com.ktc4.backend.domain.job.enums.JobStatus;
import com.ktc4.backend.domain.member.repository.StoreOwnerRepository;
import com.ktc4.backend.domain.signal.repository.SignalRepository;
import com.ktc4.backend.domain.store.dto.StoreDeleteResponse;
import com.ktc4.backend.domain.store.dto.StoreDeletionRequest;
import com.ktc4.backend.domain.store.dto.StoreDeletionResponse;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.ntscheck.repository.StoreNtsChangeRepository;
import com.ktc4.backend.domain.store.ntscheck.repository.StoreNtsCheckRepository;
import com.ktc4.backend.domain.store.repository.StoreBusinessHourRepository;
import com.ktc4.backend.domain.store.repository.StoreMenuRepository;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.domain.task.repository.TaskRepository;
import com.ktc4.backend.domain.verification.repository.VerificationRepository;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 가게를 지운다. 넣는 길({@link StoreImportService})과 고치는 길({@code PATCH /api/stores/{storeId}})만
 * 있어서 <b>되돌릴 수 없던 것</b>을 푸는 경로다(PROMPT-157).
 *
 * <p>DB 에 직접 {@code DELETE} 하지 않고 이 경로로 지우는 이유는 넣을 때와 같다 — 무엇을 지웠는지
 * 응답으로 남고, DB 비밀번호를 사람 손으로 돌려쓸 필요가 없다. 거기에 하나 더 있다. 가게를 가리키는
 * 표가 여덟 개인데, {@code Store} 엔티티에는 {@code @OneToMany} 가 없어 cascade 가 걸리지 않는다.
 * 손으로 지우면 외래키 순서를 틀려 절반만 지워진 상태가 남는다.
 *
 * <h2>지우는 순서</h2>
 * <pre>
 * 신호 → 확인 기록 → 조사 결과 → 메뉴 · 영업시간 · 국세청 변경 이력 · 국세청 확인 · 점주 연결 → 가게
 * </pre>
 * 신호는 조사 결과를, 확인 기록은 가게와 조사 결과를 함께 가리킨다. 그래서 둘을 조사 결과보다 먼저
 * 지운다. 조사({@code job}) 자체는 남긴다 — 어떤 조사를 언제 돌렸는지는 가게와 별개의 기록이다.
 *
 * <h2>지우지 않고 거절하는 경우</h2>
 * 실제 활동이 남은 가게는 그냥 지우지 않는다. 다만 <b>코드가 끝까지 막지는 않는다</b> —
 * 관리자는 보통 그 리스크를 알고 지운다. 그래서 둘로 나눈다(PR #113 리뷰에서 정했다).
 *
 * <table>
 *   <caption>거절 기준</caption>
 *   <tr><th>무엇</th><th>{@code force} 로 넘길 수 있나</th><th>왜</th></tr>
 *   <tr><td>점주가 연결돼 있음</td><td>넘길 수 있다</td>
 *       <td>점주 앱에서 그 가게가 사라진다. 관리자가 알고 지우는 일이다</td></tr>
 *   <tr><td>끝나지 않은 조사에 들어 있음</td><td>넘길 수 있다</td>
 *       <td>돌고 있는 조사의 대상이 없어진다. 같은 이유로 관리자의 판단에 맡긴다</td></tr>
 *   <tr><td>방문(체크인) 기록이 있음</td><td><b>넘길 수 없다</b></td>
 *       <td>아동이 실제로 다녀간 기록인데 <b>보관할 자리가 아직 없다.</b> 지우면 영구히 사라진다 —
 *           보관 방법을 정하기 전까지는 지우는 쪽이 더 위험하다</td></tr>
 * </table>
 *
 * <p>{@code force} 는 "지울 수 있게 해 달라"가 아니라 <b>"그 리스크를 알고 있다"는 표시</b>다.
 * 기본값은 꺼짐이라, 모르고 지우는 일은 여전히 한 번 막힌다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreDeletionService {

    /** 끝나지 않은 것으로 보는 조사 상태. 이 중 하나라도 걸리면 그 가게는 지우지 않는다. */
    private static final Set<JobStatus> UNFINISHED_JOBS = Set.of(JobStatus.PENDING, JobStatus.IN_PROGRESS);

    private final StoreRepository storeRepository;
    private final StoreNtsCheckRepository storeNtsCheckRepository;
    private final StoreNtsChangeRepository storeNtsChangeRepository;
    private final StoreMenuRepository storeMenuRepository;
    private final StoreBusinessHourRepository storeBusinessHourRepository;
    private final StoreOwnerRepository storeOwnerRepository;
    private final CheckInRepository checkInRepository;
    private final TaskRepository taskRepository;
    private final VerificationRepository verificationRepository;
    private final SignalRepository signalRepository;

    /**
     * 가게 한 곳을 딸린 기록과 함께 지운다.
     *
     * @param storeId 지울 가게 ID
     * @return 지운 가게와, 함께 지운 기록의 줄 수
     * @throws CustomException 가게가 없으면 {@code STORE_NOT_FOUND},
     *                         점주가 연결돼 있으면 {@code STORE_HAS_OWNER},
     *                         끝나지 않은 조사에 들어 있으면 {@code STORE_UNDER_INVESTIGATION},
     *                         방문 기록이 있으면 {@code STORE_HAS_CHECK_IN}
     */
    @Transactional
    public StoreDeleteResponse delete(Long storeId, boolean force) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));
        refusalOf(storeId, force).ifPresent(code -> {
            throw new CustomException(code);
        });
        return deleteStore(store);
    }

    /**
     * 가게 여러 곳을 지운다. 한 곳이 막혀도 나머지는 지운다.
     *
     * <p>한 트랜잭션에서 돈다. 중간에 예상 못 한 오류가 나면 전부 되돌아간다 — 절반만 지워진 상태로
     * 끝나는 쪽이 더 나쁘다. 반면 <b>거절</b>(점주 연결 등)은 오류가 아니라 결과로 담는다.
     *
     * <p>이미 없는 ID 는 오류로 보지 않는다. 같은 요청을 두 번 보내도 두 번째가 404 로 끊기지 않게
     * 하려는 것이다 — 넣는 쪽이 두 번 보내도 안전한 것과 짝을 맞춘다.
     *
     * @param request 지울 가게 ID 목록
     * @return 건수와, 지운 가게·거절한 가게의 내역
     */
    @Transactional
    public StoreDeletionResponse deleteAll(StoreDeletionRequest request) {
        Set<Long> storeIds = new LinkedHashSet<>(request.storeIds());
        List<StoreDeleteResponse> results = new ArrayList<>();
        List<StoreDeletionResponse.Refusal> refusals = new ArrayList<>();
        int notFound = 0;

        for (Long storeId : storeIds) {
            Optional<Store> found = storeRepository.findById(storeId);
            if (found.isEmpty()) {
                notFound++;
                continue;
            }
            Store store = found.get();
            Optional<ErrorCode> refusal = refusalOf(storeId, request.forceOrDefault());
            if (refusal.isPresent()) {
                refusals.add(new StoreDeletionResponse.Refusal(storeId, store.getName(), refusal.get().getTitle()));
                continue;
            }
            results.add(deleteStore(store));
        }

        log.info("가게 삭제 - 요청 {}건, 지움 {}건, 없음 {}건, 거절 {}건, force={}",
                storeIds.size(), results.size(), notFound, refusals.size(), request.forceOrDefault());
        return StoreDeletionResponse.of(storeIds.size(), notFound, results, refusals);
    }

    /**
     * 지우면 안 되는 이유를 찾는다. <b>먼저 걸린 하나</b>를 돌려준다 — 고쳐야 할 일이 여러 개여도
     * 한 번에 하나씩 알려 주는 편이 메시지가 분명하다.
     *
     * <p>체크인을 맨 먼저 보는 이유는 그것만 {@code force} 로 넘길 수 없기 때문이다. 나중에 보면
     * {@code force=true} 로 보낸 사람이 "점주 연결 때문이구나" 하고 연결을 끊은 뒤 다시 보냈다가
     * 그제서야 체크인에 막힌다 — 한 번에 알려 주는 편이 낫다.
     *
     * @param storeId 가게 ID
     * @param force   점주 연결·진행 중 조사를 넘길지
     * @return 지우면 안 되는 이유. 지워도 되면 빈 값
     */
    private Optional<ErrorCode> refusalOf(Long storeId, boolean force) {
        if (checkInRepository.existsByStore_StoreId(storeId)) {
            return Optional.of(ErrorCode.STORE_HAS_CHECK_IN);
        }
        if (force) {
            return Optional.empty();
        }
        if (storeOwnerRepository.existsLinkedByStoreId(storeId)) {
            return Optional.of(ErrorCode.STORE_HAS_OWNER);
        }
        if (taskRepository.existsByStoreIdAndJobStatusIn(storeId, UNFINISHED_JOBS)) {
            return Optional.of(ErrorCode.STORE_UNDER_INVESTIGATION);
        }
        return Optional.empty();
    }

    /**
     * 딸린 기록을 외래키 순서대로 지우고 가게를 지운다. 거절 조건은 이미 확인된 상태로 들어온다.
     *
     * @param store 지울 가게
     * @return 지운 가게와, 함께 지운 기록의 줄 수
     */
    private StoreDeleteResponse deleteStore(Store store) {
        Long storeId = store.getStoreId();
        String name = store.getName();

        List<Long> taskIds = taskRepository.findTaskIdsByStoreId(storeId);
        int signals = taskIds.isEmpty() ? 0 : signalRepository.deleteAllByTaskIds(taskIds);
        int verifications = verificationRepository.deleteAllByStoreId(storeId);
        if (!taskIds.isEmpty()) {
            verifications += verificationRepository.deleteAllByTaskIds(taskIds);
        }
        int tasks = taskRepository.deleteAllByStoreId(storeId);
        int menus = storeMenuRepository.deleteAllByStoreId(storeId);
        int businessHours = storeBusinessHourRepository.deleteAllByStoreId(storeId);
        int ntsChanges = storeNtsChangeRepository.deleteAllByStoreId(storeId);
        int ntsChecks = storeNtsCheckRepository.deleteAllByStoreId(storeId);
        // force 로 지울 때만 0보다 크다. 끊긴 연결 행도 함께 지운다 — 가게가 사라지면 가리킬 곳이 없다.
        int ownerLinks = storeOwnerRepository.deleteAllByStoreId(storeId);

        // 위의 @Modifying 질의는 영속성 컨텍스트를 거치지 않는다. 가게를 지우기 전에 비워 두지 않으면
        // 읽어 둔 Store 를 지울 때 Hibernate 가 이미 사라진 자식을 다시 손대려 할 수 있다.
        storeRepository.delete(store);
        storeRepository.flush();

        if (ownerLinks > 0) {
            log.warn("가게 삭제 - 점주 연결을 함께 끊었다. storeId={}, 연결 {}건", storeId, ownerLinks);
        }
        log.info("가게 삭제 - storeId={}, 조사결과 {}건, 국세청확인 {}건, 점주연결 {}건",
                storeId, tasks, ntsChecks, ownerLinks);
        return new StoreDeleteResponse(storeId, name, ntsChecks, ntsChanges, menus, businessHours,
                tasks, verifications, signals, ownerLinks);
    }
}
