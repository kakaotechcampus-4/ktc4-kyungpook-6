package com.ktc4.backend.domain.member.entity;

import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.enums.MemberStatus;
import com.ktc4.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Locale;

/**
 * 로그인하는 사람(관리자·점주).
 *
 * <p>관리자와 점주를 한 테이블에 두고 {@code role} 로 나눈다 — 로그인·토큰 발급 로직을 하나로 쓰기 위해서다.
 * 점주에게만 필요한 정보(사업자등록번호 등)는 가입 신청 기능과 함께 추가한다.
 */
@Entity
@Table(
        name = "member",
        uniqueConstraints = @UniqueConstraint(name = "uk_member_email", columnNames = "email")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    private Long memberId;

    /** 로그인 아이디. {@link #normalizeEmail} 로 맞춰 저장한다 — 대소문자만 다른 중복 가입을 막기 위해서다. */
    @Column(name = "email", nullable = false, length = 254)
    private String email;

    /** BCrypt 해시. 원문 비밀번호는 어디에도 저장하지 않는다. */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private MemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MemberStatus status;

    private Member(String email, String passwordHash, MemberRole role, MemberStatus status) {
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = status;
    }

    /**
     * 관리자 계정을 만든다. 관리자는 승인 절차가 없어 처음부터 {@link MemberStatus#APPROVED} 다.
     *
     * @param email        로그인 이메일
     * @param passwordHash 이미 암호화한 비밀번호
     * @return 저장 전 관리자 계정
     */
    public static Member admin(String email, String passwordHash) {
        return new Member(email, passwordHash, MemberRole.ADMIN, MemberStatus.APPROVED);
    }

    /**
     * 저장·조회에 쓰는 이메일 표기로 맞춘다. 앞뒤 공백을 지우고 소문자로 바꾼다.
     *
     * @param email 입력받은 이메일
     * @return 맞춘 이메일. {@code null} 이면 {@code null}
     */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }
}
