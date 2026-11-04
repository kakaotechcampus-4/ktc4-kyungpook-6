package com.ktc4.backend.domain.child.service;

import com.ktc4.backend.domain.child.dto.ChildAuthResponse;
import com.ktc4.backend.domain.child.repository.ChildRepository;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.JwtProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChildAuthService {

    private final ChildRepository childRepository;
    private final CardNumberHasher cardNumberHasher;
    private final JwtProvider jwtProvider;

    /**
     * 급식카드 번호로 아동을 찾고, 처음 보는 카드면 아동을 만든 뒤 아동 토큰을 준다.
     *
     * <p>실제 급식카드인지는 확인하지 않는다 — 카드사 연동이 없는 시연용이라 형식(숫자 16자리)만 본다. 그래서 남의 카드 번호를
     * 넣으면 그 아동의 토큰을 받는다. 무차별 대입은 IP 별 횟수 제한({@code ChildCardAuthRateLimitFilter})이 늦춘다.
     *
     * <p>같은 카드로 동시에 처음 인증해도 아동은 하나만 생긴다({@link ChildRepository#insertIfAbsent}).
     *
     * @param cardNumber 카드 번호 — 숫자 16자리(사이에 공백·하이픈 하나씩 허용)
     * @return 아동 토큰. 아동 번호는 담지 않는다
     * @throws CustomException 해시 키가 없으면 {@code CARD_AUTH_UNAVAILABLE}
     */
    // 넣은 뒤 다시 찾는 흐름은 READ COMMITTED 에 기댄다 — 문장마다 새로 읽어 다른 요청이 커밋한 행을 본다.
    // Postgres 기본값이지만, 격리 수준을 올리면 그 행이 안 보여 깨지므로 여기 못박는다
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ChildAuthResponse authenticate(String cardNumber) {
        if (!cardNumberHasher.isAvailable()) {
            throw new CustomException(ErrorCode.CARD_AUTH_UNAVAILABLE);
        }
        String cardHash = cardNumberHasher.hash(cardNumber);
        Long childId = childRepository.findIdByCardHash(cardHash)
                .orElseGet(() -> register(cardHash));
        return ChildAuthResponse.of(jwtProvider.issueChild(childId));
    }

    private Long register(String cardHash) {
        int inserted = childRepository.insertIfAbsent(cardHash, LocalDateTime.now());
        Long childId = childRepository.findIdByCardHash(cardHash)
                // 넣었거나(1) 다른 요청이 먼저 넣고 커밋한 뒤라(0) 반드시 있다. 없으면 엉뚱한 토큰을 주지 않고 멈춘다
                .orElseThrow(() -> new IllegalStateException("아동을 넣은 뒤 찾지 못했습니다"));
        if (inserted == 1) {
            // 카드 번호·해시는 남기지 않는다 — 해시도 키가 새면 카드 번호를 맞혀 볼 단서가 된다
            log.info("새 아동 등록 - childId={}", childId);
        }
        return childId;
    }
}
