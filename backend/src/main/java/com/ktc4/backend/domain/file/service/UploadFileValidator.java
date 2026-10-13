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
import java.util.List;

/**
 * 올라온 파일이 받아도 되는 파일인지 확인한다 — 비어 있지 않은지, 크기, 실제 형식.
 *
 * <p>파일 이름과 보낸 쪽이 적은 Content-Type 은 보지 않는다. 둘 다 보내는 쪽이 마음대로 적는 값이다.
 * 거절한 이유는 종류와 크기만 로그에 남긴다 — 파일 이름에는 사람 이름 같은 개인정보가 들어 있을 수 있다.
 */
@Slf4j
public final class UploadFileValidator {

    /** 가입 신청 한 건에 올릴 수 있는 메뉴 사진 수. */
    public static final int MAX_MENU_IMAGES = 2;

    private UploadFileValidator() {
    }

    /**
     * 점주 가입 신청에 함께 온 파일들을 확인한다.
     *
     * @param storeImage 가게 대표 사진
     * @param menuImages 메뉴 사진. 없으면 {@code null} 이거나 빈 목록
     * @param bizCert    사업자등록증
     * @throws CustomException 빈 파일이면 {@code EMPTY_FILE}, 종류별 크기를 넘으면 {@code FILE_TOO_LARGE},
     *                         받지 않는 형식이면 {@code UNSUPPORTED_FILE_TYPE},
     *                         메뉴 사진이 {@value #MAX_MENU_IMAGES}장을 넘으면 {@code TOO_MANY_FILES}
     */
    public static void validateSignupFiles(MultipartFile storeImage, List<MultipartFile> menuImages,
                                           MultipartFile bizCert) {
        List<MultipartFile> menus = menuImages == null ? List.of() : menuImages;
        // 개수를 먼저 본다 — 넘친 요청은 파일을 하나도 읽지 않고 거절한다
        if (menus.size() > MAX_MENU_IMAGES) {
            log.info("업로드 거절 - 메뉴 사진 개수 초과, count={}", menus.size());
            throw new CustomException(ErrorCode.TOO_MANY_FILES);
        }
        validate(UploadKind.STORE_IMAGE, storeImage);
        for (MultipartFile menu : menus) {
            validate(UploadKind.MENU_IMAGE, menu);
        }
        validate(UploadKind.BIZ_CERT, bizCert);
    }

    /**
     * 파일 한 개가 그 종류로 받아도 되는 파일인지 확인한다.
     *
     * @param kind 파일의 종류
     * @param file 올라온 파일
     * @throws CustomException 빈 파일이면 {@code EMPTY_FILE}, 종류별 크기를 넘으면 {@code FILE_TOO_LARGE},
     *                         받지 않는 형식이면 {@code UNSUPPORTED_FILE_TYPE}
     */
    public static void validate(UploadKind kind, MultipartFile file) {
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
    }

    private static byte[] readHead(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(FileType.HEAD_LENGTH);
        } catch (IOException e) {
            throw new UncheckedIOException("올라온 파일을 읽지 못했습니다", e);
        }
    }
}
