package com.ktc4.backend.domain.call.service;

import com.ktc4.backend.domain.call.dto.CallQuestion;
import com.ktc4.backend.domain.call.dto.CallQuestionField;
import com.ktc4.backend.domain.call.dto.CallQuestionType;
import com.ktc4.backend.domain.call.enums.CallField;
import com.ktc4.backend.domain.call.enums.CallReason;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * {@link CallQuestions} — 가게와 수정안을 보고 통화에서 물을 것을 정하는 규칙.
 *
 * <p>"무엇을 묻는가" 만큼 <b>"무엇을 묻지 않는가"</b> 를 함께 본다. 물을 수 없는 것을 질문으로 만들면
 * 가게에 전화가 가서 엉뚱한 것을 묻고, 그 답이 기록에 남는다 — 되돌릴 수 없는 종류의 실수다.
 */
@DisplayName("전화 질문 만들기 (CallQuestions)")
class CallQuestionsTest {

    private static Store store(StoreStatus status, String phone) {
        return Store.builder()
                .name("예시분식")
                .nameNormalized("예시분식")
                .addressRoad("대구광역시 북구 대학로 80")
                .addressNormalized("대구광역시 북구 대학로 80")
                .status(status)
                .phone(phone)
                .build();
    }

    @Nested
    @DisplayName("변화가 잡혔을 때 — 조사가 찾은 값이 맞는지 묻는다")
    class ChangeDetected {

        private List<CallQuestion> ask(Store store, Map<String, Object> proposed) {
            return CallQuestions.of(CallReason.CHANGE_DETECTED, store, proposed);
        }

        @Test
        @DisplayName("수정안 항목마다 확인 질문 하나를 만든다")
        void 수정안_항목마다_확인_질문() {
            List<CallQuestion> questions = ask(store(StoreStatus.OPEN, "053-111-1111"),
                    Map.of("status", "CLOSED", "name", "신나는분식"));

            assertThat(questions).hasSize(2);
            assertThat(questions).allSatisfy(question ->
                    assertThat(question.type()).isEqualTo(CallQuestionType.VALUE_CONFIRMATION));
            // 질문 id 는 요청 안에서 겹치지 않아야 한다
            assertThat(questions).extracting(CallQuestion::id).containsExactly("q1", "q2");
        }

        @Test
        @DisplayName("영업 여부를 가장 먼저 묻는다 — 한 가지만 듣고 끊겨도 그것이 남아야 한다")
        void 영업_여부를_먼저_묻는다() {
            List<CallQuestion> questions = ask(store(StoreStatus.OPEN, "053-111-1111"),
                    Map.of("name", "신나는분식", "addressRoad", "대구광역시 북구 대학로 1", "status", "CLOSED"));

            assertThat(questions).extracting(question -> question.fields().get(0).field())
                    .startsWith(CallField.OPEN);
        }

        @Test
        @DisplayName("영업 상태는 참·거짓으로 바꿔 묻는다 — 영업중에서 폐업 제안이면 true → false")
        void 영업_상태는_참거짓으로() {
            List<CallQuestion> questions = ask(store(StoreStatus.OPEN, "053-111-1111"),
                    Map.of("status", "CLOSED"));

            assertThat(questions).singleElement().satisfies(question -> {
                assertThat(question.fields()).extracting(CallQuestionField::field, CallQuestionField::currentValue)
                        .containsExactly(tuple(CallField.OPEN, "true"));
                assertThat(question.expectedValue()).isEqualTo("false");
            });
        }

        @Test
        @DisplayName("휴업에서 폐업으로 바뀐다는 제안은 묻지 않는다 — 둘 다 '영업하지 않음' 이라 전화로 가를 수 없다")
        void 휴업에서_폐업은_묻지_않는다() {
            assertThat(ask(store(StoreStatus.SUSPENDED, "053-111-1111"), Map.of("status", "CLOSED"))).isEmpty();
        }

        @Test
        @DisplayName("상태가 미확인이면 영업 여부를 묻지 않는다 — 모르는 것을 '영업 안 함' 으로 물으면 답이 뒤집힌다")
        void 미확인_상태는_묻지_않는다() {
            assertThat(ask(store(StoreStatus.UNKNOWN, "053-111-1111"), Map.of("status", "CLOSED"))).isEmpty();
        }

        @Test
        @DisplayName("현재 값이 없는 항목은 묻지 않는다 — '지금은 A 인데 B 가 맞습니까' 를 만들 수 없다")
        void 현재_값이_없으면_묻지_않는다() {
            assertThat(ask(store(StoreStatus.OPEN, null), Map.of("phone", "053-222-2222"))).isEmpty();
        }

        @Test
        @DisplayName("제안 값이 현재 값과 같으면 묻지 않는다")
        void 같은_값은_묻지_않는다() {
            assertThat(ask(store(StoreStatus.OPEN, "053-111-1111"), Map.of("phone", "053-111-1111"))).isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"모르는 상태 값, status, 망했음", "빈 값, name, ''"})
        @DisplayName("쓸 수 없는 제안 값은 묻지 않는다")
        void 쓸_수_없는_제안값(String label, String field, String value) {
            assertThat(ask(store(StoreStatus.OPEN, "053-111-1111"), Map.of(field, value))).isEmpty();
        }

        @Test
        @DisplayName("수정안이 비면 물을 것이 없다")
        void 수정안이_비면_빈_목록() {
            assertThat(ask(store(StoreStatus.OPEN, "053-111-1111"), Map.of())).isEmpty();
            assertThat(ask(store(StoreStatus.OPEN, "053-111-1111"), null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("90일 재확인일 때 — 등록된 값이 그대로인지 한 질문으로 묻는다")
    class PeriodicRecheck {

        private List<CallQuestion> ask(Store store) {
            // 재확인에는 짚을 값이 없다. 수정안을 넘겨도 쓰지 않는다
            return CallQuestions.of(CallReason.PERIODIC_RECHECK, store, Map.of("status", "CLOSED"));
        }

        @Test
        @DisplayName("한 질문에 영업 여부·상호명·매장 위치를 담는다")
        void 세_항목을_한_질문으로() {
            assertThat(ask(store(StoreStatus.OPEN, "053-111-1111"))).singleElement().satisfies(question -> {
                assertThat(question.id()).isEqualTo("q1");
                assertThat(question.type()).isEqualTo(CallQuestionType.CHANGE_DETECTION);
                assertThat(question.expectedValue()).isNull();
                assertThat(question.fields()).extracting(CallQuestionField::field)
                        .containsExactly(CallField.OPEN, CallField.STORE_NAME, CallField.STORE_LOCATION);
            });
        }

        @Test
        @DisplayName("전화번호는 묻지 않는다 — 그 번호로 연결된 것이 이미 답이다")
        void 전화번호는_묻지_않는다() {
            assertThat(ask(store(StoreStatus.OPEN, "053-111-1111")).get(0).fields())
                    .extracting(CallQuestionField::field)
                    .doesNotContain(CallField.PHONE_NUMBER);
        }

        @Test
        @DisplayName("상태가 미확인이면 영업 여부만 빠지고 나머지는 묻는다")
        void 미확인이면_영업_여부만_빠진다() {
            assertThat(ask(store(StoreStatus.UNKNOWN, "053-111-1111")).get(0).fields())
                    .extracting(CallQuestionField::field)
                    .containsExactly(CallField.STORE_NAME, CallField.STORE_LOCATION);
        }
    }
}
