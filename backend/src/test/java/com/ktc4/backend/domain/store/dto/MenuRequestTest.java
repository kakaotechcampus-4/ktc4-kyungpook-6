package com.ktc4.backend.domain.store.dto;

import com.ktc4.backend.domain.auth.dto.SignupMenuRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 메뉴 한 개의 검증. 메뉴 이름은 관리자 화면과 아동 앱에 그대로 보이므로 상호명과 같은 글자 규칙을 쓴다.
 * 가입 때의 메뉴({@link SignupMenuRequest})와 가게 관리 저장의 메뉴({@link MenuRequest})가 같은 이름 규칙인지 함께 본다.
 */
@DisplayName("메뉴 검증")
class MenuRequestTest {

    private static final String NAME_AT_MAX = "가".repeat(MenuRequest.MAX_NAME_LENGTH);

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static Set<String> invalidFields(Object request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    @ParameterizedTest
    @ValueSource(strings = {"김치찌개", "후라이드 치킨", "A", "1인 세트", "떡볶이(소)", "a\u0301\u0301"})
    @DisplayName("평범한 메뉴 이름은 통과한다 — 겹친 부호는 두 개까지")
    void acceptsOrdinaryNames(String name) {
        assertThat(invalidFields(new MenuRequest(null, name, true))).isEmpty();
        assertThat(invalidFields(new SignupMenuRequest(name))).isEmpty();
    }

    @Test
    @DisplayName("메뉴 이름은 50자까지 받는다")
    void acceptsNameAtMaxLength() {
        assertThat(invalidFields(new MenuRequest(null, NAME_AT_MAX, true))).isEmpty();
        assertThat(invalidFields(new SignupMenuRequest(NAME_AT_MAX))).isEmpty();
    }

    @Test
    @DisplayName("메뉴 이름이 51자면 거절한다")
    void rejectsTooLongName() {
        assertThat(invalidFields(new MenuRequest(null, NAME_AT_MAX + "가", true))).containsExactly("name");
        assertThat(invalidFields(new SignupMenuRequest(NAME_AT_MAX + "가"))).containsExactly("name");
    }

    // 빈 값, 공백만, 전각 공백만(@NotBlank 는 통과하지만 글자가 없다), 기호만, 줄바꿈, 한글 채움 문자, 폭 없는 공백, 겹친 부호
    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "　", "---", "김치\n찌개", "ㅤㅤ", "김치​찌개", "á́́"})
    @DisplayName("비었거나, 글자가 없거나, 보이지 않는 글자가 섞인 이름은 거절한다")
    void rejectsBlankOrInvisibleNames(String name) {
        assertThat(invalidFields(new MenuRequest(null, name, true))).containsExactly("name");
        assertThat(invalidFields(new SignupMenuRequest(name))).containsExactly("name");
    }

    @Test
    @DisplayName("판매 여부가 빠지면 거절한다 — 조용히 '판매 안 함'이 되지 않게")
    void requiresOnSale() {
        assertThat(invalidFields(new MenuRequest(1L, "김치찌개", null))).containsExactly("onSale");
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    @DisplayName("메뉴 ID 는 양수만 받는다. 새 메뉴는 ID 를 비운다")
    void rejectsNonPositiveMenuId(long menuId) {
        assertThat(invalidFields(new MenuRequest(menuId, "김치찌개", true))).containsExactly("menuId");
        assertThat(invalidFields(new MenuRequest(null, "김치찌개", false))).isEmpty();
    }
}
