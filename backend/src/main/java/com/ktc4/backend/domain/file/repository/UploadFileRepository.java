package com.ktc4.backend.domain.file.repository;

import com.ktc4.backend.domain.file.entity.UploadFile;
import com.ktc4.backend.domain.file.enums.UploadKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UploadFileRepository extends JpaRepository<UploadFile, Long> {

    /** 회원이 올린 파일을 올린 순서대로 읽는다. */
    List<UploadFile> findByMemberMemberIdOrderByUploadFileIdAsc(Long memberId);

    /**
     * 가게에 지금 연결된 점주가 올린 파일을 올린 순서대로 읽는다.
     *
     * <p>파일은 가게가 아니라 올린 점주에게 달려 있고, 어느 가게의 것인지는 점주↔가게 연결로 정한다.
     * 그래서 승인 전(연결 없음)·거절(연결 없음)·연결을 끊은 점주의 파일은 나오지 않고, 다시 연결하면 다시 나온다.
     * 가게를 잘못 골라 승인했다가 바로잡아도 파일을 따로 옮길 필요가 없다.
     *
     * <p>한 점주가 가게 두 곳에 연결돼 있으면 두 가게에 같은 파일이 나온다 — 가입할 때 올린 파일이
     * 어느 가게 것인지는 따로 적어 두지 않는다.
     *
     * @param storeId 가게 ID
     * @param kinds   읽을 종류
     * @return 파일. 없으면 빈 목록
     */
    @Query("""
            select f from UploadFile f
            where f.kind in :kinds
              and exists (
                  select 1 from StoreOwner so
                  where so.member = f.member
                    and so.store.storeId = :storeId
                    and so.unlinkedAt is null)
            order by f.uploadFileId asc
            """)
    List<UploadFile> findOfStore(@Param("storeId") Long storeId, @Param("kinds") Collection<UploadKind> kinds);

    /**
     * 파일 한 개를, 올린 점주가 지금 어느 가게에든 연결돼 있을 때만 읽는다.
     *
     * <p>공개 조회에 쓴다. 종류를 함께 거는 이유: 사업자등록증은 점주가 승인됐어도 공개하지 않는다.
     *
     * @param fileId 파일 ID
     * @param kinds  읽어도 되는 종류
     * @return 파일. 없거나, 종류가 다르거나, 올린 점주가 가게에 연결돼 있지 않으면 빈 값
     */
    @Query("""
            select f from UploadFile f
            where f.uploadFileId = :fileId
              and f.kind in :kinds
              and exists (
                  select 1 from StoreOwner so
                  where so.member = f.member
                    and so.unlinkedAt is null)
            """)
    Optional<UploadFile> findLinkedById(@Param("fileId") Long fileId, @Param("kinds") Collection<UploadKind> kinds);
}
