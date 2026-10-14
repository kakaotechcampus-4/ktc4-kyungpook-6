package com.ktc4.backend.domain.member.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ktc4.backend.domain.store.enums.BusinessDay;

/**
 * 가입 신청서에 적힌 영업시간 한 줄 — 신청 당시 모양 그대로 {@link OwnerInfo} 의 JSON 칸에 담긴다.
 *
 * <p>시간은 받은 {@code "HH:mm"} 문자열 그대로 둔다({@code "24:00"} 포함). 분으로 바꾸는 것은 가게로 옮길 때다.
 *
 * <p>모르는 칸은 무시하고 읽는다. Hibernate 는 JSON 칸을 Spring 설정이 아닌 자기 {@code ObjectMapper}(모르는 칸이면
 * 실패)로 읽어서, 나중에 칸이 하나 늘어난 기록을 옛 코드가 읽으면 그 회원의 로그인·신청 목록이 깨진다.
 *
 * @param day   요일
 * @param open  여는 시간 ({@code "HH:mm"})
 * @param close 닫는 시간 ({@code "HH:mm"}, 자정이면 {@code "24:00"})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SignupBusinessHour(BusinessDay day, String open, String close) {
}
