package com.ktc4.backend.domain.job.repository;

import com.ktc4.backend.domain.job.entity.Job;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRepository extends JpaRepository<Job, Long> {
}
