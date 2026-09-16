package com.ktc4.backend.domain.task.repository;

import com.ktc4.backend.domain.task.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, Long> {
}
