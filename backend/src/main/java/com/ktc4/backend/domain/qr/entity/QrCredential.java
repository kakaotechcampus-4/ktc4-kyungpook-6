package com.ktc4.backend.domain.qr.entity;

import com.ktc4.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 아동이 본인임을 증명하는 QR 값의 서버 쪽 기록. 아동당 한 줄이다.
 *
 * <p>토큰 원문은 저장하지 않고 SHA-256 해시만 둔다. DB 가 통째로 유출돼도 해시로는 QR 을 만들 수 없다.
 * 아동의 이름·생년월일 같은 개인정보도 두지 않는다 — 체크인에 필요한 건 "등록된 아동인가" 하나뿐이다.
 *
 * <p>{@code childId} 는 아직 외래키가 없는 숫자다. 아동 식별(카드 등록)이 생기면 그 테이블에 FK 를 건다.
 * 마지막 발급 시각은 {@code updatedAt} 이다.
 */
@Entity
@Table(
        name = "qr_credential",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_qr_credential_child_id", columnNames = "child_id"),
                @UniqueConstraint(name = "uk_qr_credential_token_hash", columnNames = "token_hash")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QrCredential extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "qr_credential_id")
    private Long qrCredentialId;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    /** 토큰의 SHA-256 소문자 hex. 조회 키라 unique 다. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    private QrCredential(Long childId, String tokenHash) {
        this.childId = childId;
        this.tokenHash = tokenHash;
    }

    /**
     * 아동의 첫 QR 기록을 만든다.
     *
     * @param childId   아동 번호
     * @param tokenHash 토큰의 SHA-256 hex
     * @return 저장 전 기록
     */
    public static QrCredential issue(Long childId, String tokenHash) {
        return new QrCredential(childId, tokenHash);
    }

    /**
     * 재발급한 토큰의 해시로 바꾼다. 옛 토큰은 이 순간부터 조회되지 않는다.
     *
     * <p>줄을 지우고 새로 넣지 않고 같은 줄을 고친다. 삭제 후 삽입은 Hibernate 가 INSERT 를 먼저 보내
     * {@code child_id} unique 제약에 걸린다.
     *
     * @param tokenHash 새 토큰의 SHA-256 hex
     */
    public void reissue(String tokenHash) {
        this.tokenHash = tokenHash;
    }
}
