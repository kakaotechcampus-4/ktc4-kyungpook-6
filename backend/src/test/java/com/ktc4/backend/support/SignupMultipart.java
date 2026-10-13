package com.ktc4.backend.support;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * 점주 가입 신청(multipart) 요청을 만드는 테스트 도우미.
 *
 * <p>가입 신청은 글자 정보({@code data}, JSON)와 파일({@code storeImage}·{@code menuImages}·{@code bizCert})을 함께 받는다.
 * 사진은 구조만 맞춘 가장 작은 파일이고(서버가 저장할 때 구조를 따라가며 부가 정보를 지운다),
 * PDF 는 형식을 가리는 앞부분만 진짜 모양이다 — 실제 사진·서류가 아니다.
 */
public final class SignupMultipart {

    public static final String SIGNUP_URL = "/api/auth/owners/signup";

    // 시작 표시 + JFIF 머리말(16바이트 조각). 뒤에 끝 표시를 붙이면 구조가 맞는 가장 작은 JPEG 가 된다
    private static final byte[] JPEG_HEAD = {(byte) 0xFF, (byte) 0xD8,
            (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0};
    private static final byte[] JPEG_END = {(byte) 0xFF, (byte) 0xD9};
    private static final byte[] JPEG_SCAN_START = {(byte) 0xFF, (byte) 0xDA, 0, 2};
    private static final byte[] JPEG = concat(JPEG_HEAD, JPEG_END);
    // 서명 + 머리말(1×1) + 그림 데이터 + 끝 조각 — 구조가 맞는 작은 PNG
    private static final byte[] PNG = concat(
            new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A},
            pngChunk("IHDR", new byte[]{0, 0, 0, 1, 0, 0, 0, 1, 8, 0, 0, 0, 0}),
            pngChunk("IDAT", new byte[]{0x78, (byte) 0x9C, 0x63, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01}),
            pngChunk("IEND", new byte[0]));
    private static final byte[] WEBP_HEAD = {'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'E', 'B', 'P'};
    private static final byte[] PDF_HEAD = "%PDF-1.7\n%test".getBytes(StandardCharsets.US_ASCII);

    private SignupMultipart() {
    }

    /** 바이트 배열을 이어 붙인다. */
    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** PNG 조각 하나 — 길이 + 이름 + 내용 + 검사값. */
    public static byte[] pngChunk(String name, byte[] data) {
        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(nameBytes);
        crc.update(data);
        return concat(ByteBuffer.allocate(4).putInt(data.length).array(), nameBytes, data,
                ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
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
        return new MockMultipartFile(part, "sample.jpg", MediaType.IMAGE_JPEG_VALUE, JPEG);
    }

    public static MockMultipartFile png(String part) {
        return new MockMultipartFile(part, "sample.png", MediaType.IMAGE_PNG_VALUE, PNG);
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

    /** 구조가 맞는 JPEG 이고 전체 크기가 {@code size} 바이트인 파일. 그림 데이터 자리를 0 으로 채운다. */
    public static MockMultipartFile jpegOfSize(String part, int size) {
        byte[] content = new byte[size];
        System.arraycopy(JPEG_HEAD, 0, content, 0, Math.min(JPEG_HEAD.length, size));
        int scanStart = JPEG_HEAD.length;
        if (size >= scanStart + JPEG_SCAN_START.length + JPEG_END.length) {
            System.arraycopy(JPEG_SCAN_START, 0, content, scanStart, JPEG_SCAN_START.length);
            System.arraycopy(JPEG_END, 0, content, size - JPEG_END.length, JPEG_END.length);
        }
        return new MockMultipartFile(part, "sample.jpg", MediaType.IMAGE_JPEG_VALUE, content);
    }

    /** 앞부분은 올바른 PDF 이고 전체 크기가 {@code size} 바이트인 파일. */
    public static MockMultipartFile pdfOfSize(String part, int size) {
        byte[] content = new byte[size];
        System.arraycopy(PDF_HEAD, 0, content, 0, Math.min(PDF_HEAD.length, size));
        return new MockMultipartFile(part, "sample.pdf", MediaType.APPLICATION_PDF_VALUE, content);
    }
}
