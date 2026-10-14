package com.ktc4.backend.domain.member.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 가입 신청서에 적힌 메뉴 한 개 — 신청 당시 모양 그대로 {@link OwnerInfo} 의 JSON 칸에 담긴다.
 *
 * <p>모르는 칸을 무시하고 읽는 이유는 {@link SignupBusinessHour} 와 같다.
 *
 * @param name 메뉴 이름
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SignupMenu(String name) {
}
