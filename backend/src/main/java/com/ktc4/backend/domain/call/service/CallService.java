package com.ktc4.backend.domain.call.service;

import com.ktc4.backend.domain.call.client.CallClient;
import com.ktc4.backend.domain.call.client.CallException;
import com.ktc4.backend.domain.call.client.CallNotConfigured;
import com.ktc4.backend.domain.call.dto.CallOrder;
import com.ktc4.backend.domain.call.dto.CallQuestion;
import com.ktc4.backend.domain.call.dto.CallResponse;
import com.ktc4.backend.domain.call.dto.CallSnapshot;
import com.ktc4.backend.domain.call.entity.VerificationCall;
import com.ktc4.backend.domain.call.enums.CallReason;
import com.ktc4.backend.domain.call.enums.CallStatus;
import com.ktc4.backend.domain.call.repository.VerificationCallRepository;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.task.entity.Task;
import com.ktc4.backend.domain.task.service.TaskService;
import com.ktc4.backend.domain.verification.service.VerificationService;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BinaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 가게에 확인 전화를 걸고, 전화 API 에서 결과를 받아 온다.
 *
 * <p>전화를 거는 조건은 조사 판정이 정한다 — <b>변화가 잡혔거나(우선확인), 담당자가 확인한 지 90일이
 * 넘었을 때(추가확인)</b>. 변화도 없고 최근에 확인한 가게에는 걸지 않는다. 조사에 실패한 결과에도 걸지
 * 않는다 — 무엇을 물어야 할지 모르는 상태다.
 *
 * <p><b>대기열을 우리가 만들지 않는다.</b> 전화 API 가 요청을 받아 차례대로 발신하므로, 우리는 요청을
 * 넣고 {@code verificationId} 를 받아 두었다가 폴러가 상태를 받아 온다. 한 번에 여러 건을 넣는 일은
 * {@link CallBulkService} 가 한다.
 */
@Slf4j
@Service
@Transactional
public class CallService {

    /** 아직 끝나지 않은 상태 — 폴러가 다시 물어볼 대상. */
    private static final List<CallStatus> UNFINISHED = List.of(CallStatus.PENDING, CallStatus.IN_PROGRESS);

    /**
     * 전화 API 가 받아 주는 번호 모양. 하이픈·공백·괄호를 지운 뒤 맨 앞 {@code +} 는 있어도 되고,
     * 숫자는 7~15자리여야 한다(전화 API 문서).
     *
     * <p>보낼 때는 우리 DB 의 값을 그대로 보낸다 — 지우는 일은 전화 API 가 한다. 여기서 미리 보는 것은
     * 걸 수 없는 번호를 전화 API 의 거절(502 로 보인다)이 아니라 우리 쪽 거절로 알려 주기 위해서다.
     */
    private static final Pattern CALLABLE_PHONE = Pattern.compile("\\+?\\d{7,15}");

    private static final Pattern PHONE_NOISE = Pattern.compile("[-\\s()]");

    /** 한 전화 기록에 둘 이상 붙었을 때(직접 넣은 데이터 등) 가장 최근 것을 고른다. */
    private static final Comparator<VerificationCall> LATEST =
            Comparator.comparing(VerificationCall::getRequestedAt).thenComparing(VerificationCall::getCallId);

    private final VerificationCallRepository callRepository;
    private final CallClient callClient;
    private final TaskService taskService;
    private final VerificationService verificationService;

    /** 이 시간이 지나도 끝나지 않은 전화는 실패로 닫는다. 전화 API 에 통화 제한 시간이 명시돼 있지 않아 우리가 정한다. */
    private final long maxWaitMinutes;

    public CallService(VerificationCallRepository callRepository,
                       CallClient callClient,
                       TaskService taskService,
                       VerificationService verificationService,
                       @Value("${call.max-wait-minutes}") long maxWaitMinutes) {
        this.callRepository = callRepository;
        this.callClient = callClient;
        this.taskService = taskService;
        this.verificationService = verificationService;
        this.maxWaitMinutes = maxWaitMinutes;
    }

    /**
     * 조사 결과 한 건을 보고 그 가게에 확인 전화를 요청한다.
     *
     * <p>Task 를 행 잠금으로 읽고 나서 전화 기록이 있는지 본다 — 잠그기 전에 보면 두 요청이 함께
     * "기록 없음" 을 보고 둘 다 지나가, 가게에 전화가 두 번 간다({@code VerificationService} 와 같은 방식).
     *
     * <p>전화 API 호출을 트랜잭션 안에서 한다. 요청은 통화를 기다리지 않고 바로 돌아오고
     * ({@code 202 Accepted}) 읽기 제한 시간도 걸려 있어, DB 커넥션을 붙드는 시간이 그 상한을 넘지 않는다.
     * 한 번에 여러 건을 넣을 때는 이 메서드를 건별 트랜잭션으로 되풀이한다 — 한 트랜잭션에 묶으면
     * 커넥션 하나를 호출 수만큼 붙들게 된다.
     *
     * @param taskId  조사 결과 ID
     * @param adminId 전화를 요청한 관리자 ID
     * @return 요청이 들어간 전화 기록
     * @throws CustomException 결과가 없으면 {@code TASK_NOT_FOUND}, 전화로 물을 대상이 아니면
     *                         {@code CALL_TARGET_NOT_ELIGIBLE}, 이미 걸어 둔 전화가 있으면
     *                         {@code CALL_ALREADY_REQUESTED}, 전화 API 주소·키가 없으면 {@code CALL_NOT_AVAILABLE},
     *                         전화 API 가 거절하거나 닿지 않으면 {@code CALL_API_ERROR}
     */
    public CallResponse request(Long taskId, Long adminId) {
        Task task = taskService.lockForReview(taskId);
        CallReason reason = CallReason.from(task.getClassification())
                .orElseThrow(() -> notEligible(taskId, "전화로 물을 것이 없는 판정"));
        // 담당자가 이미 확인을 끝낸 카드에는 걸지 않는다 — 답이 나온 뒤에 가게에 전화가 가면 민폐다.
        if (verificationService.isConfirmed(taskId)) {
            throw notEligible(taskId, "이미 확인을 끝낸 결과");
        }
        if (callRepository.existsLiveByTaskId(taskId)) {
            throw new CustomException(ErrorCode.CALL_ALREADY_REQUESTED);
        }

        Store store = task.getStore();
        String phone = callablePhone(taskId, store.getPhone());
        List<CallQuestion> questions = CallQuestions.of(reason, store, task.getProposedChanges());
        if (questions.isEmpty()) {
            throw notEligible(taskId, "현재 값과 수정안으로 만들 질문이 없음");
        }

        String externalId = send(taskId, new CallOrder(store.getName(), store.getAddressRoad(), phone, questions));
        try {
            /*
              saveAndFlush 다. save 만 하면 INSERT 가 트랜잭션을 커밋할 때 나가는데, 그때는 이미 이
              메서드 밖이라 아래 catch 가 잡지 못한다 — 전화는 걸렸는데 verificationId 가 어디에도
              남지 않는다(실제로 그랬다. 같은 id 가 두 번 와서 unique 제약에 걸렸을 때 로그가 비어 있었다).
            */
            VerificationCall call = callRepository.saveAndFlush(VerificationCall.builder()
                    .store(store)
                    .task(task)
                    .reason(reason)
                    .externalId(externalId)
                    .requestedBy(String.valueOf(adminId))
                    .requestedAt(LocalDateTime.now())
                    .build());
            log.info("확인 전화 요청 - taskId={}, storeId={}, reason={}, 질문 {}개",
                    taskId, store.getStoreId(), reason, questions.size());
            return CallResponse.from(call);
        } catch (RuntimeException e) {
            /*
              전화는 이미 들어갔는데 우리 기록만 실패한 경우다. 이대로 두면 그 통화를 가리킬 열쇠가
              우리 쪽에 없어 상태를 받아 올 수도, 두 번 걸리는 것을 막을 수도 없다.
              복구는 사람이 한다 — 그래서 verificationId 를 로그에 남긴다.
            */
            log.error("확인 전화 기록을 저장하지 못했다 — 통화는 이미 요청됐다. taskId={}, verificationId={}",
                    taskId, externalId, e);
            throw e;
        }
    }

    /**
     * 조사 결과 여러 건의 전화 기록을 읽는다.
     *
     * @param taskIds 조사 결과 ID 들
     * @return taskId → 전화 기록. 전화를 걸지 않은 결과는 키가 없다
     */
    @Transactional(readOnly = true)
    public Map<Long, CallResponse> findByTaskIds(Collection<Long> taskIds) {
        return callRepository.findByTask_TaskIdIn(taskIds).stream()
                .collect(Collectors.toMap(
                        call -> call.getTask().getTaskId(),
                        call -> call,
                        BinaryOperator.maxBy(LATEST)))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> CallResponse.from(entry.getValue())));
    }

    /**
     * 아직 끝나지 않은 전화의 ID 를 오래된 것부터 읽는다. 폴러가 이 ID 로 한 건씩 갱신한다.
     *
     * @param limit 한 번에 가져올 수
     * @return 전화 ID. 없으면 빈 목록
     */
    @Transactional(readOnly = true)
    public List<Long> findUnfinishedIds(int limit) {
        return callRepository.findIdsByStatusIn(UNFINISHED, Limit.of(limit));
    }

    /**
     * 전화 한 건의 상태를 전화 API 에서 받아 와 반영한다. 폴러가 건별로 부른다.
     *
     * <p>너무 오래 끝나지 않은 전화는 전화 API 에 묻지 않고 실패로 닫는다 — 닫지 않으면 폴러가 그 전화를
     * 끝없이 다시 읽고, 담당자 화면에도 "전화 중" 이 영영 남는다.
     *
     * <p>전화 API 호출이 실패하면 예외를 밖으로 내지 않는다. 폴링은 되풀이되는 일이라 한 건의 실패로
     * 남은 건의 갱신까지 멈출 이유가 없다.
     *
     * @param callId 전화 기록 ID
     * @return 상태가 실제로 바뀌었으면 true
     */
    public boolean refresh(Long callId) {
        Optional<VerificationCall> found = callRepository.findById(callId);
        if (found.isEmpty()) {
            return false;
        }
        VerificationCall call = found.get();
        if (call.getStatus().isFinished()) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        if (call.getRequestedAt().isBefore(now.minus(Duration.ofMinutes(maxWaitMinutes)))) {
            log.warn("확인 전화를 기다리다 닫았다 - callId={}, verificationId={}, {}분 경과",
                    callId, call.getExternalId(), maxWaitMinutes);
            return call.giveUp(maxWaitMinutes + "분 안에 통화가 끝나지 않았습니다", now);
        }

        CallSnapshot snapshot;
        try {
            snapshot = callClient.read(call.getExternalId());
        } catch (CallException e) {
            log.warn("확인 전화 상태를 받아 오지 못했다 - callId={}, verificationId={}, 사유={}",
                    callId, call.getExternalId(), e.getMessage());
            return false;
        }
        boolean changed = call.apply(snapshot.status(), snapshot.answers(),
                snapshot.startedAt(), snapshot.connectedAt(), snapshot.endedAt(), snapshot.failureReason());
        if (changed && snapshot.status().isFinished()) {
            log.info("확인 전화 종료 - callId={}, status={}, 답 {}건",
                    callId, snapshot.status(), snapshot.answers().size());
        }
        return changed;
    }

    /** 전화 기능을 쓸 수 있는가 — 전화 API 주소와 키가 들어와 있는가. 폴러가 헛돌지 않도록 먼저 본다. */
    public boolean isAvailable() {
        return callClient.isConfigured();
    }

    /**
     * 전화 기능을 쓸 수 없으면 바로 거절한다. 한 번에 여러 건을 넣을 때 쓴다 — 대상이 0건인 것과
     * 값이 없어 아무것도 못 하는 것은 담당자에게 다르게 보여야 한다.
     *
     * @throws CustomException 주소·키가 없으면 {@code CALL_NOT_AVAILABLE}
     */
    @Transactional(readOnly = true)
    public void requireAvailable() {
        if (!isAvailable()) {
            throw new CustomException(ErrorCode.CALL_NOT_AVAILABLE);
        }
    }

    // 전화 API 호출 실패를 우리 오류로 바꾼다. 주소·키가 없는 것(설정 문제)과 거절·장애를 가른다.
    private String send(Long taskId, CallOrder order) {
        try {
            return callClient.request(order);
        } catch (CallNotConfigured e) {
            log.warn("확인 전화를 걸 수 없다 - taskId={}, 사유={}", taskId, e.getMessage());
            throw new CustomException(ErrorCode.CALL_NOT_AVAILABLE);
        } catch (CallException e) {
            // 번호·주소가 섞일 수 있어 요청 본문은 남기지 않는다. 사유 문구에는 상태코드만 들어 있다.
            log.warn("확인 전화 요청이 실패했다 - taskId={}, 사유={}", taskId, e.getMessage());
            throw new CustomException(ErrorCode.CALL_API_ERROR);
        }
    }

    // 번호가 없거나 걸 수 없는 모양이면 전화 대상이 아니다. 번호 자체는 로그에 남기지 않는다.
    private static String callablePhone(Long taskId, String phone) {
        if (phone == null || phone.isBlank()) {
            throw notEligible(taskId, "가게 전화번호 없음");
        }
        if (!CALLABLE_PHONE.matcher(PHONE_NOISE.matcher(phone).replaceAll("")).matches()) {
            throw notEligible(taskId, "전화 API 가 받지 않는 번호 모양");
        }
        return phone;
    }

    // 왜 걸지 않았는지만 남긴다 — 번호·주소 같은 값은 남기지 않는다.
    private static CustomException notEligible(Long taskId, String reason) {
        log.info("확인 전화를 걸지 않음 - taskId={}, reason={}", taskId, reason);
        return new CustomException(ErrorCode.CALL_TARGET_NOT_ELIGIBLE);
    }
}
