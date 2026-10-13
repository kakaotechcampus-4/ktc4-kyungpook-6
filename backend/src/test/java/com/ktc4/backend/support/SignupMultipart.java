package com.ktc4.backend.support;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * 점주 가입 신청(multipart) 요청을 만드는 테스트 도우미.
 *
 * <p>가입 신청은 글자 정보({@code data}, JSON)와 파일({@code storeImage}·{@code menuImages}·{@code bizCert})을 함께 받는다.
 * 파일 내용은 형식을 가리는 앞부분만 진짜 모양이고 나머지는 의미 없는 바이트다 — 실제 사진·서류가 아니다.
 */
public final class SignupMultipart {

    public static final String SIGNUP_URL = "/api/auth/owners/signup";

    private static final byte[] JPEG_HEAD = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};
    private static final byte[] PNG_HEAD = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};
    private static final byte[] WEBP_HEAD = {'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'E', 'B', 'P'};
    private static final byte[] PDF_HEAD = "%PDF-1.7\n%test".getBytes(StandardCharsets.US_ASCII);

    private SignupMultipart() {
    }

    /** 글자 정보에 올바른 대표 사진·사업자등록증을 붙인 가입 요청. 메뉴 사진은 없다. */
    public static MockMultipartHttpServletRequestBuilder signupRequest(String dataJson) {
        return signupRequest(dataJson, jpeg("storeImage"), pdf("bizCert"));
    }

    /** 글자 정보에 넘겨준 파일만 붙인 가입 요청 — 파일이 빠지거나 틀린 경우를 만들 때 쓴다. */
    public static MockMultipartHttpServletRequestBuilder signupRequest(String dataJson, MockMultipartFile... files) {
        MockMultipartHttpServletRequestBuilder request = multipart(SIGNUP_URL).file(data(dataJson));
        for (MockMultipartFile file : files) {
            request.file(file);
        }
        return request;
    }

    /** 글자 정보 칸. 실제 클라이언트처럼 칸의 Content-Type 을 application/json 으로 싣는다. */
    public static MockMultipartFile data(String dataJson) {
        return new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE,
                dataJson.getBytes(StandardCharsets.UTF_8));
    }

    public static MockMultipartFile jpeg(String part) {
        return new MockMultipartFile(part, "sample.jpg", MediaType.IMAGE_JPEG_VALUE, JPEG_HEAD);
    }

    public static MockMultipartFile png(String part) {
        return new MockMultipartFile(part, "sample.png", MediaType.IMAGE_PNG_VALUE, PNG_HEAD);
    }

    public static MockMultipartFile webp(String part) {
        return new MockMultipartFile(part, "sample.webp", "image/webp", WEBP_HEAD);
    }

    public static MockMultipartFile pdf(String part) {
        return new MockMultipartFile(part, "sample.pdf", MediaType.APPLICATION_PDF_VALUE, PDF_HEAD);
    }

    /** 내용을 직접 정한 파일 — 이름·Content-Type 과 실제 내용이 다른 파일을 만들 때 쓴다. */
    public static MockMultipartFile file(String part, String filename, String contentType, byte[] content) {
        return new MockMultipartFile(part, filename, contentType, content);
    }

    /** 앞부분은 올바른 JPEG 이고 전체 크기가 {@code size} 바이트인 파일. */
    public static MockMultipartFile jpegOfSize(String part, int size) {
        byte[] content = new byte[size];
        System.arraycopy(JPEG_HEAD, 0, content, 0, Math.min(JPEG_HEAD.length, size));
        return new MockMultipartFile(part, "sample.jpg", MediaType.IMAGE_JPEG_VALUE, content);
    }

    /** 앞부분은 올바른 PDF 이고 전체 크기가 {@code size} 바이트인 파일. */
    public static MockMultipartFile pdfOfSize(String part, int size) {
        byte[] content = new byte[size];
        System.arraycopy(PDF_HEAD, 0, content, 0, Math.min(PDF_HEAD.length, size));
        return new MockMultipartFile(part, "sample.pdf", MediaType.APPLICATION_PDF_VALUE, content);
    }
}
