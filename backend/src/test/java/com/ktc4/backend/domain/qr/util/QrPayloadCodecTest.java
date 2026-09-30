package com.ktc4.backend.domain.qr.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QR 문자열 형식({@code v1.<토큰>})과 토큰 해시 규칙.
 *
 * <p>여기서 받아주는 형식이 곧 점주 앱이 보내도 되는 값의 전부다. 형식 검사를 느슨하게 바꾸면
 * 아래 "받지 않는 값" 목록이 먼저 깨진다.
 */
@DisplayName("QrPayloadCodec")
class QrPayloadCodecTest {

    // 43글자 base64url. 32바이트를 패딩 없이 인코딩하면 항상 이 길이다.
    private static final String VALID_TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde";

    @Nested
    @DisplayName("토큰 발급")
    class IssueToken {

        @Test
        @DisplayName("base64url 문자로만 된 43글자 토큰을 만든다 — 32바이트를 패딩 없이 인코딩한 길이")
        void issuesBase64UrlTokenOf43Chars() {
            String token = QrPayloadCodec.issueToken();

            assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
        }

        @Test
        @DisplayName("부를 때마다 다른 토큰을 만든다 — 고정값이면 모든 아동이 같은 QR 을 갖게 된다")
        void issuesDifferentTokenEachTime() {
            assertThat(QrPayloadCodec.issueToken()).isNotEqualTo(QrPayloadCodec.issueToken());
        }

        @Test
        @DisplayName("발급한 토큰으로 만든 QR 문자열은 다시 같은 토큰으로 읽힌다")
        void roundTripsIssuedToken() {
            String token = QrPayloadCodec.issueToken();

            assertThat(QrPayloadCodec.extractToken(QrPayloadCodec.toPayload(token))).contains(token);
        }
    }

    @Nested
    @DisplayName("QR 문자열 읽기")
    class ExtractToken {

        @Test
        @DisplayName("v1. 뒤의 토큰을 꺼낸다")
        void extractsTokenAfterPrefix() {
            assertThat(QrPayloadCodec.extractToken("v1." + VALID_TOKEN)).contains(VALID_TOKEN);
        }

        @Test
        @DisplayName("QR 문자열은 v1. 접두사를 붙여 만든다")
        void buildsPayloadWithPrefix() {
            assertThat(QrPayloadCodec.toPayload(VALID_TOKEN)).isEqualTo("v1." + VALID_TOKEN);
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @NullAndEmptySource
        @MethodSource("com.ktc4.backend.domain.qr.util.QrPayloadCodecTest#malformedPayloads")
        @DisplayName("형식이 맞지 않으면 예외 없이 빈 값을 돌려준다")
        void returnsEmptyForMalformedPayload(String payload) {
            assertThat(QrPayloadCodec.extractToken(payload)).isEmpty();
        }
    }

    static Stream<String> malformedPayloads() {
        return Stream.of(
                VALID_TOKEN,                                   // 접두사 없음
                "v1.",                                         // 토큰 없음
                "v9." + VALID_TOKEN,                           // 모르는 버전
                "V1." + VALID_TOKEN,                           // 대문자 접두사
                "v1" + VALID_TOKEN,                            // 점 빠짐
                "v1." + VALID_TOKEN.substring(1),              // 42글자
                "v1." + VALID_TOKEN + "a",                     // 44글자
                "v1." + VALID_TOKEN.substring(3) + "!!!",      // base64url 이 아닌 문자
                "v1." + VALID_TOKEN.substring(2) + "+/",       // 표준 base64 문자 (url-safe 아님)
                "v1." + VALID_TOKEN.substring(1) + "=",        // 패딩 문자
                "v1." + VALID_TOKEN + " ",                     // 끝 공백
                "v1." + VALID_TOKEN + "\n",                    // 끝 개행
                " v1." + VALID_TOKEN,                          // 앞 공백
                "v1." + VALID_TOKEN + ".extra"                 // 뒤에 다른 조각
        );
    }

    @Nested
    @DisplayName("토큰 해시")
    class Hash {

        @Test
        @DisplayName("SHA-256 을 소문자 hex 64글자로 만든다 — 알려진 벡터(\"abc\")와 비교")
        void hashesWithSha256Hex() {
            // FIPS 180-2 부록의 SHA-256("abc") 값. 운영 코드로 기대값을 만들면 자기 자신과 비교하게 된다.
            assertThat(QrPayloadCodec.hash("abc"))
                    .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        }

        @Test
        @DisplayName("토큰이 한 글자만 달라도 해시가 다르다")
        void differsForDifferentToken() {
            String other = "B" + VALID_TOKEN.substring(1);

            assertThat(QrPayloadCodec.hash(VALID_TOKEN)).isNotEqualTo(QrPayloadCodec.hash(other));
        }
    }
}
