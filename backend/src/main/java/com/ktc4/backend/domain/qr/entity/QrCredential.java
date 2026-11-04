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
 * <p>{@code childId} 는 {@code child} 표의 아동 번호지만 외래키는 없다 — 아동 인증이 생기기 전에 관리자가 아무 번호로
 * 발급한 행이 배포본에 남아 있을 수 있어서다({@code V6__create_child.sql}). 마지막 발급 시각은 {@code updatedAt} 이다.
 *
 * <p>행은 {@code QrCredentialRepository.upsert} 한 문장으로만 넣고 바꾼다 — 같은 아동의 첫 발급이 동시에 와도 한 줄이게.
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
}
