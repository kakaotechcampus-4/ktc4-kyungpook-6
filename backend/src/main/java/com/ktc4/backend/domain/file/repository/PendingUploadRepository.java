package com.ktc4.backend.domain.file.repository;

import com.ktc4.backend.domain.file.entity.PendingUpload;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface PendingUploadRepository extends JpaRepository<PendingUpload, Long> {

    // Hibernate 에서 잠금 대기 시간 -2 는 "잠긴 행은 건너뛴다"(LockOptions.SKIP_LOCKED). Postgres 의 SKIP LOCKED 가 된다
    String SKIP_LOCKED = "-2";

    /**
     * 가입 신청이 가져갈 파일을 잠그고 읽는다. 같은 파일 ID 로 두 신청이 동시에 들어오면 나중 것은 앞의 것이 끝날 때까지
     * 기다렸다가, 이미 지워진(앞의 신청이 가져간) 행이라 찾지 못한다.
     *
     * <p>파일 ID 순으로 잠근다. 순서가 없으면 ID 가 겹치는 두 신청이 서로 다른 순서로 행을 잠가 교착 상태가 될 수 있다
     * (DB 가 한쪽을 끊어 500 이 된다).
     *
     * @param fileKeys 파일 ID
     * @return 찾은 기록. 없는 ID 는 빠진다
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PendingUpload p where p.fileKey in :fileKeys order by p.fileKey")
    List<PendingUpload> findAllForUpdateByFileKeyIn(@Param("fileKeys") Collection<String> fileKeys);

    /**
     * 정한 시각보다 먼저 올라와 아직 아무 신청도 가져가지 않은 파일을 잠그고 읽는다. <b>다른 트랜잭션이 잠근 행은 건너뛴다</b>
     * (SKIP LOCKED).
     *
     * <p>잠그지 않고 읽으면, 정리가 읽은 직후 가입 신청이 같은 행을 가져가 커밋할 수 있다 — 그 뒤 정리가 디스크의 파일을
     * 지우면 신청자의 파일(같은 디스크 이름)이 사라진다. 잠그면 그 행은 가입 신청이 끝날 때까지 정리가 손대지 않고
     * (다음 회차에는 이미 없다), 정리가 먼저 잡은 행은 가입 신청이 기다렸다가 "없는 파일"로 거절한다.
     * 기다리지 않고 건너뛰는 이유: 정리는 급하지 않다 — 다음 회차에 지우면 된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = SKIP_LOCKED))
    List<PendingUpload> findByCreatedAtBefore(LocalDateTime cutoff);
}
