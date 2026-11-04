package com.ktc4.backend.domain.qr.service;

import com.ktc4.backend.domain.qr.dto.QrResolution;
import com.ktc4.backend.domain.qr.dto.QrTokenResponse;
import com.ktc4.backend.domain.qr.enums.QrRejectReason;
import com.ktc4.backend.domain.qr.repository.QrCredentialRepository;
import com.ktc4.backend.domain.qr.util.QrPayloadCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
     * <p>급식카드로 인증한 아동 본인만 부른다 — 아동 번호는 아동 토큰에서 온다({@code SecurityConfig}).
     *
     * <p>첫 발급과 재발급을 한 문장({@code INSERT … ON CONFLICT DO UPDATE})으로 한다. "찾고 없으면 저장"으로 하면
     * 첫 발급을 동시에 두 번 누를 때 하나가 아동당 한 줄 제약에 걸려 500 이 난다.
     *
     * @param childId 아동 번호
     * @return QR 에 담을 문자열({@code v1.<토큰>})
     */
    @Transactional
    public QrTokenResponse issue(Long childId) {
        String token = QrPayloadCodec.issueToken();
        String tokenHash = QrPayloadCodec.hash(token);

        qrCredentialRepository.upsert(childId, tokenHash, LocalDateTime.now());

        return new QrTokenResponse(QrPayloadCodec.toPayload(token));
    }

    /**
     * 점주 앱이 스캔한 QR 문자열이 어느 아동 것인지 찾는다.
     *
     * <p>다른 도메인(체크인)은 QR 형식을 모르고 이 메서드만 부른다. 30초마다 바뀌는 QR(v2)이 들어와도
     * 이 메서드 안쪽만 바뀐다.
     *
     * <p>형식이 틀리면 DB 를 보지 않고 바로 {@code FORMAT} 으로 끝난다.
     *
     * @param qrPayload 스캔한 문자열. {@code null} 일 수 있다
     * @return 아동 번호({@link QrResolution.Resolved}) 또는 실패 이유({@link QrResolution.Rejected}).
     *         예외를 던지지 않는다. 실패 이유는 로그용이고, 호출자는 응답에서 이유를 구분하지 않는다
     */
    public QrResolution resolveChildId(String qrPayload) {
        Optional<String> token = QrPayloadCodec.extractToken(qrPayload);
        if (token.isEmpty()) {
            return new QrResolution.Rejected(QrRejectReason.FORMAT);
        }
        return qrCredentialRepository.findByTokenHash(QrPayloadCodec.hash(token.get()))
                .<QrResolution>map(credential -> new QrResolution.Resolved(credential.getChildId()))
                .orElseGet(() -> new QrResolution.Rejected(QrRejectReason.NOT_FOUND));
    }
}
