package com.ktc4.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요청·응답 DTO 이름이 저장소 전체에서 겹치지 않는지 본다.
 *
 * <p><b>왜 패키지가 다른데도 겹치면 안 되나</b> — springdoc 은 스웨거 스키마 이름을
 * **클래스 단순 이름**으로 짓는다. 패키지가 달라도 스웨거에서는 한 이름이 되어, 먼저 잡힌 쪽만 남고
 * 나머지는 통째로 덮인다. 덮인 쪽 API 는 문서에 다른 모양으로 나가고, 그 문서로 타입을 만드는
 * 프론트(Orval)에는 칸이 빠진 타입이 생긴다. 컴파일은 통과하므로 돌려 보기 전까지 안 보인다.
 *
 * <p>실제로 {@code auth.dto.LinkedStoreResponse}(가게 ID·이름)와 {@code member.dto.LinkedStoreResponse}
 * (주소·연결 시각까지)가 겹쳐서, 관리자 API 응답이 두 칸짜리로 문서화됐다(#103 리뷰에서 발견).
 *
 * <p>겹치면 한쪽 이름을 바꾼다. {@code @Schema(name = ...)} 로 스웨거 이름만 바꿀 수도 있지만,
 * 코드에서 두 이름을 혼동하는 문제는 그대로 남는다.
 */
@DisplayName("DTO 이름")
class DtoNameUniquenessTest {

    private static final String BASE_PACKAGE = "com.ktc4.backend";

    @Test
    @DisplayName("요청·응답 DTO 는 저장소 전체에서 이름이 겹치지 않는다")
    void requestAndResponseNamesAreUnique() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*(Request|Response)$")));

        Map<String, List<String>> bySimpleName = new LinkedHashMap<>();
        scanner.findCandidateComponents(BASE_PACKAGE).forEach(definition -> {
            String fqcn = definition.getBeanClassName();
            String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
            bySimpleName.computeIfAbsent(simple, k -> new ArrayList<>()).add(fqcn);
        });

        List<String> duplicated = bySimpleName.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> e.getKey() + " → " + e.getValue())
                .toList();

        assertThat(bySimpleName).isNotEmpty();
        assertThat(duplicated).isEmpty();
    }
}
