package com.ktc4.backend.domain.verification.repository;

import com.ktc4.backend.domain.verification.entity.Verification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationRepository extends JpaRepository<Verification, Long> {
}
