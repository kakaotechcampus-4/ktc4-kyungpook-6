package com.ktc4.backend.global.error;

import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ErrorTypeSchemaCustomizer} 가 실제로 OpenAPI 스펙에 적용되는지 확인하는 최소 계약 테스트.
 *
 * <p>이 커스터마이저는 {@code @WebMvcTest} 슬라이스에는 안 실린다({@code @Configuration} 빈이라
 * 전체 컨텍스트가 있어야 등록된다) — 그래서 지금까지는 {@code bootRun} + curl 로만 수동 확인했다.
 * springdoc 버전이 바뀌거나 {@code Schema} API 가 바뀌면 조용히 깨질 수 있는 지점이라 자동화한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("에러 타입 스키마 커스터마이저")
class ErrorTypeSchemaCustomizerTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainerTest.POSTGRES;

    @org.springframework.beans.factory.annotation.Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("ErrorResponse.type 의 enum 개수가 ErrorCode 상수 개수와 같다")
    void typeEnumMatchesErrorCodeCount() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.ErrorResponse.properties.type.enum")
                        .isArray())
                .andExpect(jsonPath("$.components.schemas.ErrorResponse.properties.type.enum.length()")
                        .value(ErrorCode.values().length));
    }

    @Test
    @DisplayName("enum 목록에 실제 ErrorCode 의 type URI 가 전부 들어있다")
    void typeEnumContainsEveryErrorCodeUri() throws Exception {
        for (ErrorCode code : ErrorCode.values()) {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(jsonPath("$.components.schemas.ErrorResponse.properties.type.enum")
                            .value(org.hamcrest.Matchers.hasItem(code.getType().toString())));
        }
    }
}
