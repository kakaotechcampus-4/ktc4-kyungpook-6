package com.ktc4.backend.domain.member.entity;

import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.enums.MemberStatus;
import com.ktc4.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 로그인하는 사람(관리자·점주).
 *
 * <p>관리자와 점주를 한 테이블에 두고 {@code role} 로 나눈다 — 로그인·토큰 발급 로직을 하나로 쓰기 위해서다.
 * 점주에게만 필요한 정보는 {@link OwnerInfo} 로 묶어 같은 테이블에 둔다.
 *
 * <p>점주는 가입 신청 시 {@link MemberStatus#PENDING} 으로 만들어지고, 관리자가 {@link #approve} 해야 로그인할 수 있다.
 * 관리자가 {@link #reject} 하면 로그인할 수 없는 채로 남는다.
 */
@Entity
@Table(
        name = "member",
        uniqueConstraints = @UniqueConstraint(name = Member.EMAIL_UNIQUE_CONSTRAINT, columnNames = "email")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseTimeEntity {

    /** 이메일 중복을 막는 DB 제약 이름. 가입 중 제약 위반을 "이미 가입된 이메일"로 바꿀 때 이 이름으로 구분한다. */
    public static final String EMAIL_UNIQUE_CONSTRAINT = "uk_member_email";

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

    /** 점주 정보. 관리자는 {@code null} 이다. */
    @Embedded
    private OwnerInfo ownerInfo;

    private Member(String email, String passwordHash, MemberRole role, MemberStatus status, OwnerInfo ownerInfo) {
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = status;
        this.ownerInfo = ownerInfo;
    }

    /**
     * 관리자 계정을 만든다. 관리자는 승인 절차가 없어 처음부터 {@link MemberStatus#APPROVED} 다.
     *
     * @param email        로그인 이메일
     * @param passwordHash 이미 암호화한 비밀번호
     * @return 저장 전 관리자 계정
     */
    public static Member admin(String email, String passwordHash) {
        return new Member(email, passwordHash, MemberRole.ADMIN, MemberStatus.APPROVED, null);
    }

    /**
     * 점주 가입 신청을 만든다. 관리자가 승인하기 전까지 {@link MemberStatus#PENDING} 이라 로그인할 수 없다.
     *
     * @param email        로그인 이메일
     * @param passwordHash 이미 암호화한 비밀번호
     * @param ownerInfo    사업자 정보
     * @return 저장 전 점주 가입 신청
     */
    public static Member ownerApplicant(String email, String passwordHash, OwnerInfo ownerInfo) {
        return new Member(email, passwordHash, MemberRole.OWNER, MemberStatus.PENDING, ownerInfo);
    }

    /**
     * 승인 대기 중인 점주를 승인한다.
     *
     * <p>호출자(서비스)가 먼저 상태를 확인해 알맞은 에러로 바꿔 준다. 여기서 한 번 더 막는 이유는, 다른 경로에서
     * 잘못 불려도 관리자나 이미 처리된 계정의 상태가 조용히 바뀌지 않게 하기 위해서다.
     *
     * @param approvedAt 승인 시각
     * @throws IllegalStateException 승인 대기 중인 점주가 아니면
     */
    public void approve(LocalDateTime approvedAt) {
        if (role != MemberRole.OWNER || status != MemberStatus.PENDING) {
            throw new IllegalStateException("승인 대기 중인 점주만 승인할 수 있습니다 - memberId=" + memberId);
        }
        this.status = MemberStatus.APPROVED;
        this.ownerInfo.markReviewed(approvedAt);
    }

    /**
     * 승인 대기 중인 점주의 가입을 거절한다. 거절된 계정은 로그인할 수 없다.
     *
     * <p>{@link #approve} 와 같은 이유로 여기서 한 번 더 막는다.
     *
     * @param rejectedAt 거절 시각
     * @param rejectedBy 거절한 관리자의 회원 ID
     * @throws IllegalStateException 승인 대기 중인 점주가 아니면
     */
    public void reject(LocalDateTime rejectedAt, Long rejectedBy) {
        if (role != MemberRole.OWNER || status != MemberStatus.PENDING) {
            throw new IllegalStateException("승인 대기 중인 점주만 거절할 수 있습니다 - memberId=" + memberId);
        }
        this.status = MemberStatus.REJECTED;
        this.ownerInfo.markRejected(rejectedAt, rejectedBy);
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
