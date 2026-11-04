package com.ktc4.backend.domain.call.dto;

import java.util.List;

/**
 * 전화 한 통을 넣으라는 지시. 어디에 걸어 무엇을 물을지가 전부 담긴다.
 *
 * <p>가게 엔티티를 그대로 넘기지 않는 이유는 전화 API 에 나가는 값을 한 군데서 보이게 하려는 것이다 —
 * 가게에는 점주 개인 번호·사업자번호도 들어 있고, 그것들은 전화 API 에 보내지 않는다.
 *
 * @param storeName     매장 이름
 * @param storeLocation 매장 주소(도로명)
 * @param phoneNumber   전화를 걸 번호
 * @param questions     차례대로 물을 질문들
 */
public record CallOrder(String storeName, String storeLocation, String phoneNumber, List<CallQuestion> questions) {

    public CallOrder {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new IllegalArgumentException("걸 번호가 없습니다");
        }
        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("물을 것이 없으면 전화를 걸지 않습니다");
        }
        questions = List.copyOf(questions);
    }
}
