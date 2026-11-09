package com.ktc4.backend.domain.qr.repository;

import com.ktc4.backend.domain.qr.entity.QrCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface QrCredentialRepository extends JpaRepository<QrCredential, Long> {

    Optional<QrCredential> findByChildId(Long childId);

    Optional<QrCredential> findByTokenHash(String tokenHash);

    /**
     * 아동의 QR 해시를 넣거나, 이미 있으면 새 해시로 바꾼다. 줄은 아동당 하나(uk_qr_credential_child_id)이고 번호도 그대로다.
     *
     * <p>한 문장이라 같은 아동의 첫 발급이 동시에 와도 unique 제약에 걸리지 않는다 — 뒤에 온 쪽은 앞 쪽의 커밋을 기다렸다가
     * 해시를 바꾼다. 네이티브 쿼리라 JPA Auditing 이 시각을 채우지 않는다 — 부르는 쪽이 넘긴다.
     *
     * @param childId   아동 번호
     * @param tokenHash 새 토큰의 SHA-256 hex
     * @param now       행 생성(처음일 때)·수정 시각
     */
    // 앞뒤로 영속성 컨텍스트를 비운다 — 이 문장은 JPA 를 거치지 않아, 미리 읽어 둔 QrCredential 이 옛 해시로 남지 않게
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO qr_credential (child_id, token_hash, created_at, updated_at)
            VALUES (:childId, :tokenHash, :now, :now)
            ON CONFLICT (child_id) DO UPDATE SET token_hash = EXCLUDED.token_hash, updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    void upsert(@Param("childId") Long childId, @Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);
}
