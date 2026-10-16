package com.ktc4.backend.domain.file.service;

import com.ktc4.backend.domain.file.enums.UploadKind;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static com.ktc4.backend.support.SignupMultipart.file;
import static com.ktc4.backend.support.SignupMultipart.jpeg;
import static com.ktc4.backend.support.SignupMultipart.jpegOfSize;
import static com.ktc4.backend.support.SignupMultipart.pdf;
import static com.ktc4.backend.support.SignupMultipart.pdfOfSize;
import static com.ktc4.backend.support.SignupMultipart.png;
import static com.ktc4.backend.support.SignupMultipart.webp;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThat;

// 파일 내용은 형식을 가리는 앞부분만 진짜 모양인 가짜 바이트다. 실제 사진·서류가 아니다.
@DisplayName("UploadFileValidator")
class UploadFileValidatorTest {

    private static final int MB = 1024 * 1024;

    private static ErrorCode errorCodeOf(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(CustomException.class);
        return ((CustomException) thrown).getErrorCode();
    }

    @Test
    @DisplayName("사진은 JPEG·PNG 를 받는다 — WebP 는 받지 않는다")
    void acceptsImages() {
        assertThatCode(() -> UploadFileValidator.validate(UploadKind.STORE_IMAGE, jpeg("storeImage")))
                .doesNotThrowAnyException();
        assertThatCode(() -> UploadFileValidator.validate(UploadKind.STORE_IMAGE, png("storeImage")))
                .doesNotThrowAnyException();
        assertThat(errorCodeOf(() -> UploadFileValidator.validate(UploadKind.STORE_IMAGE, webp("storeImage"))))
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
        assertThat(errorCodeOf(() -> UploadFileValidator.validate(UploadKind.MENU_IMAGE, webp("menuImages"))))
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    @DisplayName("사업자등록증은 PDF·JPEG·PNG 를 받는다 — 휴대폰으로 찍은 사진도 된다. WebP 는 받지 않는다")
    void bizCertAcceptsPdfAndPhotos() {
        assertThatCode(() -> UploadFileValidator.validate(UploadKind.BIZ_CERT, pdf("bizCert")))
                .doesNotThrowAnyException();
        assertThatCode(() -> UploadFileValidator.validate(UploadKind.BIZ_CERT, jpeg("bizCert")))
                .doesNotThrowAnyException();
        assertThatCode(() -> UploadFileValidator.validate(UploadKind.BIZ_CERT, png("bizCert")))
                .doesNotThrowAnyException();
        assertThat(errorCodeOf(() -> UploadFileValidator.validate(UploadKind.BIZ_CERT, webp("bizCert"))))
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    @DisplayName("사진 자리에 PDF 를 올리면 UNSUPPORTED_FILE_TYPE")
    void imageRejectsPdf() {
        assertThat(errorCodeOf(() -> UploadFileValidator.validate(UploadKind.STORE_IMAGE, pdf("storeImage"))))
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    @DisplayName("이름과 Content-Type 이 사진이어도 내용이 사진이 아니면 거절한다 — 둘 다 보내는 쪽이 마음대로 적는 값이다")
    void rejectsDisguisedFile() {
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile disguised = file("storeImage", "photo.jpg", "image/jpeg", html);

        assertThat(errorCodeOf(() -> UploadFileValidator.validate(UploadKind.STORE_IMAGE, disguised)))
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    @DisplayName("SVG 는 받지 않는다 — 안에 스크립트를 넣을 수 있는 형식이다")
    void rejectsSvg() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);

        assertThat(errorCodeOf(() -> UploadFileValidator.validate(
                UploadKind.STORE_IMAGE, file("storeImage", "image.svg", "image/svg+xml", svg))))
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    @DisplayName("이름이 엉뚱해도 내용이 맞으면 받는다 — 파일 이름은 보지 않는다")
    void ignoresFilename() {
        MockMultipartFile oddName = file("storeImage", "../../etc/passwd", "text/plain",
                bytesOf(jpeg("storeImage")));

        assertThatCode(() -> UploadFileValidator.validate(UploadKind.STORE_IMAGE, oddName)).doesNotThrowAnyException();
    }

    private static byte[] bytesOf(MockMultipartFile file) {
        try {
            return file.getBytes();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("빈 파일과 형식을 가리기에 너무 짧은 파일은 거절한다")
    void rejectsEmptyAndTooShort() {
        assertThat(errorCodeOf(() -> UploadFileValidator.validate(
                UploadKind.STORE_IMAGE, file("storeImage", "empty.jpg", "image/jpeg", new byte[0]))))
                .isEqualTo(ErrorCode.EMPTY_FILE);
        assertThat(errorCodeOf(() -> UploadFileValidator.validate(UploadKind.STORE_IMAGE, null)))
                .isEqualTo(ErrorCode.EMPTY_FILE);
        assertThat(errorCodeOf(() -> UploadFileValidator.validate(
                UploadKind.STORE_IMAGE, file("storeImage", "short.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8}))))
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    @DisplayName("사진은 5MB 까지, 사업자등록증은 10MB 까지 받는다 — 1바이트라도 넘으면 FILE_TOO_LARGE")
    void sizeLimitsPerKind() {
        assertThatCode(() -> UploadFileValidator.validate(UploadKind.STORE_IMAGE, jpegOfSize("storeImage", 5 * MB)))
                .doesNotThrowAnyException();
        assertThat(errorCodeOf(() -> UploadFileValidator.validate(
                UploadKind.STORE_IMAGE, jpegOfSize("storeImage", 5 * MB + 1))))
                .isEqualTo(ErrorCode.FILE_TOO_LARGE);
        assertThatCode(() -> UploadFileValidator.validate(UploadKind.BIZ_CERT, pdfOfSize("bizCert", 10 * MB)))
                .doesNotThrowAnyException();
        assertThat(errorCodeOf(() -> UploadFileValidator.validate(UploadKind.BIZ_CERT, pdfOfSize("bizCert", 10 * MB + 1))))
                .isEqualTo(ErrorCode.FILE_TOO_LARGE);
    }

    @Test
    @DisplayName("사진으로 낸 사업자등록증은 사진 상한(5MB)을 따른다 — 사진은 부가 정보를 지우느라 메모리에 올리기 때문이다")
    void bizCertPhotoFollowsImageLimit() {
        assertThatCode(() -> UploadFileValidator.validate(UploadKind.BIZ_CERT, jpegOfSize("bizCert", 5 * MB)))
                .doesNotThrowAnyException();
        assertThat(errorCodeOf(() -> UploadFileValidator.validate(UploadKind.BIZ_CERT, jpegOfSize("bizCert", 5 * MB + 1))))
                .isEqualTo(ErrorCode.FILE_TOO_LARGE);
        // PDF 는 그대로 10MB 까지 — 사진 상한은 형식을 가린 뒤에 건다
        assertThatCode(() -> UploadFileValidator.validate(UploadKind.BIZ_CERT, pdfOfSize("bizCert", 5 * MB + 1)))
                .doesNotThrowAnyException();
    }
}
