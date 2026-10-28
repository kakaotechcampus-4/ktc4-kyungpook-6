package com.ktc4.backend.domain.file.service;

import com.ktc4.backend.domain.file.client.AiBizCertReading;
import com.ktc4.backend.domain.file.client.BizCertReadFailed;
import com.ktc4.backend.domain.file.client.BizCertReaderClient;
import com.ktc4.backend.domain.file.dto.BizCertExtractionResponse;
import com.ktc4.backend.domain.file.entity.PendingUpload;
import com.ktc4.backend.domain.file.enums.FileType;
import com.ktc4.backend.domain.file.enums.NtsVerification;
import com.ktc4.backend.domain.file.enums.UploadKind;
import com.ktc4.backend.domain.file.repository.PendingUploadRepository;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 사업자등록증 읽기 서비스 — 저장소·디스크·AI 를 가짜로 두고 흐름만 본다. 값은 모두 지어낸 것이다.
 */
@DisplayName("BizCertExtractionService")
class BizCertExtractionServiceTest {

    private static final String FILE_ID = "3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f";
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-10-27T03:00:00Z");
    private static final byte[] CONTENT = "%PDF-1.7 fake".getBytes();
    private static final AiBizCertReading READING = new AiBizCertReading("1234567890", "예시분식", "홍길동",
            "가상특별시 예시구 샘플로 123", LocalDate.of(2019, 3, 2), null, "음식점업", "한식, 분식");

    private final PendingUploadRepository repository = mock(PendingUploadRepository.class);
    private final FileStorage fileStorage = mock(FileStorage.class);
    private final BizCertReaderClient reader = mock(BizCertReaderClient.class);
    private BizCertExtractionService service;

    @BeforeEach
    void setUp() {
        service = service(300);
        when(fileStorage.load("stored.pdf")).thenReturn(new ByteArrayResource(CONTENT));
    }

    private BizCertExtractionService service(int maxCallsPerDay) {
        return service(maxCallsPerDay, 4);
    }

    private BizCertExtractionService service(int maxCallsPerDay, int maxConcurrent) {
        return new BizCertExtractionService(repository, fileStorage, reader, Duration.ofHours(3), maxCallsPerDay,
                maxConcurrent, Clock.fixed(NOW, ZONE));
    }

    // 올린 지 minutesAgo 분 된 대기 기록
    private static PendingUpload pending(UploadKind kind, long minutesAgo) {
        PendingUpload upload = new PendingUpload(FILE_ID, kind, FileType.PDF, "stored.pdf", CONTENT.length);
        ReflectionTestUtils.setField(upload, "createdAt",
                LocalDateTime.ofInstant(NOW, ZONE).minusMinutes(minutesAgo));
        return upload;
    }

    private static ErrorCode errorCodeOf(Runnable call) {
        try {
            call.run();
        } catch (CustomException e) {
            return e.getErrorCode();
        }
        throw new AssertionError("CustomException 이 나지 않았다");
    }

    @Test
    @DisplayName("대기 중인 사업자등록증을 AI 에 맡기고 읽은 값을 돌려준다 — 국세청 확인은 아직 UNVERIFIED")
    void readsPendingBizCert() {
        when(repository.findByFileKey(FILE_ID)).thenReturn(Optional.of(pending(UploadKind.BIZ_CERT, 10)));
        when(reader.read(CONTENT, FileType.PDF)).thenReturn(READING);

        BizCertExtractionResponse response = service.extract(FILE_ID);

        assertThat(response.bizNo()).isEqualTo("1234567890");
        assertThat(response.openDate()).isEqualTo(LocalDate.of(2019, 3, 2));
        assertThat(response.corpName()).isNull();
        assertThat(response.ntsVerification()).isEqualTo(NtsVerification.UNVERIFIED);
    }

    @Test
    @DisplayName("없는 파일·사업자등록증이 아닌 파일·세 시간이 지난 파일은 INVALID_FILE_REFERENCE 이고 AI 를 부르지 않는다")
    void rejectsUnusableFilesWithoutCallingAi() {
        when(repository.findByFileKey(FILE_ID)).thenReturn(Optional.empty());
        assertThat(errorCodeOf(() -> service.extract(FILE_ID))).isEqualTo(ErrorCode.INVALID_FILE_REFERENCE);

        when(repository.findByFileKey(FILE_ID)).thenReturn(Optional.of(pending(UploadKind.STORE_IMAGE, 10)));
        assertThat(errorCodeOf(() -> service.extract(FILE_ID))).isEqualTo(ErrorCode.INVALID_FILE_REFERENCE);

        // 정리 작업이 아직 지우지 않았어도 기한이 지났으면 없는 파일이다
        when(repository.findByFileKey(FILE_ID)).thenReturn(Optional.of(pending(UploadKind.BIZ_CERT, 3 * 60 + 1)));
        assertThat(errorCodeOf(() -> service.extract(FILE_ID))).isEqualTo(ErrorCode.INVALID_FILE_REFERENCE);

        verify(reader, never()).read(any(), any());
    }

    @Test
    @DisplayName("확인한 직후 정리 작업이 파일을 지웠으면 INVALID_FILE_REFERENCE — 처음부터 없던 파일과 같은 답")
    void fileRemovedAfterCheckIsInvalidReference() {
        when(repository.findByFileKey(FILE_ID)).thenReturn(Optional.of(pending(UploadKind.BIZ_CERT, 10)));
        when(fileStorage.load("stored.pdf")).thenThrow(new CustomException(ErrorCode.FILE_NOT_FOUND));

        assertThat(errorCodeOf(() -> service.extract(FILE_ID))).isEqualTo(ErrorCode.INVALID_FILE_REFERENCE);
        verify(reader, never()).read(any(), any());
    }

    @Test
    @DisplayName("AI 가 읽지 못하면 BIZ_CERT_UNREADABLE — 가입은 막지 않고 직접 입력으로 넘긴다")
    void aiFailureIsUnreadable() {
        when(repository.findByFileKey(FILE_ID)).thenReturn(Optional.of(pending(UploadKind.BIZ_CERT, 10)));
        when(reader.read(CONTENT, FileType.PDF)).thenThrow(new BizCertReadFailed("AI 서버가 502 로 답했습니다", null));

        assertThat(errorCodeOf(() -> service.extract(FILE_ID))).isEqualTo(ErrorCode.BIZ_CERT_UNREADABLE);
    }

    @Test
    @DisplayName("서버 전체 하루 상한을 넘으면 AI 를 부르지 않고 BIZ_CERT_UNREADABLE — 쓸 수 없는 파일 ID 는 상한을 깎지 않는다")
    void dailyBudgetStopsCallingAi() {
        BizCertExtractionService limited = service(2);
        when(repository.findByFileKey("bad")).thenReturn(Optional.empty());
        for (int i = 0; i < 5; i++) {
            assertThat(errorCodeOf(() -> limited.extract("bad"))).isEqualTo(ErrorCode.INVALID_FILE_REFERENCE);
        }
        when(repository.findByFileKey(FILE_ID)).thenReturn(Optional.of(pending(UploadKind.BIZ_CERT, 10)));
        when(reader.read(CONTENT, FileType.PDF)).thenReturn(READING);

        limited.extract(FILE_ID);
        limited.extract(FILE_ID);
        assertThat(errorCodeOf(() -> limited.extract(FILE_ID))).isEqualTo(ErrorCode.BIZ_CERT_UNREADABLE);

        verify(reader, times(2)).read(CONTENT, FileType.PDF);
    }

    @Test
    @DisplayName("AI 가 실패해도 하루 상한은 깎인다 — 모델 비용은 이미 나갔다")
    void failedCallsCountTowardBudget() {
        BizCertExtractionService limited = service(1);
        when(repository.findByFileKey(FILE_ID)).thenReturn(Optional.of(pending(UploadKind.BIZ_CERT, 10)));
        when(reader.read(CONTENT, FileType.PDF)).thenThrow(new BizCertReadFailed("timeout", null));

        assertThat(errorCodeOf(() -> limited.extract(FILE_ID))).isEqualTo(ErrorCode.BIZ_CERT_UNREADABLE);   // AI 실패
        assertThat(errorCodeOf(() -> limited.extract(FILE_ID))).isEqualTo(ErrorCode.BIZ_CERT_UNREADABLE);   // 상한

        verify(reader, times(1)).read(CONTENT, FileType.PDF);
    }

    @Test
    @DisplayName("날이 바뀌면 하루 상한을 처음부터 센다")
    void budgetResetsNextDay() {
        BizCertExtractionService.DailyBudget budget = new BizCertExtractionService.DailyBudget(1);
        LocalDate today = LocalDate.of(2026, 10, 27);

        assertThat(budget.tryAcquire(today)).isTrue();
        assertThat(budget.tryAcquire(today)).isFalse();
        assertThat(budget.tryAcquire(today.plusDays(1))).isTrue();
    }

    @Test
    @DisplayName("동시에 읽는 수가 가득 차면 기다리지 않고 BIZ_CERT_UNREADABLE — 붐벼서 돌려보낸 요청은 하루 상한을 깎지 않는다")
    void rejectsWhenTooManyReadsAreInFlight() throws Exception {
        BizCertExtractionService one = service(2, 1);
        when(repository.findByFileKey(FILE_ID)).thenReturn(Optional.of(pending(UploadKind.BIZ_CERT, 10)));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(reader.read(CONTENT, FileType.PDF)).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return READING;
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<BizCertExtractionResponse> first = pool.submit(() -> one.extract(FILE_ID));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(errorCodeOf(() -> one.extract(FILE_ID))).isEqualTo(ErrorCode.BIZ_CERT_UNREADABLE);

            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).bizNo()).isEqualTo("1234567890");
            // 자리가 돌아왔고, 붐벼서 거절한 한 번은 상한(2)을 깎지 않았다
            assertThat(one.extract(FILE_ID).bizNo()).isEqualTo("1234567890");
            verify(reader, times(2)).read(CONTENT, FileType.PDF);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("AI 가 실패해도 동시 읽기 자리를 돌려준다")
    void failedReadGivesBackItsSlot() {
        BizCertExtractionService one = service(300, 1);
        when(repository.findByFileKey(FILE_ID)).thenReturn(Optional.of(pending(UploadKind.BIZ_CERT, 10)));
        when(reader.read(CONTENT, FileType.PDF))
                .thenThrow(new BizCertReadFailed("AI 서버가 502 로 답했습니다", null))
                .thenReturn(READING);

        assertThat(errorCodeOf(() -> one.extract(FILE_ID))).isEqualTo(ErrorCode.BIZ_CERT_UNREADABLE);
        assertThat(one.extract(FILE_ID).bizNo()).isEqualTo("1234567890");
    }

    @Test
    @DisplayName("하루 상한은 서버가 UTC 여도 한국 날짜로 센다 — 오전 9시가 아니라 한국 자정에 초기화된다")
    void budgetDayIsKoreanDateEvenOnUtcServer() {
        MovableClock utc = new MovableClock(Instant.parse("2026-10-27T14:59:00Z"));   // 한국 10/27 23:59, UTC 10/27
        BizCertExtractionService limited = new BizCertExtractionService(repository, fileStorage, reader,
                Duration.ofHours(3), 1, 4, utc);
        PendingUpload upload = new PendingUpload(FILE_ID, UploadKind.BIZ_CERT, FileType.PDF, "stored.pdf", CONTENT.length);
        when(repository.findByFileKey(FILE_ID)).thenReturn(Optional.of(upload));
        when(reader.read(CONTENT, FileType.PDF)).thenReturn(READING);

        // createdAt 은 서버 기본 시간대(여기선 UTC)로 적힌다 — 3시간 비교가 그대로 맞아야 한다
        ReflectionTestUtils.setField(upload, "createdAt", LocalDateTime.ofInstant(utc.instant(), ZoneOffset.UTC).minusMinutes(10));
        limited.extract(FILE_ID);
        assertThat(errorCodeOf(() -> limited.extract(FILE_ID))).isEqualTo(ErrorCode.BIZ_CERT_UNREADABLE);

        utc.now = Instant.parse("2026-10-27T15:01:00Z");   // 한국 10/28 00:01, UTC 는 아직 10/27
        assertThat(limited.extract(FILE_ID).bizNo()).isEqualTo("1234567890");
    }

    // 테스트 안에서 시각을 옮길 수 있는 UTC 시계. withZone 은 부르는 순간의 시각으로 고정한 시계를 준다
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("AI 가 자릿수가 틀린 사업자번호를 보내면 비운다 — 다른 사업자의 번호일 수 있다")
    void dropsMalformedBizNo() {
        when(repository.findByFileKey(FILE_ID)).thenReturn(Optional.of(pending(UploadKind.BIZ_CERT, 10)));
        when(reader.read(CONTENT, FileType.PDF)).thenReturn(new AiBizCertReading("123-45-6789", null, null, null,
                null, null, null, null));

        assertThat(service.extract(FILE_ID).bizNo()).isNull();
    }
}
