package com.ktc4.backend.domain.child.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 급식카드 번호를 저장해도 되는 값(HMAC-SHA256, 소문자 hex 64자)으로 바꾼다.
 *
 * <p>카드 번호는 16자리지만 앞 6~8자리(발급사)가 정해져 있어 실제로 맞혀야 하는 자리가 적다. 그냥 SHA-256 이면 DB 만 새도
 * 대입으로 원래 번호를 찾는다. 비밀 키를 섞으면 키 없이는 못 찾는다. 같은 번호 → 같은 값이라 조회는 그대로 된다.
 * PCI DSS v4.0 요구사항 3.5.1.1 도 카드 번호를 해시로 남길 땐 키를 쓴 해시여야 한다고 정한다.
 *
 * <p>키({@code CARD_HASH_SECRET})는 한 번 정하면 바꾸지 않는다 — 바꾸면 같은 카드가 다른 값이 되어 모든 아동이 새 아동이 된다.
 *
 * <p>키가 없으면 앱은 뜨고 이 기능만 멈춘다({@link #isAvailable()} 이 false). 기동을 막으면 설정 전에 머지된 개발 서버가
 * 통째로 멈추고, JWT 처럼 임의 키를 쓰면 재시작마다 같은 카드가 다른 아동이 되는 조용한 데이터 오류가 생긴다.
 * 대신 기동 때 ERROR 로 알린다. 키가 짧으면 잘못 넣은 것이라 기동을 막는다.
 */
@Slf4j
@Component
public class CardNumberHasher {

    // HMAC-SHA256 키는 해시 출력 길이(32바이트) 이상을 권장한다(RFC 2104 §3). JWT 키와 같은 기준이다.
    private static final int MIN_SECRET_BYTES = 32;
    private static final String ALGORITHM = "HmacSHA256";
    // 숫자 16개. 숫자 사이의 공백·하이픈 하나씩은 허용한다 — 요청 DTO 의 규칙과 같다. [0-9] 로 적어 다른 문자의 숫자는 받지 않는다
    private static final Pattern CARD_NUMBER = Pattern.compile("[0-9](?:[ -]?[0-9]){15}");
    private static final Pattern SEPARATORS = Pattern.compile("[ -]");

    private final SecretKeySpec key;

    /**
     * @param secret 해시에 섞는 비밀 키. 비어 있으면 카드 인증을 끈다
     * @throws IllegalStateException 키가 32바이트보다 짧으면
     */
    public CardNumberHasher(@Value("${child.card-hash-secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            log.error("CARD_HASH_SECRET 이 없어 아동 카드 인증을 끕니다 — 카드 인증은 503 으로 응답합니다. "
                    + "배포 환경에는 32바이트 이상 값을 넣으세요. 한 번 정한 값은 바꾸면 안 됩니다");
            this.key = null;
            return;
        }
        // 앞뒤 공백·줄바꿈은 지운다 — env 파일 끝의 줄바꿈 하나로 서버마다 다른 키가 되면 같은 카드가 다른 아동이 된다
        byte[] bytes = secret.strip().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "CARD_HASH_SECRET 은 " + MIN_SECRET_BYTES + "바이트 이상이어야 합니다 (현재 " + bytes.length + "바이트)");
        }
        this.key = new SecretKeySpec(bytes, ALGORITHM);
    }

    /**
     * 키가 있어 해시할 수 있는지 답한다.
     *
     * @return 키가 있으면 true
     */
    public boolean isAvailable() {
        return key != null;
    }

    /**
     * 카드 번호를 해시한다. 숫자 사이의 공백·하이픈은 지우고 본다.
     *
     * @param cardNumber 카드 번호 — 숫자 16자리(사이에 공백·하이픈 하나씩 허용)
     * @return HMAC-SHA256 소문자 hex 64자
     * @throws IllegalArgumentException 숫자 16자리가 아니면. 요청 검증을 지나 온 값이면 생기지 않는다
     * @throws IllegalStateException    키가 없으면({@link #isAvailable()} 을 먼저 볼 것)
     */
    public String hash(String cardNumber) {
        if (key == null) {
            throw new IllegalStateException("CARD_HASH_SECRET 이 없어 카드 번호를 해시할 수 없습니다");
        }
        if (cardNumber == null || !CARD_NUMBER.matcher(cardNumber).matches()) {
            // 받은 값은 남기지 않는다 — 카드 번호일 수 있다
            throw new IllegalArgumentException("카드 번호는 숫자 16자리여야 합니다");
        }
        String digits = SEPARATORS.matcher(cardNumber).replaceAll("");
        try {
            // Mac 은 여러 스레드가 함께 쓸 수 없어 부를 때마다 만든다
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(digits.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            // HmacSHA256 은 모든 JDK 가 갖춰야 하는 알고리즘이라 여기 올 일이 없다
            throw new IllegalStateException("HmacSHA256 을 쓸 수 없습니다", e);
        }
    }
}
