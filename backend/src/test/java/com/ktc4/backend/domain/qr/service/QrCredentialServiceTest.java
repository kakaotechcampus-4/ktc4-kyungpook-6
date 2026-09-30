package com.ktc4.backend.domain.qr.service;

import com.ktc4.backend.domain.qr.entity.QrCredential;
import com.ktc4.backend.domain.qr.repository.QrCredentialRepository;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QR 발급·해석을 실제 Postgres 에 붙여서 확인한다.
 *
 * <p>저장값은 {@code flush} → {@code clear} 뒤 다시 읽어 비교한다. 같은 트랜잭션에서 바로 읽으면
 * 1차 캐시의 자바 객체가 그대로 돌아와 컬럼 매핑이 틀려도 통과하기 때문이다.
 */
@Import(QrCredentialService.class)
@DisplayName("QrCredentialService")
class QrCredentialServiceTest extends PostgresContainerTest {

    private static final String PREFIX = "v1.";

    @Autowired
    private QrCredentialService qrCredentialService;

    @Autowired
    private QrCredentialRepository qrCredentialRepository;

    @Autowired
    private TestEntityManager entityManager;

    // 기대 해시는 운영 코드가 아니라 JDK 로 직접 계산한다 — 같은 함수로 만들면 자기 자신과 비교하게 된다.
    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String tokenOf(String qrPayload) {
        return qrPayload.substring(PREFIX.length());
    }

    private String issueAndClear(Long childId) {
        String payload = qrCredentialService.issue(childId).qrPayload();
        entityManager.flush();
        entityManager.clear();
        return payload;
    }

    @Nested
    @DisplayName("발급")
    class Issue {

        @Test
        @DisplayName("QR 문자열의 토큰을 SHA-256 한 값만 DB 에 저장한다")
        void storesSha256OfToken() {
            String payload = issueAndClear(7L);

            QrCredential saved = qrCredentialRepository.findByChildId(7L).orElseThrow();
            assertThat(payload).startsWith(PREFIX);
            assertThat(saved.getTokenHash()).isEqualTo(sha256Hex(tokenOf(payload)));
        }

        @Test
        @DisplayName("원문 토큰은 DB 어느 컬럼에도 남지 않는다")
        void neverStoresRawToken() {
            String token = tokenOf(issueAndClear(7L));

            @SuppressWarnings("unchecked")
            List<Object[]> rows = entityManager.getEntityManager()
                    .createNativeQuery("select * from qr_credential")
                    .getResultList();

            assertThat(rows).hasSize(1);
            assertThat(Arrays.toString(rows.get(0))).doesNotContain(token);
        }

        @Test
        @DisplayName("같은 아동이 다시 발급받으면 줄은 그대로 하나이고 해시만 새 토큰 것으로 바뀐다")
        void reissueReplacesHashInSameRow() {
            String first = issueAndClear(7L);
            Long firstId = qrCredentialRepository.findByChildId(7L).orElseThrow().getQrCredentialId();

            String second = issueAndClear(7L);

            QrCredential saved = qrCredentialRepository.findByChildId(7L).orElseThrow();
            assertThat(qrCredentialRepository.count()).isEqualTo(1);
            assertThat(saved.getQrCredentialId()).isEqualTo(firstId);
            assertThat(second).isNotEqualTo(first);
            assertThat(saved.getTokenHash()).isEqualTo(sha256Hex(tokenOf(second)));
        }

        @Test
        @DisplayName("아동마다 따로 저장된다")
        void storesPerChild() {
            issueAndClear(7L);
            issueAndClear(8L);

            assertThat(qrCredentialRepository.count()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("해석")
    class ResolveChildId {

        @Test
        @DisplayName("발급한 QR 문자열이면 그 아동 번호를 돌려준다")
        void resolvesIssuedPayload() {
            String payload = issueAndClear(7L);

            assertThat(qrCredentialService.resolveChildId(payload)).contains(7L);
        }

        @Test
        @DisplayName("재발급 뒤에는 옛 QR 은 거부되고 새 QR 만 통한다")
        void rejectsOldPayloadAfterReissue() {
            String oldPayload = issueAndClear(7L);
            String newPayload = issueAndClear(7L);

            assertThat(qrCredentialService.resolveChildId(oldPayload)).isEmpty();
            assertThat(qrCredentialService.resolveChildId(newPayload)).contains(7L);
        }

        @Test
        @DisplayName("형식은 맞지만 발급한 적 없는 토큰이면 빈 값이다")
        void rejectsUnknownToken() {
            issueAndClear(7L);

            assertThat(qrCredentialService.resolveChildId(PREFIX + "A".repeat(43))).isEmpty();
        }

        @Test
        @DisplayName("토큰을 한 글자 바꾸면 빈 값이다")
        void rejectsTamperedToken() {
            String payload = issueAndClear(7L);
            char last = payload.charAt(payload.length() - 1);
            String tampered = payload.substring(0, payload.length() - 1) + (last == 'A' ? 'B' : 'A');

            assertThat(qrCredentialService.resolveChildId(tampered)).isEmpty();
        }

        @Test
        @DisplayName("DB 에 저장된 해시 자체를 QR 로 보내도 빈 값이다 — DB 가 유출돼도 QR 을 만들 수 없다")
        void rejectsStoredHashUsedAsToken() {
            issueAndClear(7L);
            String storedHash = qrCredentialRepository.findByChildId(7L).orElseThrow().getTokenHash();

            assertThat(qrCredentialService.resolveChildId(PREFIX + storedHash)).isEmpty();
        }

        @Test
        @DisplayName("형식이 틀린 값은 DB 를 보기 전에 예외 없이 빈 값이다")
        void rejectsMalformedPayloadWithoutThrowing() {
            assertThat(qrCredentialService.resolveChildId("v1.!!!")).isEmpty();
            assertThat(qrCredentialService.resolveChildId(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("DB 제약 — 서비스 로직과 별개로 테이블이 스스로 막는다")
    class Constraints {

        // 서비스는 조회 후 수정이라 평소엔 중복이 생기지 않는다. 그래서 제약이 빠져도 위 테스트는 전부 통과한다.
        // 동시 요청처럼 서비스 로직을 우회하는 경우를 막는 건 제약뿐이라, 리포지토리로 직접 넣어 확인한다.

        @Test
        @DisplayName("같은 아동 번호로 두 줄을 넣으면 거절된다")
        void rejectsDuplicateChildId() {
            qrCredentialRepository.saveAndFlush(QrCredential.issue(7L, "a".repeat(64)));

            assertThatThrownBy(() -> qrCredentialRepository.saveAndFlush(QrCredential.issue(7L, "b".repeat(64))))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("같은 해시로 두 줄을 넣으면 거절된다 — 한 QR 이 두 아동으로 풀리면 안 된다")
        void rejectsDuplicateTokenHash() {
            qrCredentialRepository.saveAndFlush(QrCredential.issue(7L, "a".repeat(64)));

            assertThatThrownBy(() -> qrCredentialRepository.saveAndFlush(QrCredential.issue(8L, "a".repeat(64))))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
