package com.ktc4.backend.domain.checkin.service;

import com.ktc4.backend.domain.checkin.dto.CheckInResponse;
import com.ktc4.backend.domain.checkin.entity.CheckIn;
import com.ktc4.backend.domain.checkin.repository.CheckInRepository;
import com.ktc4.backend.domain.qr.service.QrCredentialService;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 체크인을 실제 Postgres 에 붙여서, 발급 → 체크인 흐름과 실패 경로를 확인한다.
 *
 * <p>QR 해석({@code QrCredentialService})과 가게 조회({@code StoreService})를 Mock 으로 바꾸지 않는다.
 * 세 서비스가 한 트랜잭션 안에서 실제로 맞물리는지가 이 테스트의 목적이다.
 */
@Import({CheckInService.class, QrCredentialService.class, StoreService.class})
@DisplayName("CheckInService")
class CheckInServiceTest extends PostgresContainerTest {

    private static final Long CHILD_ID = 7L;
    private static final Long MISSING_STORE_ID = 999_999L;

    @Autowired
    private CheckInService checkInService;

    @Autowired
    private QrCredentialService qrCredentialService;

    @Autowired
    private CheckInRepository checkInRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Long storeId;

    @BeforeEach
    void setUp() {
        storeId = entityManager.persistAndFlush(Store.builder()
                .name("예시분식")
                .nameNormalized("예시분식")
                .addressRoad("가상특별시 예시구 샘플로 123")
                .addressNormalized("가상특별시예시구샘플로123")
                .status(StoreStatus.OPEN)
                .build()).getStoreId();
        entityManager.clear();
    }

    private String issue() {
        String payload = qrCredentialService.issue(CHILD_ID).qrPayload();
        entityManager.flush();
        entityManager.clear();
        return payload;
    }

    private static ErrorCode errorCodeOf(Throwable e) {
        return ((CustomException) e).getErrorCode();
    }

    @Nested
    @DisplayName("성공")
    class Success {

        @Test
        @DisplayName("발급한 QR 로 체크인하면 아동 번호와 가게가 담긴 기록이 한 줄 저장된다")
        void savesCheckIn() {
            String payload = issue();

            CheckInResponse response = checkInService.checkIn(storeId, payload);
            entityManager.flush();
            entityManager.clear();

            CheckIn saved = checkInRepository.findById(response.checkInId()).orElseThrow();
            assertThat(checkInRepository.count()).isEqualTo(1);
            assertThat(saved.getChildId()).isEqualTo(CHILD_ID);
            assertThat(saved.getStore().getStoreId()).isEqualTo(storeId);
        }

        @Test
        @DisplayName("응답의 체크인 시각은 저장된 생성 시각이다")
        void respondsWithCreatedAt() {
            String payload = issue();
            LocalDateTime before = LocalDateTime.now();

            CheckInResponse response = checkInService.checkIn(storeId, payload);

            LocalDateTime after = LocalDateTime.now();
            entityManager.flush();
            entityManager.clear();
            CheckIn saved = checkInRepository.findById(response.checkInId()).orElseThrow();
            assertThat(response.checkedInAt()).isBetween(before, after);
            // Postgres 는 마이크로초 단위로 반올림해 저장하므로 그 차이만큼은 허용한다.
            assertThat(saved.getCreatedAt()).isCloseTo(response.checkedInAt(), within(1, ChronoUnit.MICROS));
        }

        @Test
        @DisplayName("같은 QR 로 여러 번 체크인할 수 있다 — 방문할 때마다 기록이 쌓인다")
        void allowsRepeatedCheckIns() {
            String payload = issue();

            checkInService.checkIn(storeId, payload);
            checkInService.checkIn(storeId, payload);
            entityManager.flush();

            assertThat(checkInRepository.count()).isEqualTo(2);
        }

        @Test
        @DisplayName("체크인은 가게 정보를 바꾸지 않는다 — 확인 시각·수정 시각 그대로")
        void leavesStoreUntouched() {
            Store before = entityManager.find(Store.class, storeId);
            LocalDateTime updatedAtBefore = before.getUpdatedAt();
            LocalDateTime lastCheckedAtBefore = before.getLastCheckedAt();
            entityManager.clear();
            String payload = issue();

            checkInService.checkIn(storeId, payload);
            entityManager.flush();
            entityManager.clear();

            Store after = entityManager.find(Store.class, storeId);
            assertThat(after.getUpdatedAt()).isEqualTo(updatedAtBefore);
            assertThat(after.getLastCheckedAt()).isEqualTo(lastCheckedAtBefore);
        }
    }

    @Nested
    @DisplayName("실패 — 기록이 남지 않는다")
    class Failure {

        @Test
        @DisplayName("재발급으로 바뀐 옛 QR 이면 INVALID_QR_TOKEN")
        void rejectsOldPayloadAfterReissue() {
            String oldPayload = issue();
            issue();

            assertThatThrownBy(() -> checkInService.checkIn(storeId, oldPayload))
                    .isInstanceOf(CustomException.class)
                    .extracting(CheckInServiceTest::errorCodeOf)
                    .isEqualTo(ErrorCode.INVALID_QR_TOKEN);
            assertThat(checkInRepository.count()).isZero();
        }

        @Test
        @DisplayName("발급한 적 없는 QR 이면 INVALID_QR_TOKEN")
        void rejectsUnknownPayload() {
            issue();

            assertThatThrownBy(() -> checkInService.checkIn(storeId, "v1." + "A".repeat(43)))
                    .isInstanceOf(CustomException.class)
                    .extracting(CheckInServiceTest::errorCodeOf)
                    .isEqualTo(ErrorCode.INVALID_QR_TOKEN);
            assertThat(checkInRepository.count()).isZero();
        }

        @Test
        @DisplayName("형식이 틀린 QR 이면 500 이 아니라 INVALID_QR_TOKEN")
        void rejectsMalformedPayload() {
            assertThatThrownBy(() -> checkInService.checkIn(storeId, "v1.!!!"))
                    .isInstanceOf(CustomException.class)
                    .extracting(CheckInServiceTest::errorCodeOf)
                    .isEqualTo(ErrorCode.INVALID_QR_TOKEN);
            assertThat(checkInRepository.count()).isZero();
        }

        @Test
        @DisplayName("없는 가게면 STORE_NOT_FOUND")
        void rejectsMissingStore() {
            String payload = issue();

            assertThatThrownBy(() -> checkInService.checkIn(MISSING_STORE_ID, payload))
                    .isInstanceOf(CustomException.class)
                    .extracting(CheckInServiceTest::errorCodeOf)
                    .isEqualTo(ErrorCode.STORE_NOT_FOUND);
            assertThat(checkInRepository.count()).isZero();
        }

        @Test
        @DisplayName("없는 가게 + 틀린 QR 이면 가게 쪽 에러가 먼저 나간다")
        void checksStoreBeforeQr() {
            assertThatThrownBy(() -> checkInService.checkIn(MISSING_STORE_ID, "v1.!!!"))
                    .isInstanceOf(CustomException.class)
                    .extracting(CheckInServiceTest::errorCodeOf)
                    .isEqualTo(ErrorCode.STORE_NOT_FOUND);
            assertThat(checkInRepository.count()).isZero();
        }
    }
}
