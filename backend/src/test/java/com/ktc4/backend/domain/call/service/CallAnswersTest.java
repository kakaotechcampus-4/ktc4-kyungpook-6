package com.ktc4.backend.domain.call.service;

import com.ktc4.backend.domain.call.entity.CallAnswer;
import com.ktc4.backend.domain.store.dto.StoreUpdateRequest;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CallAnswers} — 통화에서 들은 답 중 <b>가게에 그대로 반영할 것</b>을 고른다.
 *
 * <p>여기가 어긋나면 담당자가 모르는 사이에 가게 정보가 바뀐다. 그래서 "무엇을 반영하는가" 보다
 * <b>"무엇을 반영하지 않는가"</b> 를 더 많이 본다.
 */
@DisplayName("전화 답 반영 고르기 (CallAnswers)")
class CallAnswersTest {

    private static Store store() {
        return Store.builder()
                .name("예시분식")
                .nameNormalized("예시분식")
                .addressRoad("대구광역시 북구 대학로 80")
                .addressNormalized("대구광역시 북구 대학로 80")
                .status(StoreStatus.OPEN)
                .phone("053-111-1111")
                .build();
    }

    private static CallAnswer answer(String field, String value) {
        return new CallAnswer("q1", field, value);
    }

    @Test
    @DisplayName("바뀐 값은 반영한다")
    void 바뀐_값() {
        Map<String, Object> applied = CallAnswers.toApply(store(), List.of(
                answer("storeName", "신나는 분식"),
                answer("phoneNumber", "053-222-2222")));

        assertThat(applied).containsExactlyInAnyOrderEntriesOf(
                Map.of("name", "신나는 분식", "phone", "053-222-2222"));
    }

    @Test
    @DisplayName("영업 여부는 반영하지 않는다 — 휴업인지 폐업인지 전화로는 가릴 수 없다")
    void 영업_여부는_반영하지_않는다() {
        /*
          "안 합니다" 를 들어도 휴업인지 폐업인지 모른다. 게다가 정기휴무일에 받은 사장님은
          "오늘은 안 해요" 라고 답한다 — 그걸 폐업으로 찍으면 멀쩡한 가게가 목록에서 사라진다.
        */
        assertThat(CallAnswers.toApply(store(), List.of(answer("open", "false")))).isEmpty();
        assertThat(CallAnswers.toApply(store(), List.of(answer("open", "true")))).isEmpty();
    }

    @Test
    @DisplayName("영업 여부가 섞여 있어도 나머지만 반영한다")
    void 섞여_있어도_나머지만() {
        Map<String, Object> applied = CallAnswers.toApply(store(), List.of(
                answer("open", "false"),
                answer("storeName", "신나는 분식")));

        assertThat(applied).containsOnlyKeys("name");
    }

    @Test
    @DisplayName("지금 값과 같으면 반영하지 않는다 — 빈 변경 기록을 남기지 않는다")
    void 같은_값() {
        assertThat(CallAnswers.toApply(store(), List.of(
                answer("storeName", "예시분식"),
                answer("storeLocation", "대구광역시 북구 대학로 80")))).isEmpty();
    }

    @ParameterizedTest(name = "값이 [{0}]")
    @ValueSource(strings = {"", "   "})
    @DisplayName("빈 값은 반영하지 않는다 — 전화로 가게 이름을 지울 수는 없다")
    void 빈_값(String value) {
        assertThat(CallAnswers.toApply(store(), List.of(answer("storeName", value)))).isEmpty();
    }

    @Test
    @DisplayName("값이 없는 답도 반영하지 않는다")
    void 값이_null() {
        assertThat(CallAnswers.toApply(store(), List.of(answer("storeName", null)))).isEmpty();
    }

    @Test
    @DisplayName("우리가 묻지 않은 항목은 반영하지 않는다 — 화면에는 그대로 보여 준다")
    void 모르는_항목() {
        assertThat(CallAnswers.toApply(store(), List.of(answer("ownerAge", "40")))).isEmpty();
    }

    @Test
    @DisplayName("같은 항목을 두 번 들었으면 나중 답을 쓴다 — 통화에서 고쳐 말한 경우다")
    void 두_번_들었을_때() {
        Map<String, Object> applied = CallAnswers.toApply(store(), List.of(
                answer("storeName", "신나는분식"),
                answer("storeName", "신나는 분식")));

        assertThat(applied).containsEntry("name", "신나는 분식");
    }

    @Test
    @DisplayName("답이 없으면 빈 결과다")
    void 답이_없을_때() {
        assertThat(CallAnswers.toApply(store(), List.of())).isEmpty();
        assertThat(CallAnswers.toApply(store(), null)).isEmpty();
    }

    @Test
    @DisplayName("가게 수정 요청에는 영업 상태를 절대 싣지 않는다")
    void 수정_요청에_상태가_없다() {
        // toApply 가 이미 걸렀지만, 여기서도 막아 전화가 영업 상태를 바꾸는 길이 아예 없게 한다
        StoreUpdateRequest request = CallAnswers.toUpdateRequest(
                Map.of("name", "신나는 분식", "status", "CLOSED"));

        assertThat(request.status()).isNull();
        assertThat(request.name()).isEqualTo("신나는 분식");
    }
}
