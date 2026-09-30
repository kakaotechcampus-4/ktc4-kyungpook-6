package com.ktc4.backend.domain.qr.repository;

import com.ktc4.backend.domain.qr.entity.QrCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface QrCredentialRepository extends JpaRepository<QrCredential, Long> {

    Optional<QrCredential> findByChildId(Long childId);

    Optional<QrCredential> findByTokenHash(String tokenHash);
}
