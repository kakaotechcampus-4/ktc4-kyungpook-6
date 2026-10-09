package com.ktc4.backend.domain.auth.service;

import com.ktc4.backend.domain.auth.dto.LoginRequest;
import com.ktc4.backend.domain.auth.dto.LoginResponse;
import com.ktc4.backend.domain.auth.dto.MemberResponse;
import com.ktc4.backend.domain.auth.dto.OwnerSignupRequest;
import com.ktc4.backend.domain.auth.dto.OwnerSignupResponse;
import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.entity.OwnerInfo;
import com.ktc4.backend.domain.member.repository.MemberRepository;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.IssuedToken;
import com.ktc4.backend.global.security.JwtProvider;
import com.ktc4.backend.global.util.BizNoNormalizer;
import com.ktc4.backend.global.util.PhoneNormalizer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class AuthService {

    // BCrypt 는 앞 72바이트만 비교한다. 그보다 긴 비밀번호는 저장될 수 없으니 틀린 비밀번호와 같다.
    private static final int MAX_PASSWORD_BYTES = 72;

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    // 없는 이메일일 때도 비밀번호 비교를 한 번 해서, 응답 시간 차이로 가입 여부를 알아내지 못하게 한다.
    private final String dummyPasswordHash;

    public AuthService(MemberRepository memberRepository, PasswordEncoder passwordEncoder, JwtProvider jwtProvider) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtProvider = jwtProvider;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    /**
     * 이메일·비밀번호를 확인하고 접근 토큰을 발급한다.
     *
     * <p>이메일이 없을 때와 비밀번호가 틀릴 때 같은 에러를 준다 — 다르면 가입된 이메일을 하나씩 알아낼 수 있다.
     * 승인 대기·거절 안내는 비밀번호까지 맞았을 때만 준다. 비밀번호를 모르는 사람에게 계정 상태를 알려주지 않기 위해서다.
     *
     * @param request 이메일·비밀번호
     * @return 접근 토큰과 역할
     * @throws CustomException 이메일·비밀번호가 틀리면 {@code INVALID_CREDENTIALS},
     *                         승인 대기면 {@code OWNER_PENDING_APPROVAL}, 거절이면 {@code OWNER_REJECTED}
     */
    public LoginResponse login(LoginRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new CustomException(ErrorCode.INVALID_CREDENTIALS);
        }
        Optional<Member> found = memberRepository.findByEmail(Member.normalizeEmail(request.email()));
        String passwordHash = found.map(Member::getPasswordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), passwordHash);

        Member member = found.filter(ignored -> passwordMatches)
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_CREDENTIALS));
        switch (member.getStatus()) {
            case PENDING -> throw new CustomException(ErrorCode.OWNER_PENDING_APPROVAL);
            case REJECTED -> throw new CustomException(ErrorCode.OWNER_REJECTED);
            case APPROVED -> { }
        }

        IssuedToken token = jwtProvider.issue(member.getMemberId(), member.getRole());
        return LoginResponse.of(token, member.getRole());
    }

    /**
     * 점주 가입 신청을 받는다. 관리자가 승인하기 전까지 로그인할 수 없다.
     *
     * <p>사업자등록번호는 숫자 10자리로, 휴대폰 번호는 숫자만 남겨 저장한다. 둘 다 관리자가 가게를 정할 때
     * 후보 가게를 찾는 단서가 된다. 국세청 진위확인은 다음 단계에서 붙인다.
     *
     * <p>가입 상태 확인용 토큰을 함께 준다. 신청자가 승인됐는지 보려고 비밀번호로 로그인을 되풀이하지 않게 하기 위해서다.
     * 이 토큰으로는 자기 가입 상태만 읽을 수 있고, 승인된 뒤에도 접근 토큰으로 바뀌지 않는다 — 로그인해야 한다.
     *
     * @param request 이메일·비밀번호·사업자 정보·휴대폰 번호
     * @return 만들어진 계정(승인 대기)과 가입 상태 확인용 토큰
     * @throws CustomException 비밀번호가 72바이트를 넘으면 {@code PASSWORD_TOO_LONG},
     *                         사업자등록번호가 10자리가 아니면 {@code INVALID_BIZ_NO},
     *                         휴대폰 번호 형식이 아니면 {@code INVALID_PHONE},
     *                         이미 가입된 이메일이면 {@code DUPLICATE_EMAIL}
     */
    @Transactional
    public OwnerSignupResponse signupOwner(OwnerSignupRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new CustomException(ErrorCode.PASSWORD_TOO_LONG);
        }
        String bizNo = BizNoNormalizer.normalize(request.bizNo());
        if (!BizNoNormalizer.isValid(bizNo)) {
            throw new CustomException(ErrorCode.INVALID_BIZ_NO);
        }
        String phone = PhoneNormalizer.normalize(request.phone());
        if (!PhoneNormalizer.isValidMobile(phone)) {
            throw new CustomException(ErrorCode.INVALID_PHONE);
        }
        String email = Member.normalizeEmail(request.email());
        if (memberRepository.existsByEmail(email)) {
            throw new CustomException(ErrorCode.DUPLICATE_EMAIL);
        }

        Member applicant = Member.ownerApplicant(email, passwordEncoder.encode(request.password()),
                new OwnerInfo(bizNo, request.storeName().strip(), request.representativeName().strip(), phone));
        try {
            Member saved = memberRepository.saveAndFlush(applicant);
            return OwnerSignupResponse.of(saved, jwtProvider.issueSignupStatus(saved.getMemberId()));
        } catch (DataIntegrityViolationException e) {
            // 위 확인과 저장 사이에 같은 이메일로 동시에 가입한 경우. 이메일 유니크 제약이 최종 방어선이다.
            // 다른 제약 위반까지 "이미 가입된 이메일"로 바꾸면 원인을 잃으므로 그대로 던진다.
            if (isEmailUniqueViolation(e)) {
                throw new CustomException(ErrorCode.DUPLICATE_EMAIL);
            }
            throw e;
        }
    }

    // 원인 사슬에서 Hibernate 가 알려준 제약 이름을 찾는다. 이름이 바뀌면 이 구분이 조용히 무너지므로
    // 실제 Postgres 가 이 이름을 돌려주는지 MemberRepositoryTest 가 확인한다.
    static boolean isEmailUniqueViolation(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation) {
                return Member.EMAIL_UNIQUE_CONSTRAINT.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }

    /**
     * 로그인한 본인 정보를 조회한다. 가입 상태 확인용 토큰으로 부르면 승인 전 신청자의 현재 상태가 나온다.
     *
     * @param memberId 토큰에 담긴 회원 ID
     * @return 본인 정보
     * @throws CustomException 토큰 발급 뒤 계정이 지워졌으면 {@code MEMBER_NOT_FOUND}
     */
    public MemberResponse getMe(Long memberId) {
        return memberRepository.findById(memberId)
                .map(MemberResponse::from)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
    }
}
