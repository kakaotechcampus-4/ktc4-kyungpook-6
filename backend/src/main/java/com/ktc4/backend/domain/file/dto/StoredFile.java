package com.ktc4.backend.domain.file.dto;

import com.ktc4.backend.domain.file.enums.FileType;
import com.ktc4.backend.domain.file.enums.UploadKind;
import org.springframework.core.io.Resource;

/**
 * 내려줄 파일 한 개 — 기록과 내용을 함께 든다.
 *
 * @param fileId    파일 ID
 * @param kind      종류. 바로 보여줄지 내려받게 할지를 정한다
 * @param fileType  서버가 가린 형식. Content-Type 을 정한다
 * @param sizeBytes 크기
 * @param content   파일 내용
 */
public record StoredFile(String fileId, UploadKind kind, FileType fileType, long sizeBytes, Resource content) {
}
