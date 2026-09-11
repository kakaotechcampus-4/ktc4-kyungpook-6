package com.ktc4.backend.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 모든 엔티티가 공통으로 갖는 행 생성·수정 시각.
 *
 * <p>행이 "언제 생기고 언제 바뀌었는지"를 기록하는 기술적 값이다.
 * 도메인 의미의 시각(예: 가게 정보를 검증한 {@code lastCheckedAt})과는 별개로 관리한다.
 * 값은 JPA Auditing 이 저장·수정 시점에 자동으로 채운다.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseTimeEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
