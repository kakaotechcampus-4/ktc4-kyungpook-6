package com.ktc4.backend.domain.auth.service;

import com.ktc4.backend.domain.auth.dto.LoginRequest;
import com.ktc4.backend.domain.auth.dto.LoginResponse;
import com.ktc4.backend.domain.auth.dto.MemberResponse;
import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.repository.MemberRepository;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.IssuedToken;
import com.ktc4.backend.global.security.JwtProvider;
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
     * 로그인한 본인 정보를 조회한다.
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
