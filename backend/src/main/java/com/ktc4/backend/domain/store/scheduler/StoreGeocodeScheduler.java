package com.ktc4.backend.domain.store.scheduler;

import com.ktc4.backend.domain.store.service.StoreGeocodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 좌표가 없는 가게를 주기적으로 채운다({@link StoreGeocodeService}). 키가 없으면 아무것도 하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class StoreGeocodeScheduler {

    private final StoreGeocodeService storeGeocodeService;

    // 기동 직후에는 다른 일을 먼저 하게 조금 뒤에 시작한다. 기록 시각은 JVM 기본 시간대 — Auditing 과 같은 기준
    @Scheduled(fixedDelayString = "${geocode.interval-ms:600000}", initialDelayString = "${geocode.initial-delay-ms:60000}")
    public void fillMissingCoordinates() {
        storeGeocodeService.fillMissingCoordinates(LocalDateTime.now());
    }
}
