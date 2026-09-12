package com.ktc4.backend.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

// @EnableJpaAuditing 을 BackendApplication 에 붙이면 @WebMvcTest 같은 슬라이스 테스트에서
// JPA 설정 없이 Auditing 을 켜려다 실패하므로 별도 설정 클래스로 분리한다.
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
