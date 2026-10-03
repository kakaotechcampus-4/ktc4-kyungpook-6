package com.ktc4.backend.domain.qr.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 아동 QR 에 담기는 문자열({@code v1.<토큰>})을 만들고 읽는다.
 *
 * <p>토큰은 {@link SecureRandom} 32바이트(256bit)를 base64url(패딩 없음)로 인코딩한 43글자다.
 * 아동 번호 같은 의미 있는 값을 넣지 않는다 — QR 은 암호가 아니라 아무 카메라로나 읽히기 때문이다.
 *
 * <p>{@code v1.} 은 형식 버전이다. 나중에 30초마다 바뀌는 QR({@code v2.})이 들어와도 옛 앱이 보낸 값과
 * 구분할 수 있게 둔다(결제 QR 표준의 {@code CPV01}, GitHub 토큰의 {@code ghp_} 와 같은 발상).
 *
 * <p>DB 에는 토큰 원문이 아니라 {@link #hash} 값만 저장한다. 256bit 랜덤 값이라 추측할 수 없으므로
 * 비밀번호처럼 느린 해시(bcrypt)를 쓸 이유가 없고, 해시로 바로 조회할 수 있어야 한다.
 */
public final class QrPayloadCodec {

    private static final String PREFIX = "v1.";
    private static final int TOKEN_BYTES = 32;

    // 32바이트를 패딩 없이 base64url 로 인코딩하면 항상 43글자다. 이 형식이 아니면 DB 를 볼 필요도 없다.
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    // SecureRandom 은 스레드 안전하다. 요청마다 새로 만들면 시드 초기화 비용만 든다.
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private QrPayloadCodec() {
    }

    /**
     * 새 토큰을 만든다.
     *
     * @return base64url(패딩 없음) 43글자 토큰
     */
    public static String issueToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /**
     * 토큰에 형식 버전을 붙여 QR 에 담을 문자열을 만든다.
     *
     * @param token {@link #issueToken} 으로 만든 토큰
     * @return {@code v1.<토큰>}
     */
    public static String toPayload(String token) {
        return PREFIX + token;
    }

    /**
     * QR 문자열에서 토큰을 꺼낸다. 앞뒤 공백도 형식 오류로 본다 — 스캐너가 붙인 값을 조용히 고치지 않는다.
     *
     * @param payload 점주 앱이 스캔해서 보낸 문자열. {@code null} 일 수 있다
     * @return 형식이 맞으면 토큰, 아니면 빈 값. 예외를 던지지 않는다
     */
    public static Optional<String> extractToken(String payload) {
        if (payload == null || !payload.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String token = payload.substring(PREFIX.length());
        return TOKEN_FORMAT.matcher(token).matches() ? Optional.of(token) : Optional.empty();
    }

    /**
     * 토큰을 DB 에 저장·조회할 해시로 바꾼다.
     *
     * @param token 토큰 원문
     * @return SHA-256 소문자 hex 64글자
     */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // 모든 JDK 구현은 SHA-256 을 반드시 제공해야 한다(MessageDigest 명세). 여기 오면 JDK 가 깨진 것이다.
            throw new IllegalStateException("SHA-256 을 지원하지 않는 JDK 입니다", e);
        }
    }
}
