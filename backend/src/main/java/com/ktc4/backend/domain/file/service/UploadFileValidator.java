package com.ktc4.backend.domain.file.service;

import com.ktc4.backend.domain.file.enums.FileType;
import com.ktc4.backend.domain.file.enums.UploadKind;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * 올라온 파일이 받아도 되는 파일인지 확인한다 — 비어 있지 않은지, 크기, 실제 형식.
 *
 * <p>파일 이름과 보낸 쪽이 적은 Content-Type 은 보지 않는다. 둘 다 보내는 쪽이 마음대로 적는 값이다.
 * 거절한 이유는 종류와 크기만 로그에 남긴다 — 파일 이름에는 사람 이름 같은 개인정보가 들어 있을 수 있다.
 */
@Slf4j
public final class UploadFileValidator {

    private UploadFileValidator() {
    }

    /**
     * 파일 한 개가 그 종류로 받아도 되는 파일인지 확인한다.
     *
     * @param kind 파일의 종류
     * @param file 올라온 파일
     * @return 파일 앞부분으로 가린 실제 형식
     * @throws CustomException 빈 파일이면 {@code EMPTY_FILE}, 종류·형식별 크기를 넘으면 {@code FILE_TOO_LARGE},
     *                         받지 않는 형식이면 {@code UNSUPPORTED_FILE_TYPE}
     */
    public static FileType validate(UploadKind kind, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            log.info("업로드 거절 - 빈 파일, kind={}", kind);
            throw new CustomException(ErrorCode.EMPTY_FILE);
        }
        if (file.getSize() > kind.maxBytes()) {
            log.info("업로드 거절 - 크기 초과, kind={}, size={}", kind, file.getSize());
            throw new CustomException(ErrorCode.FILE_TOO_LARGE);
        }
        FileType type = FileType.detect(readHead(file));
        if (!kind.allows(type)) {
            log.info("업로드 거절 - 받지 않는 형식, kind={}, detected={}", kind, type);
            throw new CustomException(ErrorCode.UNSUPPORTED_FILE_TYPE);
        }
        // 사업자등록증은 PDF 면 10MB, 사진이면 5MB 다 — 형식을 알아야 정할 수 있다
        if (file.getSize() > kind.maxBytes(type)) {
            log.info("업로드 거절 - 크기 초과, kind={}, type={}, size={}", kind, type, file.getSize());
            throw new CustomException(ErrorCode.FILE_TOO_LARGE);
        }
        return type;
    }

    private static byte[] readHead(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(FileType.HEAD_LENGTH);
        } catch (IOException e) {
            throw new UncheckedIOException("올라온 파일을 읽지 못했습니다", e);
        }
    }
}
