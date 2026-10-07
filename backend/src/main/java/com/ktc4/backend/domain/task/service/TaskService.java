package com.ktc4.backend.domain.task.service;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.investigation.dto.AiFinding;
import com.ktc4.backend.domain.investigation.dto.AiSignal;
import com.ktc4.backend.domain.investigation.dto.NtsTarget;
import com.ktc4.backend.domain.job.entity.Job;
import com.ktc4.backend.domain.job.service.JobService;
import com.ktc4.backend.domain.signal.entity.Signal;
import com.ktc4.backend.domain.signal.enums.ChangeField;
import com.ktc4.backend.domain.signal.enums.SignalSource;
import com.ktc4.backend.domain.signal.enums.SignalType;
import com.ktc4.backend.domain.signal.repository.SignalRepository;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.domain.task.dto.EvidenceResponse;
import com.ktc4.backend.domain.task.dto.ProposedChangeResponse;
import com.ktc4.backend.domain.task.dto.TaskResultResponse;
import com.ktc4.backend.domain.task.entity.Task;
import com.ktc4.backend.domain.task.enums.TaskClassification;
import com.ktc4.backend.domain.task.repository.TaskRepository;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 가게 한 곳의 조사 결과를 Task·Signal 로 저장한다.
 *
 * <p>메서드마다 트랜잭션 하나에서 Task·Signal 저장과 Job 진행 수 +1 을 함께 커밋한다 — 나누면 저장은 됐는데
 * 진행 수가 안 오르거나 그 반대가 되어, 진행도가 영영 끝나지 않거나 Task 가 빠진다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class TaskService {

    /**
     * 변화가 없어도 담당자가 마지막으로 확인한 지 이 일수 이상 지났으면 추가확인으로 올린다.
     * 정확히 90일째도 포함한다({@code docs/도메인_용어집.md} — {@code >= 90}).
     */
    static final int RECHECK_AFTER_DAYS = 90;

    private final TaskRepository taskRepository;
    private final SignalRepository signalRepository;
    private final JobService jobService;
    private final StoreService storeService;

    /**
     * 1차 조사(국세청 대조) 결과를 저장한다. AI 를 거치지 않고, 국세청 상태로 바꾸자는 수정안 하나와 국세청 근거 하나를 남긴다.
     *
     * <p>국세청과 상태가 다르다는 것 자체가 변화가 잡힌 것이라 분류는 항상 우선확인이다 — 확인한 지 오래됐는지는 보지 않는다.
     *
     * @param jobId  조사 ID
     * @param target 국세청과 상태가 다른 가게
     * @throws IllegalArgumentException 상태가 같아 제안할 것이 없는 가게일 때 — 대상 나누기가 잘못됐다는 뜻이다
     */
    public void saveNts(Long jobId, NtsTarget target) {
        StoreStatus proposed = target.statusComparison().proposedStatus()
                .orElseThrow(() -> new IllegalArgumentException(
                        "국세청과 상태가 같아 1차 수정안이 없습니다 - storeId=" + target.storeId()));
        Job job = jobService.recordProgress(jobId);
        Store store = storeService.findStore(target.storeId());

        Task task = taskRepository.save(Task.builder()
                .job(job)
                .store(store)
                .classification(TaskClassification.PRIORITY_CHECK)
                .proposedChanges(NtsProposedChanges.from(target.statusComparison()))
                .build());
        signalRepository.save(Signal.builder()
                .task(task)
                .source(SignalSource.NTS)
                .field(ChangeField.STATUS)
                // 수정안·AI 와 같은 값(StoreStatus 이름)으로 남긴다 — 국세청 이름(ACTIVE)과 가게 상태 이름(OPEN)이 다르다
                .observed(proposed.name())
                .signalType(SignalType.SIGNAL_HIGH)
                .evidenceText("국세청 사업자 상태: " + label(target.ntsStatus()))
                .build());
    }

    /**
     * 2차 조사(AI) 결과를 저장한다. 수정안은 Store 필드명 키로, 근거는 AI 출처로 남긴다.
     *
     * <p>AI 는 변화가 없으면 {@code NO_CHANGE} 로만 답한다. 그 가게를 담당자가 확인한 지
     * {@value #RECHECK_AFTER_DAYS}일 이상 지났거나 한 번도 확인하지 않았으면 추가확인으로 올린다 — 이 판단은
     * 확인 시각을 가진 백엔드의 몫이다({@code ai/docs/백엔드_연동.md}).
     *
     * @param jobId   조사 ID
     * @param finding AI 조사 결과 (성공)
     * @param now     추가확인을 판단할 기준 시각
     * @throws IllegalArgumentException 실패 결과를 넘겼을 때 — 실패는 {@link #saveFailure} 로 남긴다
     */
    public void save(Long jobId, AiFinding finding, LocalDateTime now) {
        if (finding.isFailed()) {
            throw new IllegalArgumentException("실패 결과는 saveFailure 로 저장합니다 - storeId=" + finding.storeId());
        }
        Job job = jobService.recordProgress(jobId);
        Store store = storeService.findStore(finding.storeId());

        Task task = taskRepository.save(Task.builder()
                .job(job)
                .store(store)
                .classification(classify(finding.classification(), store.getLastCheckedAt(), now))
                .proposedChanges(toStoredChanges(finding.proposedChanges()))
                .build());
        for (AiSignal signal : finding.signals()) {
            signalRepository.save(Signal.builder()
                    .task(task)
                    .source(SignalSource.AI_WEB)
                    .field(signal.field())
                    .observed(signal.observed())
                    .signalType(signal.signalType())
                    .evidenceText(signal.evidenceText())
                    .evidenceUrl(signal.evidenceUrl())
                    .build());
        }
    }

    /**
     * 조사하지 못한 가게를 판정 없이 실패 이유와 함께 남긴다. 성공과 똑같이 진행 수를 1 올린다 —
     * 요청한 가게 수와 Task 수가 같아야 화면이 "조사 실패 N곳"을 채울 수 있다.
     *
     * @param jobId   조사 ID
     * @param storeId 가게 ID
     * @param reason  화면에 보여 줄 실패 이유 (정해 둔 문구)
     */
    public void saveFailure(Long jobId, Long storeId, String reason) {
        Job job = jobService.recordProgress(jobId);
        Store store = storeService.findStore(storeId);

        taskRepository.save(Task.builder()
                .job(job)
                .store(store)
                .failureReason(reason)
                .build());
    }

    /**
     * 조사 한 건의 결과를 가게·근거와 함께 읽는다. Task·가게는 한 쿼리, 근거는 Task 묶음으로 한 쿼리라 Task 수와
     * 상관없이 쿼리가 두 번이다.
     *
     * @param jobId 조사 ID
     * @return taskId 오름차순 결과. 아직 끝난 가게가 없으면 빈 목록
     */
    @Transactional(readOnly = true)
    public List<TaskResultResponse> findResults(Long jobId) {
        List<Task> tasks = taskRepository.findAllWithStoreByJobId(jobId);
        if (tasks.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Signal>> signalsByTask = signalRepository
                .findByTask_TaskIdInOrderBySignalIdAsc(tasks.stream().map(Task::getTaskId).toList())
                .stream()
                .collect(Collectors.groupingBy(signal -> signal.getTask().getTaskId()));

        return tasks.stream()
                .map(task -> toResult(task, signalsByTask.getOrDefault(task.getTaskId(), List.of())))
                .toList();
    }

    /**
     * 조사 결과 한 건을 가게·근거와 함께 읽는다 — 반영·확인 뒤 화면이 그 카드 하나만 다시 그릴 때 쓴다.
     *
     * @param taskId Task ID
     * @return 결과 조회의 task 한 건과 같은 모양 (확인 기록은 비어 있다 — 부르는 쪽이 붙인다)
     * @throws CustomException Task 가 없으면 {@code TASK_NOT_FOUND}
     */
    @Transactional(readOnly = true)
    public TaskResultResponse findResult(Long taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new CustomException(ErrorCode.TASK_NOT_FOUND));
        return toResult(task, signalRepository.findByTask_TaskIdInOrderBySignalIdAsc(List.of(taskId)));
    }

    /**
     * 담당자가 반영·확인할 Task 를 행 잠금으로 읽는다. 같은 카드에 요청이 동시에 와도 먼저 잠근 쪽이 끝날 때까지
     * 나머지가 기다려, 확인 기록이 있는지를 하나씩 보게 된다.
     *
     * <p>잠금은 트랜잭션이 끝날 때 풀리므로 이미 열린 트랜잭션 안에서만 부를 수 있다 — 혼자 부르면 잠그자마자 풀려 의미가 없다.
     *
     * @param taskId Task ID
     * @return 잠근 Task (가게는 아직 읽지 않은 채)
     * @throws CustomException Task 가 없으면 {@code TASK_NOT_FOUND}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Task lockForReview(Long taskId) {
        return taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new CustomException(ErrorCode.TASK_NOT_FOUND));
    }

    private static TaskResultResponse toResult(Task task, List<Signal> signals) {
        Store store = task.getStore();
        return new TaskResultResponse(
                task.getTaskId(),
                store.getStoreId(),
                store.getName(),
                store.getAddressRoad(),
                store.getStatus(),
                store.getPhone(),
                store.getLastCheckedAt(),
                task.getClassification(),
                task.getFailureReason(),
                toProposedChangeResponses(task.getProposedChanges()),
                signals.stream()
                        .map(signal -> new EvidenceResponse(
                                signal.getField().key(),
                                signal.getSource().name(),
                                signal.getEvidenceText(),
                                null,
                                signal.getEvidenceUrl()))
                        .toList(),
                null);
    }

    // 저장 순서와 상관없이 항목 순서(ChangeField 선언 순)로 내보낸다. 모르는 키는 버리지 않고 맨 뒤에 둔다.
    private static List<ProposedChangeResponse> toProposedChangeResponses(Map<String, Object> proposedChanges) {
        if (proposedChanges == null || proposedChanges.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<String, Object>> entries = new ArrayList<>(proposedChanges.entrySet());
        entries.sort(Comparator.comparingInt(entry -> ChangeField.fromKey(entry.getKey())
                .map(Enum::ordinal)
                .orElse(Integer.MAX_VALUE)));
        return entries.stream()
                .map(entry -> new ProposedChangeResponse(entry.getKey(), String.valueOf(entry.getValue())))
                .toList();
    }

    private static TaskClassification classify(TaskClassification aiClassification,
                                                LocalDateTime lastCheckedAt, LocalDateTime now) {
        if (aiClassification != TaskClassification.NO_CHANGE) {
            return aiClassification;
        }
        boolean needsRecheck = lastCheckedAt == null || !lastCheckedAt.plusDays(RECHECK_AFTER_DAYS).isAfter(now);
        return needsRecheck ? TaskClassification.ADDITIONAL_CHECK : TaskClassification.NO_CHANGE;
    }

    // 키 ↔ ChangeField 변환은 ChangeField 한 곳에 있다. 화면에 늘 같은 순서로 나오게 항목 순서대로 담는다.
    private static Map<String, Object> toStoredChanges(Map<ChangeField, String> changes) {
        Map<String, Object> stored = new LinkedHashMap<>();
        for (ChangeField field : ChangeField.values()) {
            if (changes.containsKey(field)) {
                stored.put(field.key(), changes.get(field));
            }
        }
        return stored;
    }

    // 용어집의 화면 라벨 (docs/도메인_용어집.md — "영업중" 은 쓰지 않을 말)
    private static String label(BusinessState ntsStatus) {
        if (ntsStatus == null) {
            return "확인안됨";
        }
        return switch (ntsStatus) {
            case ACTIVE -> "계속사업자";
            case SUSPENDED -> "휴업자";
            case CLOSED -> "폐업자";
            case NOT_REGISTERED -> "미등록";
        };
    }
}
