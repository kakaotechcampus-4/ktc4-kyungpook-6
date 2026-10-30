package com.ktc4.backend.domain.file.service;

import com.ktc4.backend.domain.file.client.AiBizCertReading;
import com.ktc4.backend.domain.file.client.BizCertReadFailed;
import com.ktc4.backend.domain.file.client.BizCertReaderClient;
import com.ktc4.backend.domain.file.dto.BizCertExtractionResponse;
import com.ktc4.backend.domain.file.entity.PendingUpload;
import com.ktc4.backend.domain.file.enums.NtsVerification;
import com.ktc4.backend.domain.file.enums.UploadKind;
import com.ktc4.backend.domain.file.repository.PendingUploadRepository;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

/**
 * 가입 화면에서 올린 사업자등록증을 읽어 가입 form 에 채울 값을 돌려준다(점주 가게 등록 간소화, 10/26 회의).
 *
 * <p>흐름 — ① 가입 신청에 아직 쓰지 않은 사업자등록증인지 본다 ② 동시에 읽는 수와 서버 전체 하루 호출 상한을 센다
 * ③ 디스크에서 파일을 읽어 AI 서버에 맡긴다. 같은 곳·같은 파일로 되풀이하는 것은 그 앞의 필터({@code BizCertReadRateLimitFilter})가 막는다.
 *
 * <p><b>DB 트랜잭션을 쥐지 않는다.</b> AI 는 몇 초 걸리는데, 그동안 연결을 붙잡으면 다른 요청이 기다린다. 대기 기록은
 * 읽기만 하고, 읽은 값은 서버에 남기지 않는다 — 가입 신청에 실어야 남는다.
 *
 * <p><b>어떤 실패든 가입을 막지 않는다.</b> 읽지 못하면 {@code BIZ_CERT_UNREADABLE}(503) 이고, 앱은 직접 입력으로 넘어간다.
 * 국세청 진위확인은 아직 하지 않는다 — 결과는 늘 {@code UNVERIFIED} 다(PROMPT-171 에서 붙인다).
 */
@Slf4j
@Service
public class BizCertExtractionService {

    private static final int BIZ_NO_DIGITS = 10;
    // 하루 상한은 한국 날짜로 센다 — 서버가 UTC 면 한국 시간 오전 9시에 초기화돼 버린다.
    // 시계 자체는 서버 기본 시간대로 둔다 — 대기 기록의 createdAt 이 그 시간대로 적혀 3시간 비교가 어긋나지 않게.
    private static final ZoneId BUDGET_ZONE = ZoneId.of("Asia/Seoul");

    private final PendingUploadRepository pendingUploadRepository;
    private final FileStorage fileStorage;
    private final BizCertReaderClient readerClient;
    private final Duration pendingTtl;
    private final DailyBudget dailyBudget;
    private final Semaphore inFlight;
    private final Clock clock;

    @Autowired
    public BizCertExtractionService(PendingUploadRepository pendingUploadRepository, FileStorage fileStorage,
                                    BizCertReaderClient readerClient,
                                    @Value("${upload.pending-ttl-hours:3}") long pendingTtlHours,
                                    @Value("${biz-cert-extraction.max-calls-per-day:300}") int maxCallsPerDay,
                                    @Value("${biz-cert-extraction.max-concurrent:4}") int maxConcurrent) {
        this(pendingUploadRepository, fileStorage, readerClient, Duration.ofHours(pendingTtlHours), maxCallsPerDay,
                maxConcurrent, Clock.systemDefaultZone());
    }

    BizCertExtractionService(PendingUploadRepository pendingUploadRepository, FileStorage fileStorage,
                             BizCertReaderClient readerClient, Duration pendingTtl, int maxCallsPerDay,
                             int maxConcurrent, Clock clock) {
        this.pendingUploadRepository = pendingUploadRepository;
        this.fileStorage = fileStorage;
        this.readerClient = readerClient;
        this.pendingTtl = pendingTtl;
        this.dailyBudget = new DailyBudget(maxCallsPerDay);
        this.inFlight = new Semaphore(maxConcurrent);
        this.clock = clock;
    }

    /**
     * 사업자등록증을 읽는다.
     *
     * @param fileId 사업자등록증을 올리고 받은 파일 ID
     * @return 읽은 값. 못 읽은 칸은 null, 국세청 확인은 {@code UNVERIFIED}
     * @throws CustomException 쓸 수 없는 파일 ID 면 {@code INVALID_FILE_REFERENCE}(없음·만료·이미 가입에 씀·사업자등록증 아님을
     *                         구분하지 않는다), 읽지 못하면 {@code BIZ_CERT_UNREADABLE}
     */
    public BizCertExtractionResponse extract(String fileId) {
        PendingUpload upload = findReadableBizCert(fileId);

        // 동시에 읽는 수를 묶는다. 한 건이 스레드를 최대 25초 쥐고 파일·base64 로 수십 MB 를 쓰는데, IP 별 한도는
        // 곳을 바꿔 한꺼번에 몰리는 것을 막지 못한다. 자리가 없으면 기다리지 않고 직접 입력으로 넘긴다.
        // 파일을 읽기 전·하루 상한을 깎기 전에 본다 — 붐벼서 돌려보낸 요청은 메모리도 상한도 쓰지 않는다.
        if (!inFlight.tryAcquire()) {
            log.info("사업자등록증 읽기 거절 - 동시에 읽는 수가 가득 참");
            throw new CustomException(ErrorCode.BIZ_CERT_UNREADABLE);
        }
        try {
            // 쓸 수 없는 파일 ID 는 세지 않는다 — 상한은 모델을 부르는 횟수다
            if (!dailyBudget.tryAcquire(LocalDate.now(clock.withZone(BUDGET_ZONE)))) {
                throw new CustomException(ErrorCode.BIZ_CERT_UNREADABLE);
            }

            AiBizCertReading reading;
            try {
                reading = readerClient.read(readContent(upload), upload.getFileType());
            } catch (BizCertReadFailed e) {
                log.warn("사업자등록증 읽기 실패 - {}", e.getMessage());
                throw new CustomException(ErrorCode.BIZ_CERT_UNREADABLE);
            }
            BizCertExtractionResponse response = toResponse(reading);
            // 읽은 값은 남기지 않는다 — 대표자 이름·주소가 들어 있다
            log.info("사업자등록증 읽기 - 읽은 칸 {}/8", filledCount(response));
            return response;
        } finally {
            inFlight.release();
        }
    }

    private PendingUpload findReadableBizCert(String fileId) {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(pendingTtl);
        return pendingUploadRepository.findByFileKey(fileId)
                .filter(upload -> upload.getKind() == UploadKind.BIZ_CERT)
                // 정리 작업은 15분마다 돈다 — 지울 시각이 지났지만 아직 남아 있는 파일도 없는 파일로 본다
                .filter(upload -> upload.getCreatedAt() != null && upload.getCreatedAt().isAfter(cutoff))
                .orElseThrow(() -> {
                    log.info("사업자등록증 읽기 거절 - 쓸 수 없는 파일 ID");
                    return new CustomException(ErrorCode.INVALID_FILE_REFERENCE);
                });
    }

    private byte[] readContent(PendingUpload upload) {
        try {
            Resource resource = fileStorage.load(upload.getStoredName());
            return resource.getContentAsByteArray();
        } catch (CustomException e) {
            // 확인한 직후 정리 작업이 지웠다 — 처음부터 없던 파일과 같은 답을 준다
            throw new CustomException(ErrorCode.INVALID_FILE_REFERENCE);
        } catch (IOException e) {
            log.warn("사업자등록증 파일을 읽지 못했습니다 - {}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.BIZ_CERT_UNREADABLE);
        }
    }

    private static BizCertExtractionResponse toResponse(AiBizCertReading reading) {
        return new BizCertExtractionResponse(
                bizNoOrNull(reading.bizNo()),
                reading.storeName(),
                reading.representativeName(),
                reading.storeAddress(),
                reading.openDate(),
                reading.corpName(),
                reading.businessType(),
                reading.businessItem(),
                NtsVerification.UNVERIFIED);
    }

    // AI 가 이미 다듬어 보내지만 한 번 더 본다 — 자릿수가 틀린 번호는 다른 사업자라 칸에 넣지 않는다
    private static String bizNoOrNull(String bizNo) {
        if (bizNo == null) {
            return null;
        }
        String digits = bizNo.replaceAll("\\D", "");
        return digits.length() == BIZ_NO_DIGITS ? digits : null;
    }

    private static long filledCount(BizCertExtractionResponse r) {
        return Stream.of(r.bizNo(), r.storeName(), r.representativeName(), r.storeAddress(),
                        r.openDate(), r.corpName(), r.businessType(), r.businessItem())
                .filter(Objects::nonNull)
                .count();
    }

    /**
     * 서버 전체에서 하루에 모델을 부르는 횟수 — 비용의 마지막 방어선이다. 같은 곳·같은 파일 한도는 필터가 보지만, 곳을 바꿔 가며
     * 부르는 것까지는 막지 못한다. 넘으면 그날은 읽지 않고 직접 입력으로 넘긴다(가입은 막지 않는다).
     *
     * <p>서버 메모리에만 센다. 다시 띄우면 초기화되고, 서버가 여러 대면 대수만큼 늘어난다 — 지금은 한 대다.
     */
    static final class DailyBudget {

        private final int maxCallsPerDay;
        private LocalDate day;
        private int used;
        private boolean warned;

        DailyBudget(int maxCallsPerDay) {
            this.maxCallsPerDay = maxCallsPerDay;
        }

        synchronized boolean tryAcquire(LocalDate today) {
            if (!today.equals(day)) {
                day = today;
                used = 0;
                warned = false;
            }
            if (used >= maxCallsPerDay) {
                if (!warned) {
                    // 넘은 첫 번에만 남긴다 — 그 뒤로 매번 남기면 로그가 끝없이 쌓인다
                    log.warn("사업자등록증 읽기 하루 상한 도달 - {}회, 오늘은 직접 입력으로 넘깁니다", maxCallsPerDay);
                    warned = true;
                }
                return false;
            }
            used++;
            return true;
        }
    }
}
