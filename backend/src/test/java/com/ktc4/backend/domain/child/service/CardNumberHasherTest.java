package com.ktc4.backend.domain.child.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CardNumberHasher")
class CardNumberHasherTest {

    // 테스트 전용 값. 32바이트 이상이어야 한다.
    private static final String SECRET = "test-only-card-hash-secret-at-least-32-bytes";
    private static final String CARD = "1234567812345678";

    private ListAppender<ILoggingEvent> logAppender;
    private Logger hasherLogger;

    @BeforeEach
    void attachLogAppender() {
        // Lombok @Slf4j 로거는 private static final 이라 Mockito 로 잡을 수 없다. ListAppender 로 실제 로그를 받는다.
        hasherLogger = (Logger) LoggerFactory.getLogger(CardNumberHasher.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        hasherLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        hasherLogger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("HMAC-SHA256 값이다 — 기대값은 운영 코드가 아니라 openssl 로 따로 계산했다")
    void matchesKnownHmac() {
        // printf '%s' 1234567812345678 | openssl dgst -sha256 -hmac "test-only-card-hash-secret-at-least-32-bytes"
        // 알고리즘이나 키를 섞는 방식이 바뀌면 이미 등록된 아동이 모두 새 아동이 된다 — 그런 변경을 여기서 잡는다
        assertThat(new CardNumberHasher(SECRET).hash(CARD))
                .isEqualTo("874fb8a58ec5c8d878b77583c93159c60434d1e7556c60eb74fe95a89d26ba13");
    }

    @Test
    @DisplayName("같은 번호는 같은 값이다 — 같은 카드면 같은 아동으로 찾는다")
    void sameCardSameHash() {
        CardNumberHasher hasher = new CardNumberHasher(SECRET);

        assertThat(hasher.hash(CARD)).isEqualTo(hasher.hash(CARD));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"1234 5678 1234 5678", "1234-5678-1234-5678", "1234 5678-1234 5678"})
    @DisplayName("숫자 사이의 공백·하이픈은 지우고 본다 — 띄어 쓴 같은 카드도 같은 아동")
    void ignoresSeparators(String card) {
        CardNumberHasher hasher = new CardNumberHasher(SECRET);

        assertThat(hasher.hash(card)).isEqualTo(hasher.hash(CARD));
    }

    @Test
    @DisplayName("키가 다르면 값이 다르다 — 키 없이는 DB 의 값으로 카드 번호를 맞혀 볼 수 없다")
    void differentKeyDifferentHash() {
        CardNumberHasher other = new CardNumberHasher("another-test-only-card-hash-secret-32-bytes");

        assertThat(other.hash(CARD)).isNotEqualTo(new CardNumberHasher(SECRET).hash(CARD));
    }

    @Test
    @DisplayName("값은 소문자 hex 64자다 — DB 칸 길이(64)와 맞는다")
    void lowercaseHex64() {
        assertThat(new CardNumberHasher(SECRET).hash(CARD)).matches("[0-9a-f]{64}");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"123456781234567", "12345678123456789", "1234 5678 1234 567a", "１２３４５６７８１２３４５６７８",
            "١٢٣٤٥٦٧٨١٢٣٤٥٦٧٨"})
    @DisplayName("숫자 16자리가 아니면 해시하지 않는다 — 전각·아라비아 숫자도 숫자로 보지 않는다")
    void rejectsNonCardNumber(String card) {
        CardNumberHasher hasher = new CardNumberHasher(SECRET);

        assertThatThrownBy(() -> hasher.hash(card)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("키가 없으면 쓸 수 없는 상태로 뜨고, 기동 때 ERROR 로 한 번 알린다 — 앱 전체는 멈추지 않는다")
    void unavailableWithoutSecret(String secret) {
        CardNumberHasher hasher = new CardNumberHasher(secret);

        assertThat(hasher.isAvailable()).isFalse();
        assertThat(logAppender.list).filteredOn(event -> event.getLevel() == Level.ERROR)
                .singleElement()
                .extracting(ILoggingEvent::getFormattedMessage)
                .asString().contains("CARD_HASH_SECRET");
        assertThatThrownBy(() -> hasher.hash(CARD)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("키가 있으면 쓸 수 있고, 키는 로그에 남기지 않는다")
    void availableWithSecret() {
        CardNumberHasher hasher = new CardNumberHasher(SECRET);

        assertThat(hasher.isAvailable()).isTrue();
        assertThat(logAppender.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains(SECRET));
    }

    @Test
    @DisplayName("키 앞뒤의 공백·줄바꿈은 무시한다 — env 파일 끝 줄바꿈 하나로 서버마다 다른 아동이 되지 않게")
    void stripsSecret() {
        assertThat(new CardNumberHasher(" " + SECRET + "\n").hash(CARD)).isEqualTo(new CardNumberHasher(SECRET).hash(CARD));
    }

    @Test
    @DisplayName("설정값(child.card-hash-secret)을 실제로 읽는다 — 없으면 뜨되 끄고, 짧으면 기동이 멈추고, 맞으면 켠다")
    void bindsFromConfiguration() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(CardNumberHasher.class);

        runner.run(context -> assertThat(context.getBean(CardNumberHasher.class).isAvailable()).isFalse());
        runner.withPropertyValues("child.card-hash-secret=short-secret")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("child.card-hash-secret=" + SECRET)
                .run(context -> assertThat(context.getBean(CardNumberHasher.class).hash(CARD))
                        .isEqualTo("874fb8a58ec5c8d878b77583c93159c60434d1e7556c60eb74fe95a89d26ba13"));
    }

    @Test
    @DisplayName("키가 32바이트보다 짧으면 기동을 막는다 — 비어 있는 것과 잘못 넣은 것은 다르다")
    void rejectsShortSecret() {
        assertThatThrownBy(() -> new CardNumberHasher("short-secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32바이트");
    }
}
