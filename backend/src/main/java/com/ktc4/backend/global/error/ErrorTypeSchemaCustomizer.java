package com.ktc4.backend.global.error;

import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * OpenAPI 스펙의 {@code ErrorResponse.type} 필드에 실제 나올 수 있는 URI 목록을 붙인다.
 *
 * <p>{@code code} 필드를 없애고 {@code type} 을 유일한 식별자로 쓰기로 하면서(RFC 9457 §3.1.1),
 * {@code type} 이 스펙에 그냥 {@code string} 으로만 나가면 프론트가 {@code openapi-typescript}
 * 로 타입을 생성해도 실제 값 집합을 알 수 없다 — {@code code} 시절엔 {@code @Schema(example=...)}
 * 로 힌트를 줬는데 그게 사라진 것.
 *
 * <p>{@code @Schema(allowableValues=...)} 는 컴파일 타임 상수만 받아서 {@link ErrorCode} 값을
 * 직접 참조할 수 없다. 그래서 애노테이션이 아니라 이 커스터마이저로 기동 시점에 {@link ErrorCode}
 * 전수를 읽어 스키마에 심는다 — 목록을 손으로 나열하면 {@code ErrorCode} 에 항목을 추가할 때마다
 * 여기도 같이 고쳐야 하는 걸 잊기 쉽다.
 */
@Configuration
public class ErrorTypeSchemaCustomizer {

    private static final String ERROR_RESPONSE_SCHEMA = "ErrorResponse";
    private static final String TYPE_PROPERTY = "type";

    @Bean
    public OpenApiCustomizer errorTypeEnumCustomizer() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            Schema<?> errorResponse = openApi.getComponents().getSchemas().get(ERROR_RESPONSE_SCHEMA);
            if (errorResponse == null || errorResponse.getProperties() == null) {
                return;
            }
            @SuppressWarnings("unchecked")
            Schema<Object> typeProperty = (Schema<Object>) errorResponse.getProperties().get(TYPE_PROPERTY);
            if (typeProperty == null) {
                return;
            }
            List<Object> typeUris = Arrays.stream(ErrorCode.values())
                    .<Object>map(code -> code.getType().toString())
                    .distinct()
                    .toList();
            typeProperty.setEnum(typeUris);
        };
    }
}
