package com.ktc4.backend.domain.child.entity;

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
 * 급식카드로 인증한 아동. 아동을 알아보는 값은 카드 번호의 해시 하나뿐이다.
 *
 * <p>카드 번호 원문은 저장하지 않는다 — 카드 번호는 앞자리(발급사)가 정해져 있어 그냥 해시하면 대입으로 되찾을 수 있으므로,
 * 비밀 키를 섞은 해시({@code CardNumberHasher})만 둔다. 이름·생년월일 같은 개인정보도 두지 않는다.
 *
 * <p>행은 {@code ChildRepository.insertIfAbsent} 로만 넣는다 — 같은 카드로 동시에 처음 인증해도 한 행만 생기게.
 */
@Entity
@Table(
        name = "child",
        uniqueConstraints = @UniqueConstraint(name = "uk_child_card_hash", columnNames = "card_hash")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Child extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "child_id")
    private Long childId;

    /** 카드 번호의 HMAC-SHA256 소문자 hex. 조회 키라 unique 다. */
    @Column(name = "card_hash", nullable = false, length = 64, updatable = false)
    private String cardHash;
}
