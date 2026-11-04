package com.ktc4.backend.domain.call.service;

import com.ktc4.backend.domain.call.dto.CallBulkResponse;
import com.ktc4.backend.domain.call.dto.CallResponse;
import com.ktc4.backend.domain.call.enums.CallReason;
import com.ktc4.backend.domain.call.enums.CallStatus;
import com.ktc4.backend.domain.job.service.JobQueryService;
import com.ktc4.backend.domain.task.dto.TaskResultResponse;
import com.ktc4.backend.global.error.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 조사 한 건의 전화 대상에 한 번에 전화를 넣는다.
 *
 * <p>담당자가 카드를 하나씩 눌러야 했던 일을 없애려고 둔다 — 한 번 누르면 전화를 걸 만한 가게 전부에
 * 요청이 들어가고, 발신 순서와 동시성은 전화 API 의 대기열이 정한다.
 *
 * <p><b>트랜잭션으로 묶지 않는다.</b> 건별로 {@link CallService#request} 를 부르고, 그 메서드 하나가
 * 트랜잭션 하나다. 전체를 한 트랜잭션으로 묶으면 전화 API 호출 수십 번이 DB 커넥션 하나를 그만큼
 * 붙들고, 중간에 한 건이 실패하면 이미 걸린 전화의 기록까지 함께 되돌아간다 — 전화는 되돌릴 수 없다.
 *
 * <p>그래서 한 건의 실패는 그 건만 건너뛴다. {@link CallService} 와 따로 둔 것은 자기 메서드를
 * 자기가 부르면 트랜잭션 경계가 생기지 않기 때문이다(프록시를 거치지 않는다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallBulkService {

    private final JobQueryService jobQueryService;
    private final CallService callService;

    /**
     * 조사 결과에서 전화를 걸 만한 가게 전부에 전화를 요청한다.
     *
     * <p>대상은 <b>변화가 잡혔거나(우선확인) 확인한 지 90일이 넘은(추가확인)</b> 결과 중, 담당자가 아직
     * 확인하지 않았고 걸어 둔 전화도 없는 것이다. 실패한 전화가 있는 결과는 다시 대상이 된다.
     *
     * @param jobId   조사 ID
     * @param adminId 전화를 요청한 관리자 ID
     * @return 대상 수와 실제로 들어간 수
     * @throws CustomException 조사가 없으면 {@code JOB_NOT_FOUND}, 전화 API 주소·키가 없으면
     *                         {@code CALL_NOT_AVAILABLE}
     */
    public CallBulkResponse requestAll(Long jobId, Long adminId) {
        // 대상이 한 건도 없는데 "설정이 없다" 를 숨기면, 값을 안 넣은 서버에서 아무 일도 안 일어나는 이유가 보이지 않는다.
        callService.requireAvailable();

        List<Long> targets = jobQueryService.getJob(jobId).tasks().stream()
                .filter(CallBulkService::isCallTarget)
                .map(TaskResultResponse::taskId)
                .toList();

        List<CallResponse> calls = new ArrayList<>();
        int skipped = 0;
        for (Long taskId : targets) {
            try {
                calls.add(callService.request(taskId, adminId));
            } catch (CustomException e) {
                /*
                  대상을 고를 때 본 것과 request 가 보는 것이 완전히 같지 않다 — 번호가 없거나 모양이
                  안 맞는 가게, 물을 질문이 만들어지지 않는 가게, 전화 API 가 거절한 건은 여기서 걸러진다.
                  한 건이 안 됐다고 남은 가게까지 멈출 이유가 없어 건너뛴다.
                */
                skipped++;
            } catch (RuntimeException e) {
                /*
                  우리가 미리 가려낸 경우가 아닌 실패(DB 제약 위반 등)도 그 건만 건너뛴다.
                  CustomException 만 잡았을 때 실제로 터졌다 — 한 건이 제약에 걸리자 요청 전체가 500 이
                  됐고, 그 앞에서 이미 걸린 전화의 기록까지 응답에서 사라졌다. 전화는 되돌릴 수 없어서
                  "걸렸는데 안 걸린 것으로 보이는" 쪽이 가장 나쁘다.
                */
                log.error("확인 전화를 넣지 못했다 - jobId={}, taskId={}", jobId, taskId, e);
                skipped++;
            }
        }
        log.info("확인 전화 일괄 요청 - jobId={}, 대상 {}건, 요청 {}건, 건너뜀 {}건",
                jobId, targets.size(), calls.size(), skipped);
        return new CallBulkResponse(targets.size(), calls.size(), skipped, List.copyOf(calls));
    }

    // 전화를 걸 만한 결과인가. 여기서 거르는 것은 "부를 가치가 없는 것" 이고, 실제 가능 여부는 request 가 다시 본다.
    private static boolean isCallTarget(TaskResultResponse task) {
        if (CallReason.from(task.classification()).isEmpty()) {
            return false;
        }
        if (task.verification() != null) {
            return false;
        }
        // 실패한 전화는 다시 걸 수 있다(안 받았거나 끊긴 통화).
        return task.call() == null || task.call().status() == CallStatus.FAILED;
    }
}
