package com.ktc4.backend.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling 을 BackendApplication 에 붙이면 @WebMvcTest 같은 슬라이스 테스트에서
// 스케줄링 설정 없이 이 기능을 켜려다 실패할 수 있다. JpaAuditingConfig 와 같은 이유로 분리한다.
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
