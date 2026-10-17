package com.ktc4.backend.domain.file.scheduler;

import com.ktc4.backend.domain.file.service.UploadFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 올라온 지 오래됐는데 아무 가입 신청도 가져가지 않은 파일을 지운다.
 *
 * <p>업로드는 로그인 없이 받는다. 가입 화면에서 사진만 올리고 나가 버린 점주도, 가입할 생각 없이 파일만 올리는 요청도
 * 있다. 지우지 않으면 디스크에 계속 쌓인다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingUploadCleanupScheduler {

    private final UploadFileService uploadFileService;

    // 가입 화면에서 사진을 고르고 세 시간 넘게 신청하지 않는 경우는 드물다. 넘기면 앱이 다시 올리면 된다.
    @Value("${upload.pending-ttl-hours:3}")
    private long ttlHours;

    // 처음 한 번은 기동하고 한 주기 뒤에 돈다 — 기동 직후에는 다른 일을 먼저 하게 둔다
    @Scheduled(fixedDelayString = "${upload.cleanup-interval-ms:900000}",
            initialDelayString = "${upload.cleanup-interval-ms:900000}")
    public void deleteExpired() {
        int deleted = uploadFileService.deleteExpiredPending(LocalDateTime.now().minusHours(ttlHours));
        if (deleted > 0) {
            log.info("가입에 쓰이지 않은 업로드 정리 - {}건", deleted);
        }
    }
}
