package com.ktc4.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling: @Scheduled 가 붙은 메서드(국세청 상태 조회 배치)를 실제로 돌리기 위한 설정.
// 이게 없으면 애노테이션은 그대로 있어도 아무 일도 일어나지 않는다.
@EnableScheduling
@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
