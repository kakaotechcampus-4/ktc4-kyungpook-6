package com.ktc4.backend.domain.qr.dto;

import com.ktc4.backend.domain.qr.enums.QrRejectReason;

/**
 * QR 문자열을 해석한 결과. 성공이면 {@link Resolved}(아동 번호), 실패면 {@link Rejected}(이유) 둘 중 하나다.
 *
 * <p>{@code sealed} 라서 이 두 가지 말고는 만들 수 없다. 받는 쪽은 {@code switch} 로 두 경우를 나눈다.
 * {@code Optional} 은 "없다"만 담고 왜 없는지는 담지 못해서 이 타입을 쓴다.
 *
 * <ul>
 *   <li><b>실패 이유가 늘 때</b>(예: 30초 QR 의 만료·재사용): {@link QrRejectReason} 에 값만 더한다.
 *       {@code Rejected(EXPIRED)} 처럼 담기므로 받는 쪽 코드는 그대로다.</li>
 *   <li><b>결과 종류 자체가 늘 때</b>(이 인터페이스에 새 record 를 더할 때): {@code default} 없는 {@code switch} 에서
 *       처리하지 않은 곳을 컴파일러가 알려준다.</li>
 * </ul>
 *
 * <pre>{@code
 * switch (qrCredentialService.resolveChildId(payload)) {
 *     case QrResolution.Resolved resolved -> resolved.childId();
 *     case QrResolution.Rejected rejected -> ...; // rejected.reason()
 * }
 * }</pre>
 */
public sealed interface QrResolution {

    /** 해석 성공. 등록된 아동의 번호다. */
    record Resolved(Long childId) implements QrResolution {
    }

    /** 해석 실패. 이유는 로그용이며 응답에는 싣지 않는다. */
    record Rejected(QrRejectReason reason) implements QrResolution {
    }
}
