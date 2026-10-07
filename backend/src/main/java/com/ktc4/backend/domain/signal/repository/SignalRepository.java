package com.ktc4.backend.domain.signal.repository;

import com.ktc4.backend.domain.signal.entity.Signal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SignalRepository extends JpaRepository<Signal, Long> {

    List<Signal> findByTask_TaskIdInOrderBySignalIdAsc(Collection<Long> taskIds);
}
