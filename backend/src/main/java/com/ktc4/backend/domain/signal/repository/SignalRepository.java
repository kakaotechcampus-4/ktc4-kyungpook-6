package com.ktc4.backend.domain.signal.repository;

import com.ktc4.backend.domain.signal.entity.Signal;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SignalRepository extends JpaRepository<Signal, Long> {
}
