package com.ktc4.backend.domain.checkin.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ktc4.backend.domain.checkin.dto.CheckInResponse;
import com.ktc4.backend.domain.checkin.entity.CheckIn;
import com.ktc4.backend.domain.checkin.repository.CheckInRepository;
import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.entity.OwnerInfo;
import com.ktc4.backend.domain.member.entity.StoreOwner;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.qr.service.QrCredentialService;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.AuthMember;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Import;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;

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

    // 관리자는 가게 연결을 확인하지 않는다. 가게 연결과 무관한 테스트는 관리자로 부른다.
    private static final AuthMember ADMIN = new AuthMember(1L, MemberRole.ADMIN);

    @Autowired
    private CheckInService checkInService;

    @Autowired
    private QrCredentialService qrCredentialService;

    @Autowired
    private CheckInRepository checkInRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Long storeId;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger serviceLogger;

    @BeforeEach
    void attachLogAppender() {
        // Lombok @Slf4j 로거는 private static final 이라 Mockito 로 잡을 수 없다. ListAppender 로 실제 로그를 받는다.
        serviceLogger = (Logger) LoggerFactory.getLogger(CheckInService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        serviceLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    private List<String> qrFailureLogs() {
        return logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("QR 체크인 실패"))
                .toList();
    }

    // 기대 해시는 운영 코드가 아니라 JDK 로 직접 계산한다 — 로그에 해시가 새지 않는지 확인할 때 쓴다.
    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

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

            CheckInResponse response = checkInService.checkIn(storeId, payload, ADMIN);
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

            CheckInResponse response = checkInService.checkIn(storeId, payload, ADMIN);

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

            checkInService.checkIn(storeId, payload, ADMIN);
            checkInService.checkIn(storeId, payload, ADMIN);
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

            checkInService.checkIn(storeId, payload, ADMIN);
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

            assertThatThrownBy(() -> checkInService.checkIn(storeId, oldPayload, ADMIN))
                    .isInstanceOf(CustomException.class)
                    .extracting(CheckInServiceTest::errorCodeOf)
                    .isEqualTo(ErrorCode.INVALID_QR_TOKEN);
            assertThat(checkInRepository.count()).isZero();
        }

        @Test
        @DisplayName("발급한 적 없는 QR 이면 INVALID_QR_TOKEN")
        void rejectsUnknownPayload() {
            issue();

            assertThatThrownBy(() -> checkInService.checkIn(storeId, "v1." + "A".repeat(43), ADMIN))
                    .isInstanceOf(CustomException.class)
                    .extracting(CheckInServiceTest::errorCodeOf)
                    .isEqualTo(ErrorCode.INVALID_QR_TOKEN);
            assertThat(checkInRepository.count()).isZero();
        }

        @Test
        @DisplayName("형식이 틀린 QR 이면 500 이 아니라 INVALID_QR_TOKEN")
        void rejectsMalformedPayload() {
            assertThatThrownBy(() -> checkInService.checkIn(storeId, "v1.!!!", ADMIN))
                    .isInstanceOf(CustomException.class)
                    .extracting(CheckInServiceTest::errorCodeOf)
                    .isEqualTo(ErrorCode.INVALID_QR_TOKEN);
            assertThat(checkInRepository.count()).isZero();
        }

        @Test
        @DisplayName("없는 가게면 STORE_NOT_FOUND")
        void rejectsMissingStore() {
            String payload = issue();

            assertThatThrownBy(() -> checkInService.checkIn(MISSING_STORE_ID, payload, ADMIN))
                    .isInstanceOf(CustomException.class)
                    .extracting(CheckInServiceTest::errorCodeOf)
                    .isEqualTo(ErrorCode.STORE_NOT_FOUND);
            assertThat(checkInRepository.count()).isZero();
        }

        @Test
        @DisplayName("없는 가게 + 틀린 QR 이면 가게 쪽 에러가 먼저 나간다")
        void checksStoreBeforeQr() {
            assertThatThrownBy(() -> checkInService.checkIn(MISSING_STORE_ID, "v1.!!!", ADMIN))
                    .isInstanceOf(CustomException.class)
                    .extracting(CheckInServiceTest::errorCodeOf)
                    .isEqualTo(ErrorCode.STORE_NOT_FOUND);
            assertThat(checkInRepository.count()).isZero();
        }
    }

    @Nested
    @DisplayName("가게 연결 확인 — 점주는 자기에게 연결된 가게에만 기록한다")
    class StoreLink {

        private static final LocalDateTime LINKED_AT = LocalDateTime.of(2026, 10, 6, 10, 0);

        private Member persistOwner(String email) {
            Member owner = Member.ownerApplicant(email, "hash", new OwnerInfo("0000000000", "예시분식", "홍길동", "01000000000"));
            owner.approve(LINKED_AT);
            return entityManager.persistAndFlush(owner);
        }

        private void link(Long linkedStoreId, Member owner) {
            Member admin = entityManager.persistAndFlush(Member.admin("admin@example.com", "hash"));
            Store store = entityManager.find(Store.class, linkedStoreId);
            entityManager.persistAndFlush(StoreOwner.link(store, owner, admin, LINKED_AT));
            entityManager.clear();
        }

        private Long persistAnotherStore() {
            return entityManager.persistAndFlush(Store.builder()
                    .name("샘플카페")
                    .nameNormalized("샘플카페")
                    .addressRoad("가상특별시 예시구 샘플로 456")
                    .addressNormalized("가상특별시예시구샘플로456")
                    .status(StoreStatus.OPEN)
                    .build()).getStoreId();
        }

        private AuthMember loginAs(Member owner) {
            return new AuthMember(owner.getMemberId(), MemberRole.OWNER);
        }

        @Test
        @DisplayName("연결된 가게에는 체크인할 수 있다")
        void linkedOwnerCanCheckIn() {
            Member owner = persistOwner("owner@example.com");
            link(storeId, owner);
            String payload = issue();

            checkInService.checkIn(storeId, payload, loginAs(owner));
            entityManager.flush();

            assertThat(checkInRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("어느 가게에도 연결되지 않은 점주는 FORBIDDEN — 기록이 남지 않는다")
        void rejectsOwnerWithoutLink() {
            Member owner = persistOwner("owner@example.com");
            String payload = issue();

            assertThatThrownBy(() -> checkInService.checkIn(storeId, payload, loginAs(owner)))
                    .isInstanceOf(CustomException.class)
                    .extracting(CheckInServiceTest::errorCodeOf)
                    .isEqualTo(ErrorCode.FORBIDDEN);
            assertThat(checkInRepository.count()).isZero();
        }

        @Test
        @DisplayName("다른 가게에 연결된 점주는 이 가게에 기록할 수 없다")
        void rejectsOwnerLinkedToAnotherStore() {
            Member owner = persistOwner("owner@example.com");
            Long anotherStoreId = persistAnotherStore();
            link(anotherStoreId, owner);
            String payload = issue();

            assertThatThrownBy(() -> checkInService.checkIn(storeId, payload, loginAs(owner)))
                    .isInstanceOf(CustomException.class)
                    .extracting(CheckInServiceTest::errorCodeOf)
                    .isEqualTo(ErrorCode.FORBIDDEN);
            assertThat(checkInRepository.count()).isZero();
        }

        @Test
        @DisplayName("이 가게에 다른 점주가 연결돼 있어도, 연결되지 않은 점주는 기록할 수 없다")
        void linkBelongsToEachOwner() {
            Member linked = persistOwner("linked@example.com");
            Member stranger = persistOwner("stranger@example.com");
            link(storeId, linked);
            String payload = issue();

            assertThatThrownBy(() -> checkInService.checkIn(storeId, payload, loginAs(stranger)))
                    .isInstanceOf(CustomException.class)
                    .extracting(CheckInServiceTest::errorCodeOf)
                    .isEqualTo(ErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("남의 가게면 QR 을 보기 전에 끝난다 — 남의 가게 번호로 QR 이 유효한지 알아볼 수 없다")
        void checksLinkBeforeQr() {
            Member owner = persistOwner("owner@example.com");

            assertThatThrownBy(() -> checkInService.checkIn(storeId, "v1.!!!", loginAs(owner)))
                    .isInstanceOf(CustomException.class)
                    .extracting(CheckInServiceTest::errorCodeOf)
                    .isEqualTo(ErrorCode.FORBIDDEN);
            assertThat(qrFailureLogs()).isEmpty();
        }

        @Test
        @DisplayName("연결되지 않은 점주에게는 없는 가게도 FORBIDDEN — 404 와 403 의 차이로 가게가 있는지 알아낼 수 없다")
        void hidesStoreExistenceFromUnlinkedOwner() {
            Member owner = persistOwner("owner@example.com");
            String payload = issue();

            assertThatThrownBy(() -> checkInService.checkIn(MISSING_STORE_ID, payload, loginAs(owner)))
                    .isInstanceOf(CustomException.class)
                    .extracting(CheckInServiceTest::errorCodeOf)
                    .isEqualTo(ErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("관리자에게는 없는 가게가 STORE_NOT_FOUND 로 그대로 보인다")
        void adminSeesMissingStore() {
            String payload = issue();

            assertThatThrownBy(() -> checkInService.checkIn(MISSING_STORE_ID, payload, ADMIN))
                    .isInstanceOf(CustomException.class)
                    .extracting(CheckInServiceTest::errorCodeOf)
                    .isEqualTo(ErrorCode.STORE_NOT_FOUND);
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @EnumSource(MemberRole.class)
        @DisplayName("통과시키는 것은 관리자와 연결된 점주뿐이다 — 연결 없는 역할은 관리자가 아니면 모두 거절된다")
        void onlyAdminPassesWithoutLink(MemberRole role) {
            // 역할이 새로 생겨도 이 테스트가 그 역할로 한 번 돈다. "점주가 아니면 통과"로 되돌아가면 여기서 걸린다
            String payload = issue();
            AuthMember member = new AuthMember(999L, role);

            if (role == MemberRole.ADMIN) {
                checkInService.checkIn(storeId, payload, member);
                entityManager.flush();
                assertThat(checkInRepository.count()).isEqualTo(1);
            } else {
                assertThatThrownBy(() -> checkInService.checkIn(storeId, payload, member))
                        .isInstanceOf(CustomException.class)
                        .extracting(CheckInServiceTest::errorCodeOf)
                        .isEqualTo(ErrorCode.FORBIDDEN);
                assertThat(checkInRepository.count()).isZero();
            }
        }

        @Test
        @DisplayName("관리자는 연결이 없어도 어느 가게에나 기록할 수 있다")
        void adminSkipsLinkCheck() {
            String payload = issue();

            checkInService.checkIn(storeId, payload, ADMIN);
            entityManager.flush();

            assertThat(checkInRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("로그인 정보 없이 들어온 요청(권한 검사 스위치가 꺼진 동안)은 연결을 확인하지 않는다")
        void skipsLinkCheckWithoutLogin() {
            String payload = issue();

            checkInService.checkIn(storeId, payload, null);
            entityManager.flush();

            assertThat(checkInRepository.count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("실패 이유 로그 — 응답은 하나로, 로그에는 이유를 (멘토 Q6)")
    class FailureLog {

        @Test
        @DisplayName("형식이 틀린 QR 이면 storeId 와 reason=FORMAT 을 한 줄 남기고, 보낸 값은 남기지 않는다")
        void logsFormatReasonWithoutPayload() {
            String malformed = "v1.SECRETPAYLOAD!!!";

            assertThatThrownBy(() -> checkInService.checkIn(storeId, malformed, ADMIN))
                    .isInstanceOf(CustomException.class);

            assertThat(qrFailureLogs()).containsExactly("QR 체크인 실패 - storeId=" + storeId + ", reason=FORMAT");
            assertThat(logAppender.list)
                    .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("SECRETPAYLOAD"));
        }

        @Test
        @DisplayName("형식은 맞지만 모르는 QR 이면 reason=NOT_FOUND, 토큰과 그 해시는 남기지 않는다")
        void logsNotFoundReasonWithoutTokenOrHash() {
            String token = "B".repeat(43);

            assertThatThrownBy(() -> checkInService.checkIn(storeId, "v1." + token, ADMIN))
                    .isInstanceOf(CustomException.class);

            assertThat(qrFailureLogs()).containsExactly("QR 체크인 실패 - storeId=" + storeId + ", reason=NOT_FOUND");
            assertThat(logAppender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                    .doesNotContain(token)
                    .doesNotContain(sha256Hex(token)));
        }

        @Test
        @DisplayName("재발급으로 바뀐 옛 QR 도 reason=NOT_FOUND — 옛 해시를 기억하지 않아 미발급과 구분하지 않는다")
        void logsNotFoundForOldPayloadAfterReissue() {
            String oldPayload = issue();
            issue();

            assertThatThrownBy(() -> checkInService.checkIn(storeId, oldPayload, ADMIN))
                    .isInstanceOf(CustomException.class);

            assertThat(qrFailureLogs()).containsExactly("QR 체크인 실패 - storeId=" + storeId + ", reason=NOT_FOUND");
        }

        @Test
        @DisplayName("성공하면 실패 로그를 남기지 않는다")
        void noFailureLogOnSuccess() {
            String payload = issue();

            checkInService.checkIn(storeId, payload, ADMIN);

            assertThat(qrFailureLogs()).isEmpty();
        }

        @Test
        @DisplayName("가게가 없으면 QR 을 보기 전에 끝나므로 QR 실패 로그가 없다")
        void noQrFailureLogWhenStoreMissing() {
            assertThatThrownBy(() -> checkInService.checkIn(MISSING_STORE_ID, "v1.!!!", ADMIN))
                    .isInstanceOf(CustomException.class);

            assertThat(qrFailureLogs()).isEmpty();
        }
    }
}
