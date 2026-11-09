package com.ktc4.backend.domain.child.service;

import com.ktc4.backend.domain.child.dto.ChildAuthResponse;
import com.ktc4.backend.domain.child.repository.ChildRepository;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.AuthMember;
import com.ktc4.backend.global.security.JwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 카드 번호로 아동을 찾거나 만들고 토큰을 주는 흐름. 저장소만 가짜로 두고, 해시와 토큰은 실제 객체로 만든다 —
 * 돌려받은 토큰을 다시 풀어 "어느 아동의 토큰인지"를 확인하려고.
 *
 * <p>같은 카드로 동시에 처음 인증하는 경우는 트랜잭션이 실제로 겹쳐야 드러나서 {@code ChildAuthConcurrencyTest} 가 본다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChildAuthService")
class ChildAuthServiceTest {

    private static final String SECRET = "test-only-card-hash-secret-at-least-32-bytes";
    private static final Instant NOW = Instant.parse("2026-11-03T03:00:00Z");
    private static final String CARD = "1234 5678 1234 5678";

    @Mock
    private ChildRepository childRepository;

    private final CardNumberHasher hasher = new CardNumberHasher(SECRET);
    private final JwtProvider jwtProvider =
            new JwtProvider("test-only-jwt-secret-at-least-32-bytes-long", Duration.ofDays(7), Clock.fixed(NOW, ZoneOffset.UTC));

    private ChildAuthService service;

    @BeforeEach
    void setUp() {
        service = new ChildAuthService(childRepository, hasher, jwtProvider);
    }

    private AuthMember holderOf(ChildAuthResponse response) {
        return jwtProvider.parse(response.accessToken()).orElseThrow();
    }

    @Test
    @DisplayName("등록된 카드면 그 아동의 토큰을 주고, 새 아동을 넣지 않는다")
    void existingCard() {
        when(childRepository.findIdByCardHash(hasher.hash(CARD))).thenReturn(Optional.of(42L));

        ChildAuthResponse response = service.authenticate(CARD);

        assertThat(holderOf(response)).isEqualTo(AuthMember.child(42L));
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        verify(childRepository, never()).insertIfAbsent(anyString(), any());
    }

    @Test
    @DisplayName("처음 보는 카드면 해시로 아동을 넣고, 넣은 뒤 다시 찾은 번호로 토큰을 준다")
    void newCard() {
        String cardHash = hasher.hash(CARD);
        when(childRepository.findIdByCardHash(cardHash)).thenReturn(Optional.empty(), Optional.of(43L));

        ChildAuthResponse response = service.authenticate(CARD);

        assertThat(holderOf(response)).isEqualTo(AuthMember.child(43L));
        verify(childRepository).insertIfAbsent(eq(cardHash), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("저장소에는 카드 번호가 아니라 해시만 간다")
    void storesOnlyHash() {
        when(childRepository.findIdByCardHash(anyString())).thenReturn(Optional.empty(), Optional.of(43L));

        service.authenticate(CARD);

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(childRepository).insertIfAbsent(stored.capture(), any());
        assertThat(stored.getValue()).matches("[0-9a-f]{64}").doesNotContain("12345678");
    }

    @Test
    @DisplayName("넣은 뒤에도 못 찾으면 조용히 넘기지 않고 예외로 끝낸다 — 토큰을 엉뚱한 번호로 주지 않게")
    void failsLoudlyWhenInsertedRowMissing() {
        when(childRepository.findIdByCardHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.authenticate(CARD)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("해시 키가 없으면 503 card-auth-unavailable 이고, 저장소를 부르지 않는다")
    void unavailableWithoutKey() {
        ChildAuthService withoutKey = new ChildAuthService(childRepository, new CardNumberHasher(""), jwtProvider);

        assertThatThrownBy(() -> withoutKey.authenticate(CARD))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CARD_AUTH_UNAVAILABLE));
        verifyNoInteractions(childRepository);
    }
}
