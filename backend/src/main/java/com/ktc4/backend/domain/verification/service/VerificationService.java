package com.ktc4.backend.domain.verification.service;

import com.ktc4.backend.domain.signal.enums.ChangeField;
import com.ktc4.backend.domain.store.dto.StoreUpdateRequest;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.domain.task.dto.TaskResultResponse;
import com.ktc4.backend.domain.task.entity.Task;
import com.ktc4.backend.domain.task.service.TaskService;
import com.ktc4.backend.domain.verification.dto.VerificationResponse;
import com.ktc4.backend.domain.verification.entity.Verification;
import com.ktc4.backend.domain.verification.enums.VerificationAction;
import com.ktc4.backend.domain.verification.enums.VerificationResult;
import com.ktc4.backend.domain.verification.repository.VerificationRepository;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

/**
 * 담당자가 조사 결과 카드를 처리한다 — "즉시 수정 반영하기"(수정안을 가게에 반영)와 "자체 확인 완료"(확인만).
 *
 * <p>둘 다 한 트랜잭션에서 Task 를 행 잠금으로 읽고, 확인 기록이 없을 때만 가게 확인일과 확인 기록을 남긴다.
 * 같은 카드는 한 번만 처리한다 — 연타나 여러 탭에서 와도 같은 수정안이 두 번 반영되지 않게.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class VerificationService {

    private static final Comparator<Verification> LATEST =
            Comparator.comparing(Verification::getVerifiedAt).thenComparing(Verification::getVerificationId);

    private final VerificationRepository verificationRepository;
    private final TaskService taskService;
    private final StoreService storeService;
    private final Validator validator;

    /**
     * 조사 결과의 수정안을 그대로 가게에 반영하고 확인 완료로 남긴다.
     *
     * <p>수정안을 모두 검사한 뒤에만 가게를 고친다 — 하나라도 가게 규칙에 맞지 않으면 아무것도 바꾸지 않는다.
     * 값을 고쳐서 반영하려면 "직접 수정하기"(가게 수정 API) 뒤 {@link #confirm} 을 쓴다.
     *
     * @param taskId  조사 결과 ID
     * @param adminId 확인한 관리자 ID
     * @return 바뀐 가게 값과 확인 기록이 담긴 카드 한 장
     * @throws CustomException 결과가 없으면 {@code TASK_NOT_FOUND}, 이미 확인했으면 {@code TASK_ALREADY_CONFIRMED},
     *                         수정안이 없거나 가게 규칙에 맞지 않으면 {@code NOTHING_TO_APPLY}
     */
    public TaskResultResponse apply(Long taskId, Long adminId) {
        Task task = lockUnreviewed(taskId);
        StoreUpdateRequest request = toUpdateRequest(task);
        Long storeId = task.getStore().getStoreId();

        storeService.updateStore(storeId, request);
        Map<String, Object> applied = new LinkedHashMap<>(task.getProposedChanges());
        return saveVerification(task, actionFor(applied), applied, adminId);
    }

    /**
     * 가게 값은 그대로 두고 확인 완료로 남긴다. 담당자가 "직접 수정하기"로 고쳤거나 직접 확인해 바꿀 것이 없을 때 쓴다.
     * 수정안이 없는 결과(변화없음·추가확인·조사 실패)도 닫을 수 있다.
     *
     * @param taskId  조사 결과 ID
     * @param adminId 확인한 관리자 ID
     * @return 확인 기록이 담긴 카드 한 장
     * @throws CustomException 결과가 없으면 {@code TASK_NOT_FOUND}, 이미 확인했으면 {@code TASK_ALREADY_CONFIRMED}
     */
    public TaskResultResponse confirm(Long taskId, Long adminId) {
        Task task = lockUnreviewed(taskId);
        return saveVerification(task, VerificationAction.NO_ACTION, null, adminId);
    }

    /**
     * 조사 결과 여러 건의 확인 기록을 읽는다. 한 건에 기록이 둘 이상이면(직접 넣은 데이터 등) 가장 최근 것을 쓴다.
     *
     * @param taskIds 조사 결과 ID 들
     * @return taskId → 확인 기록. 확인하지 않은 결과는 키가 없다
     */
    @Transactional(readOnly = true)
    public Map<Long, VerificationResponse> findByTaskIds(Collection<Long> taskIds) {
        return verificationRepository.findByTask_TaskIdIn(taskIds).stream()
                .collect(Collectors.toMap(
                        verification -> verification.getTask().getTaskId(),
                        verification -> verification,
                        BinaryOperator.maxBy(LATEST)))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> VerificationResponse.from(entry.getValue())));
    }

    // 잠근 뒤에 확인 기록을 본다 — 잠그기 전에 보면 두 요청이 함께 "기록 없음"을 보고 둘 다 지나간다
    private Task lockUnreviewed(Long taskId) {
        Task task = taskService.lockForReview(taskId);
        if (verificationRepository.existsByTask_TaskId(taskId)) {
            throw new CustomException(ErrorCode.TASK_ALREADY_CONFIRMED);
        }
        return task;
    }

    // 확인 기록과 가게 확인일은 같은 시각으로 남긴다 — 화면이 둘을 나란히 보여 준다
    private TaskResultResponse saveVerification(Task task, VerificationAction action,
                                                Map<String, Object> appliedChanges, Long adminId) {
        LocalDateTime now = LocalDateTime.now();
        storeService.confirmStore(task.getStore().getStoreId(), now);
        Verification verification = verificationRepository.save(Verification.builder()
                .store(task.getStore())
                .task(task)
                .result(VerificationResult.CONFIRMED)
                .action(action)
                .appliedChanges(appliedChanges)
                .verifiedBy(String.valueOf(adminId))
                .verifiedAt(now)
                .build());
        return taskService.findResult(task.getTaskId()).withVerification(VerificationResponse.from(verification));
    }

    private static VerificationAction actionFor(Map<String, Object> applied) {
        boolean statusOnly = applied.keySet().equals(Set.of(ChangeField.STATUS.key()));
        return statusOnly ? VerificationAction.CHANGE_STATUS : VerificationAction.UPDATE_INFO;
    }

    // 수정안(AI·국세청이 만든 값)을 가게 수정 요청으로 바꾼다. 가게 수정 API 의 @Valid 를 거치지 않으므로 여기서 같은 규칙으로 검사한다
    private StoreUpdateRequest toUpdateRequest(Task task) {
        Map<String, Object> proposed = task.getProposedChanges();
        if (proposed == null || proposed.isEmpty()) {
            throw nothingToApply(task, "수정안 없음");
        }
        Map<ChangeField, String> values = new EnumMap<>(ChangeField.class);
        for (Map.Entry<String, Object> entry : proposed.entrySet()) {
            ChangeField field = ChangeField.fromKey(entry.getKey())
                    .orElseThrow(() -> nothingToApply(task, "모르는 항목"));
            if (!(entry.getValue() instanceof String value)) {
                throw nothingToApply(task, field.key() + " 값이 문자열이 아님");
            }
            values.put(field, value);
        }
        StoreUpdateRequest request = new StoreUpdateRequest(
                values.get(ChangeField.NAME),
                values.get(ChangeField.ADDRESS_ROAD),
                phone(task, values.get(ChangeField.PHONE)),
                status(task, values.get(ChangeField.STATUS)));
        validate(task, request);
        return request;
    }

    // 빈 전화번호는 가게 수정에서 "전화번호 지우기"라 수정안으로는 받지 않는다. 지우는 건 담당자가 직접 수정한다
    private static String phone(Task task, String value) {
        if (value != null && value.isBlank()) {
            throw nothingToApply(task, "phone 값이 비어 있음");
        }
        return value;
    }

    // 미확인(UNKNOWN)으로 바꾸면 그 가게가 국세청 대조에서 빠지므로 수정안으로는 받지 않는다
    private static StoreStatus status(Task task, String value) {
        if (value == null) {
            return null;
        }
        return Arrays.stream(StoreStatus.values())
                .filter(status -> status != StoreStatus.UNKNOWN && status.name().equals(value))
                .findFirst()
                .orElseThrow(() -> nothingToApply(task, "status 값을 모름"));
    }

    private void validate(Task task, StoreUpdateRequest request) {
        Set<ConstraintViolation<StoreUpdateRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String fields = violations.stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .sorted()
                    .collect(Collectors.joining(","));
            throw nothingToApply(task, fields + " 가게 규칙 위반");
        }
    }

    // 어느 항목이 왜 막혔는지만 남긴다 — 값(전화번호 등)은 남기지 않는다
    private static CustomException nothingToApply(Task task, String reason) {
        log.warn("수정안을 반영하지 않음 - taskId={}, reason={}", task.getTaskId(), reason);
        return new CustomException(ErrorCode.NOTHING_TO_APPLY);
    }
}
