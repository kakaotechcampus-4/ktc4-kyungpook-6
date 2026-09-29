package com.ktc4.backend.global.security;

import java.time.Instant;

/**
 * 발급한 접근 토큰과 만료 시각.
 *
 * @param value     토큰 문자열
 * @param expiresAt 만료 시각
 */
public record IssuedToken(String value, Instant expiresAt) {
}
