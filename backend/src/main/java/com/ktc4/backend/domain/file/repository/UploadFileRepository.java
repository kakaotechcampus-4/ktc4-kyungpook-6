package com.ktc4.backend.domain.file.repository;

import com.ktc4.backend.domain.file.entity.UploadFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UploadFileRepository extends JpaRepository<UploadFile, Long> {

    /** 회원이 올린 파일을 올린 순서대로 읽는다. */
    List<UploadFile> findByMemberMemberIdOrderByUploadFileIdAsc(Long memberId);
}
