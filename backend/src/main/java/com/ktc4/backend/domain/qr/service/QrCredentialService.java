package com.ktc4.backend.domain.qr.service;

import com.ktc4.backend.domain.qr.dto.QrTokenResponse;
import com.ktc4.backend.domain.qr.entity.QrCredential;
import com.ktc4.backend.domain.qr.repository.QrCredentialRepository;
import com.ktc4.backend.domain.qr.util.QrPayloadCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QrCredentialService {

    private final QrCredentialRepository qrCredentialRepository;

    /**
     * 아동에게 QR 문자열을 발급한다. 이미 발급받은 아동이면 재발급이고, 옛 QR 은 바로 무효가 된다.
     *
     * <p>토큰 원문은 반환값으로만 나가고 DB 에는 해시만 남는다.
     *
     * <p>⚠️ 지금은 누구나 아무 {@code childId} 로 부를 수 있다. 카드 등록(아동 인증)이 생기면
     * 그 아동 본인만 부르도록 컨트롤러에서 막아야 한다 — 실제 아동 사용자를 받기 전에 반드시.
     *
     * @param childId 아동 번호
     * @return QR 에 담을 문자열({@code v1.<토큰>})
     */
    @Transactional
    public QrTokenResponse issue(Long childId) {
        String token = QrPayloadCodec.issueToken();
        String tokenHash = QrPayloadCodec.hash(token);

        qrCredentialRepository.findByChildId(childId)
                .ifPresentOrElse(
                        credential -> credential.reissue(tokenHash),
                        () -> qrCredentialRepository.save(QrCredential.issue(childId, tokenHash)));

        return new QrTokenResponse(QrPayloadCodec.toPayload(token));
    }

    /**
     * 점주 앱이 스캔한 QR 문자열이 어느 아동 것인지 찾는다.
     *
     * <p>다른 도메인(체크인)은 QR 형식을 모르고 이 메서드만 부른다. 30초마다 바뀌는 QR(v2)이 들어와도
     * 이 메서드 안쪽만 바뀐다.
     *
     * @param qrPayload 스캔한 문자열. {@code null} 일 수 있다
     * @return 아동 번호. 형식 오류, 발급한 적 없는 토큰, 재발급으로 바뀐 옛 토큰이면 전부 빈 값이며
     *         예외를 던지지 않는다 — 호출자가 어떤 경우인지 구분하지 않게 하기 위해서다
     */
    public Optional<Long> resolveChildId(String qrPayload) {
        return QrPayloadCodec.extractToken(qrPayload)
                .map(QrPayloadCodec::hash)
                .flatMap(qrCredentialRepository::findByTokenHash)
                .map(QrCredential::getChildId);
    }
}
