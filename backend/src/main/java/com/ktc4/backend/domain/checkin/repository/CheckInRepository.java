package com.ktc4.backend.domain.checkin.repository;

import com.ktc4.backend.domain.checkin.entity.CheckIn;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckInRepository extends JpaRepository<CheckIn, Long> {
}
