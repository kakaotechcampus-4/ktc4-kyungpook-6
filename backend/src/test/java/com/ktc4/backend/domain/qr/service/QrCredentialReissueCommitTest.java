package com.ktc4.backend.domain.qr.service;

import com.ktc4.backend.domain.qr.repository.QrCredentialRepository;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재발급이 테스트 트랜잭션 없이도 실제로 커밋되는지 확인한다.
 *
 * <p>{@code QrCredentialService} 는 클래스에 {@code @Transactional(readOnly = true)} 가 붙어 있다.
 * {@code issue} 에 쓰기 트랜잭션을 빠뜨리면 Hibernate 가 flush 를 하지 않아서, 재발급 응답에는 새 토큰이
 * 나가는데 DB 에는 옛 해시가 남는다. {@code @DataJpaTest} 의 기본 테스트 트랜잭션은 서비스를 감싸 버려서
 * 이 실수를 못 잡는다. 그래서 이 클래스만 테스트 트랜잭션을 끄고({@code NOT_SUPPORTED}) 서비스가 스스로
 * 연 트랜잭션의 결과를 본다.
 *
 * <p>롤백이 없으므로 다른 테스트와 겹치지 않는 아동 번호를 쓰고, 끝나면 직접 지운다.
 */
@Import(QrCredentialService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("QrCredentialService 재발급 커밋")
class QrCredentialReissueCommitTest extends PostgresContainerTest {

    private static final Long CHILD_ID = 900_001L;

    @Autowired
    private QrCredentialService qrCredentialService;

    @Autowired
    private QrCredentialRepository qrCredentialRepository;

    // 이 테스트가 넣은 줄만 지운다. 테이블 전체를 지우면 나중에 테스트를 병렬로 돌릴 때 다른 클래스 데이터까지 사라진다.
    @AfterEach
    void cleanUp() {
        qrCredentialRepository.findByChildId(CHILD_ID).ifPresent(qrCredentialRepository::delete);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("재발급한 해시가 커밋되어, 새로 읽어도 새 토큰의 해시다")
    void commitsReissuedHash() {
        qrCredentialService.issue(CHILD_ID);
        String reissued = qrCredentialService.issue(CHILD_ID).qrPayload();

        String storedHash = qrCredentialRepository.findByChildId(CHILD_ID).orElseThrow().getTokenHash();
        assertThat(storedHash).isEqualTo(sha256Hex(reissued.substring("v1.".length())));
    }
}
