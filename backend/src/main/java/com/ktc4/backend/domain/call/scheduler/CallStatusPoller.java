package com.ktc4.backend.domain.call.scheduler;

import com.ktc4.backend.domain.call.service.CallService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 끝나지 않은 확인 전화의 상태를 전화 API 에서 받아 온다.
 *
 * <p>전화 API 는 요청을 접수하고 바로 답하므로({@code 202 Accepted}) 통화 결과는 우리가 물어봐야 한다.
 * 담당자 화면은 조사 결과를 되풀이해 조회하는데, 그 조회가 외부 호출을 하면 가게 수만큼 전화 API 를
 * 부르게 된다 — 그래서 받아 오는 일은 여기서 하고 화면 조회는 DB 만 읽는다.
 *
 * <p>건별로 {@link CallService#refresh} 를 부른다. 한 묶음을 한 트랜잭션에 넣으면 전화 API 호출 여러
 * 번이 DB 커넥션 하나를 그만큼 붙든다.
 *
 * <p>한 번에 보는 수에 상한을 둔다. 상한이 없으면 전화 API 가 느려진 날 한 바퀴가 끝나지 않아 다음 차례가
 * 계속 밀린다. 남은 전화는 다음 차례에 본다 — 오래된 것부터 읽으므로 뒤로 밀리기만 하는 전화는 없다.
 */
@Slf4j
@Component
public class CallStatusPoller {

    private final CallService callService;
    private final int batchSize;

    public CallStatusPoller(CallService callService,
                            @Value("${call.poll-batch-size}") int batchSize) {
        this.callService = callService;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${call.poll-interval-ms}",
            initialDelayString = "${call.poll-interval-ms}")
    public void poll() {
        // 주소·키가 없는 환경에서는 걸어 둔 전화도 없다. 조회조차 하지 않아 로컬 로그가 깨끗하다.
        if (!callService.isAvailable()) {
            return;
        }
        List<Long> ids = callService.findUnfinishedIds(batchSize);
        if (ids.isEmpty()) {
            return;
        }
        int changed = 0;
        for (Long callId : ids) {
            try {
                if (callService.refresh(callId)) {
                    changed++;
                }
            } catch (RuntimeException e) {
                /*
                  refresh 는 전화 API 실패를 이미 삼킨다. 여기까지 오는 것은 DB 쪽 실패 같은 것이고,
                  그것도 한 건 때문에 남은 전화의 갱신을 멈출 이유는 없다. @Scheduled 는 예외를 로그 한 줄로
                  삼켜서, 밖으로 내면 "폴러는 도는데 아무것도 갱신되지 않는" 상태가 조용히 생긴다.
                */
                log.error("확인 전화 갱신에 실패했다 - callId={}", callId, e);
            }
        }
        if (changed > 0) {
            log.info("확인 전화 상태 갱신 - {}건 중 {}건 바뀜", ids.size(), changed);
        }
    }
}
