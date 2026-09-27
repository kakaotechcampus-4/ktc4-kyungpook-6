package com.ktc4.backend.domain.store.ntscheck.controller;

import com.ktc4.backend.domain.store.ntscheck.scheduler.NtsCheckScheduler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 국세청 상태 조회 배치를 새벽 3시까지 기다리지 않고 즉시 돌려볼 수 있는 내부 전용 진입점.
 *
 * <p>{@code local}/{@code dev} 프로필에서만 빈이 등록된다 — 운영에서 실수로 눌러 국세청 호출
 * 쿼터를 낭비하는 일을 막기 위해서다. 별도 인증을 걸지 않은 이유도 같다: 운영 환경에는 이 컨트롤러
 * 자체가 존재하지 않는다.
 */
@Tag(name = "국세청 배치(내부용)", description = "local/dev 환경에서 배치를 수동으로 실행합니다")
@Profile({"local", "dev"})
@RestController
@RequestMapping("/internal/nts-check")
@RequiredArgsConstructor
public class NtsCheckAdminController {

    private final NtsCheckScheduler ntsCheckScheduler;

    @Operation(summary = "국세청 상태 조회 배치 즉시 실행",
            description = "새벽 3시 크론을 기다리지 않고 배치를 바로 실행합니다. 응답은 실행 요청 접수만 뜻하며, "
                    + "배치 자체는 기존 스케줄 실행과 동일하게 로그로 진행 상황을 남깁니다.")
    @PostMapping("/run")
    public void run() {
        ntsCheckScheduler.checkAllStores();
    }
}
