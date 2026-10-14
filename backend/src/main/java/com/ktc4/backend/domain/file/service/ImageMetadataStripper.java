package com.ktc4.backend.domain.file.service;

import com.ktc4.backend.domain.file.enums.FileType;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 사진에서 화면에 그리는 데 필요 없는 부가 정보를 지운다.
 *
 * <p><b>왜 지우나.</b> 휴대폰으로 찍은 사진에는 찍은 곳의 좌표(GPS)·기기 이름·찍은 시각이 함께 들어 있다.
 * 가게 사진은 가입이 승인되면 누구나 내려받을 수 있어, 그대로 두면 집에서 찍은 메뉴 사진 한 장으로 점주의 집 위치가 공개된다.
 *
 * <p><b>어떻게 지우나.</b> 사진을 풀었다가 다시 압축하지 않는다 — 화질이 떨어지고, 큰 사진을 메모리에 펼쳐야 한다.
 * 파일은 "조각"의 나열이고 부가 정보는 그림과 다른 조각에 들어 있으므로, 그림에 필요한 조각만 골라 옮겨 적는다.
 * 지울 것을 적는 방식이 아니라 <b>남길 것을 적는 방식</b>이다. 모르는 조각은 남기지 않는다 — 새로운 종류의 부가 정보가
 * 생겨도 따로 손대지 않아도 빠진다.
 *
 * <p>그림의 끝 뒤에 붙은 내용도 버린다. 일부 휴대폰은 사진 뒤에 짧은 동영상이나 두 번째 사진을 붙여 두는데
 * 거기에도 위치 정보가 있을 수 있고, 사진인 척하는 파일 뒤에 다른 내용을 숨기는 데도 쓰인다.
 *
 * <p>구조가 맞지 않는 파일은 저장하지 않고 거절한다. 어디까지가 그림인지 알 수 없는 파일에서
 * "부가 정보를 지웠다"고 말할 수 없기 때문이다.
 */
@Slf4j
public final class ImageMetadataStripper {

    private ImageMetadataStripper() {
    }

    /**
     * 사진의 부가 정보를 지운 내용을 돌려준다. 사진이 아닌 형식은 그대로 돌려준다.
     *
     * @param type    서버가 가린 형식
     * @param content 올라온 파일의 내용
     * @return 그림에 필요한 조각만 남긴 내용
     * @throws CustomException 구조가 맞지 않는 사진이면 {@code UNSUPPORTED_FILE_TYPE}
     */
    public static byte[] strip(FileType type, byte[] content) {
        return switch (type) {
            case JPEG -> stripJpeg(content);
            case PNG -> stripPng(content);
            case PDF -> content;
        };
    }

    private static CustomException malformed(FileType type, String what) {
        // 어디가 틀렸는지만 남긴다. 파일 내용은 남기지 않는다
        log.info("업로드 거절 - 사진의 구조가 맞지 않음, type={}, 문제={}", type, what);
        return new CustomException(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    private static int u8(byte[] data, int at) {
        return data[at] & 0xFF;
    }

    private static int u16(byte[] data, int at, boolean littleEndian) {
        return littleEndian ? u8(data, at) | (u8(data, at + 1) << 8) : (u8(data, at) << 8) | u8(data, at + 1);
    }

    private static long u32(byte[] data, int at, boolean littleEndian) {
        long a = u8(data, at);
        long b = u8(data, at + 1);
        long c = u8(data, at + 2);
        long d = u8(data, at + 3);
        return littleEndian ? a | (b << 8) | (c << 16) | (d << 24) : (a << 24) | (b << 16) | (c << 8) | d;
    }

    private static boolean startsWith(byte[] data, int from, int end, byte[] prefix) {
        if (end - from < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[from + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    // ── JPEG ──────────────────────────────────────────────────────────────
    //
    // JPEG 는 0xFF 로 시작하는 2바이트 표시(마커)가 붙은 조각의 나열이다.
    //   FFD8(시작) → [조각: FFxx + 길이 2바이트 + 내용]… → FFDA(그림 데이터 시작) + 압축된 그림 → FFD9(끝)
    // 부가 정보는 APPn(FFE0~FFEF)·COM(FFFE) 조각에 들어 있다. EXIF(위치·기기·시각·미리보기)와 XMP 는 APP1 이다.
    // 방향은 JPEG 에서만 남긴다. PNG 의 EXIF 조각은 통째로 버린다 — 방향이 든 PNG 는 드물고(휴대폰 사진은 JPEG 다),
    // 그런 PNG 는 돌아가 보일 수 있다.

    private static final int MARKER = 0xFF;
    private static final int SOI = 0xD8;
    private static final int EOI = 0xD9;
    private static final int SOS = 0xDA;
    private static final int RST_FIRST = 0xD0;
    private static final int RST_LAST = 0xD7;
    private static final int TEM = 0x01;
    private static final int SOF_FIRST = 0xC0;
    private static final int SOF_LAST = 0xCF;
    private static final int DQT = 0xDB;
    private static final int EXP = 0xDF;
    private static final int APP0 = 0xE0;
    private static final int APP1 = 0xE1;
    private static final int APP2 = 0xE2;
    private static final int APP14 = 0xEE;

    private static final byte[] JFIF = "JFIF\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ICC_PROFILE = "ICC_PROFILE\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ADOBE = "Adobe".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EXIF = "Exif\0\0".getBytes(StandardCharsets.US_ASCII);

    private static final int EXIF_ORIENTATION_TAG = 0x0112;
    private static final int EXIF_TYPE_SHORT = 3;
    private static final int IFD_ENTRY_LENGTH = 12;
    private static final int UPRIGHT = 1;

    private static byte[] stripJpeg(byte[] in) {
        int n = in.length;
        if (n < 4 || u8(in, 0) != MARKER || u8(in, 1) != SOI) {
            throw malformed(FileType.JPEG, "시작 표시 없음");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(n);
        out.write(MARKER);
        out.write(SOI);

        // 방향 조각을 끼울 자리 — 시작 표시 바로 뒤, 맨 앞 조각이 JFIF 면 그 뒤
        int insertAt = out.size();
        boolean first = true;
        int orientation = UPRIGHT;
        int pos = 2;

        while (true) {
            if (pos >= n) {
                // 끝 표시 없이 끝난 파일. 일부 기기·편집기가 이렇게 만들고 화면에는 문제없이 그려진다 — 끝 표시를 붙여 준다
                out.write(MARKER);
                out.write(EOI);
                break;
            }
            if (u8(in, pos) != MARKER) {
                throw malformed(FileType.JPEG, "조각이 있어야 할 자리에 표시가 없음");
            }
            // 표시 앞에 0xFF 가 여러 개 채워져 있을 수 있다
            int markerAt = pos + 1;
            while (markerAt < n && u8(in, markerAt) == MARKER) {
                markerAt++;
            }
            if (markerAt >= n) {
                throw malformed(FileType.JPEG, "표시가 끊김");
            }
            int marker = u8(in, markerAt);
            pos = markerAt + 1;

            if (marker == EOI) {
                // 여기서 끝낸다 — 뒤에 무엇이 붙어 있든 옮기지 않는다
                out.write(MARKER);
                out.write(EOI);
                break;
            }
            if (marker == 0x00 || marker == SOI) {
                throw malformed(FileType.JPEG, "조각 자리에 올 수 없는 표시");
            }
            if (marker == TEM || (marker >= RST_FIRST && marker <= RST_LAST)) {
                // 길이가 없는 표시
                out.write(MARKER);
                out.write(marker);
                first = false;
                continue;
            }
            if (pos + 2 > n) {
                throw malformed(FileType.JPEG, "조각 길이가 끊김");
            }
            int length = u16(in, pos, false);   // 길이 2바이트 자신을 포함한다
            if (length < 2 || pos + length > n) {
                throw malformed(FileType.JPEG, "조각 길이가 파일을 벗어남");
            }
            int payloadStart = pos + 2;
            int segmentEnd = pos + length;

            if (marker == APP1 && orientation == UPRIGHT) {
                orientation = readOrientation(in, payloadStart, segmentEnd);
            }
            if (keepsJpegSegment(marker, in, payloadStart, segmentEnd)) {
                out.write(MARKER);
                out.write(marker);
                out.write(in, pos, length);
                if (first && marker == APP0) {
                    insertAt = out.size();
                }
                // 버린 조각은 세지 않는다 — EXIF 가 JFIF 앞에 있던 사진도 지운 뒤에는 JFIF 가 맨 앞이다
                first = false;
            }
            pos = segmentEnd;

            if (marker == SOS) {
                // 압축된 그림 데이터. 다음 조각이 시작되는 곳까지 그대로 옮긴다
                int scanEnd = endOfScan(in, pos);
                out.write(in, pos, scanEnd - pos);
                pos = scanEnd;
            }
        }

        byte[] cleaned = out.toByteArray();
        if (orientation == UPRIGHT) {
            return cleaned;
        }
        // 방향만 적은 조각을 끼운다. 휴대폰은 세로로 찍어도 그림은 가로로 저장하고 "돌려서 보여 줘라"를 EXIF 에 적는다 —
        // EXIF 를 통째로 지우기만 하면 세로 사진이 누워서 보인다.
        byte[] orientationSegment = orientationSegment(orientation);
        byte[] result = new byte[cleaned.length + orientationSegment.length];
        System.arraycopy(cleaned, 0, result, 0, insertAt);
        System.arraycopy(orientationSegment, 0, result, insertAt, orientationSegment.length);
        System.arraycopy(cleaned, insertAt, result, insertAt + orientationSegment.length, cleaned.length - insertAt);
        return result;
    }

    // 남길 조각을 적는다. 여기에 없는 표시는 모두 버린다 — 글자 조각(COM), 적지 않은 APPn,
    // 표준이 나중에 쓰려고 비워 둔 번호(JPGn 등)까지.
    private static boolean keepsJpegSegment(int marker, byte[] in, int payloadStart, int segmentEnd) {
        // 그림 자체를 이루는 조각: 크기·압축 방식(SOF, C0~CF — 허프만 표 C4 와 산술 부호 조건 CC 포함),
        // 양자화 표(DB), 줄 수(DC), 다시 시작 간격(DD), 계층 구조(DE·DF), 그림 데이터 머리말(DA)
        if ((marker >= SOF_FIRST && marker <= SOF_LAST) || marker == SOS || (marker >= DQT && marker <= EXP)) {
            return true;
        }
        return switch (marker) {
            // JFIF 머리말(화소 밀도). 같은 APP0 이라도 미리보기 그림을 담는 JFXX 는 버린다
            case APP0 -> startsWith(in, payloadStart, segmentEnd, JFIF);
            // 색 프로파일. 버리면 색이 달라 보인다. 같은 APP2 를 쓰는 MPF(뒤에 붙은 두 번째 사진의 목차)는 버린다
            case APP2 -> startsWith(in, payloadStart, segmentEnd, ICC_PROFILE);
            // Adobe 색 변환 표시. 버리면 일부 사진의 색이 뒤집혀 보인다. 같은 번호를 쓰는 다른 내용은 버린다
            case APP14 -> startsWith(in, payloadStart, segmentEnd, ADOBE);
            default -> false;
        };
    }

    // 그림 데이터 안에서 다음 조각이 시작되는 자리를 찾는다. 데이터 안의 0xFF 는 뒤에 0x00 을 붙여 표시와 구별하고,
    // 다시 시작 표시(RSTn)는 데이터의 일부다.
    private static int endOfScan(byte[] in, int from) {
        int n = in.length;
        int i = from;
        while (i < n) {
            if (u8(in, i) != MARKER) {
                i++;
                continue;
            }
            if (i + 1 >= n) {
                return n;
            }
            int next = u8(in, i + 1);
            if (next == 0x00 || (next >= RST_FIRST && next <= RST_LAST)) {
                i += 2;
            } else if (next == MARKER) {
                i++;
            } else {
                return i;
            }
        }
        return n;
    }

    // EXIF 조각에서 방향 값만 읽는다. 읽지 못하면 "바로 선 사진"으로 본다 — 방향을 모른다고 사진을 거절하지는 않는다.
    // 파일이 적어 둔 위치 값을 따라가며 읽으므로, 값을 쓰기 전에 조각 안인지 매번 확인한다.
    private static int readOrientation(byte[] in, int payloadStart, int segmentEnd) {
        if (!startsWith(in, payloadStart, segmentEnd, EXIF)) {
            return UPRIGHT;   // 같은 APP1 을 쓰는 XMP 등
        }
        int tiff = payloadStart + EXIF.length;
        if (segmentEnd - tiff < 8) {
            return UPRIGHT;
        }
        boolean littleEndian;
        if (in[tiff] == 'I' && in[tiff + 1] == 'I') {
            littleEndian = true;
        } else if (in[tiff] == 'M' && in[tiff + 1] == 'M') {
            littleEndian = false;
        } else {
            return UPRIGHT;
        }
        if (u16(in, tiff + 2, littleEndian) != 42) {
            return UPRIGHT;
        }
        long directory = tiff + u32(in, tiff + 4, littleEndian);
        if (directory + 2 > segmentEnd) {
            return UPRIGHT;
        }
        int entries = u16(in, (int) directory, littleEndian);
        for (int k = 0; k < entries; k++) {
            long entry = directory + 2 + (long) k * IFD_ENTRY_LENGTH;
            if (entry + IFD_ENTRY_LENGTH > segmentEnd) {
                return UPRIGHT;
            }
            int at = (int) entry;
            if (u16(in, at, littleEndian) != EXIF_ORIENTATION_TAG) {
                continue;
            }
            if (u16(in, at + 2, littleEndian) != EXIF_TYPE_SHORT || u32(in, at + 4, littleEndian) != 1) {
                return UPRIGHT;
            }
            int value = u16(in, at + 8, littleEndian);
            return value >= 1 && value <= 8 ? value : UPRIGHT;
        }
        return UPRIGHT;
    }

    // 방향 하나만 적은 EXIF 조각. 길이는 늘 같다.
    private static byte[] orientationSegment(int orientation) {
        return new byte[]{
                (byte) MARKER, (byte) APP1, 0x00, 0x22,                     // 조각 길이 34
                'E', 'x', 'i', 'f', 0, 0,
                'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,               // 큰 자리부터, 목록은 8번째 바이트에서 시작
                0x00, 0x01,                                                 // 항목 1개
                0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01,             // 방향, 2바이트 정수 1개
                0x00, (byte) orientation, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00                                      // 다음 목록 없음
        };
    }

    // ── PNG ───────────────────────────────────────────────────────────────
    //
    // PNG 는 8바이트 서명 뒤에 조각(청크)이 이어진다: 길이 4바이트 + 이름 4글자 + 내용 + 검사값 4바이트.
    // 부가 정보는 eXIf(EXIF)·tEXt/zTXt/iTXt(글자, XMP 포함)·tIME(시각) 조각에 들어 있다.

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final int PNG_CHUNK_OVERHEAD = 12;
    private static final String PNG_HEADER = "IHDR";
    private static final String PNG_END = "IEND";

    // 남길 조각: 그림 자체(IHDR·PLTE·IDAT·IEND), 색·투명도·화소 밀도, 움직이는 PNG.
    private static final Set<String> PNG_KEPT = Set.of(
            "IHDR", "PLTE", "IDAT", "IEND",
            "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "bKGD", "pHYs", "hIST", "sPLT", "cICP", "mDCV", "cLLI",
            "acTL", "fcTL", "fdAT");

    private static byte[] stripPng(byte[] in) {
        int n = in.length;
        if (!startsWith(in, 0, n, PNG_SIGNATURE)) {
            throw malformed(FileType.PNG, "서명 없음");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(n);
        out.write(in, 0, PNG_SIGNATURE.length);
        int pos = PNG_SIGNATURE.length;
        boolean first = true;

        while (true) {
            if (n - pos < PNG_CHUNK_OVERHEAD) {
                throw malformed(FileType.PNG, "끝 조각 없이 끝남");
            }
            long length = u32(in, pos, false);
            if (length > n - pos - PNG_CHUNK_OVERHEAD) {
                throw malformed(FileType.PNG, "조각 길이가 파일을 벗어남");
            }
            String name = new String(in, pos + 4, 4, StandardCharsets.ISO_8859_1);
            int chunkLength = PNG_CHUNK_OVERHEAD + (int) length;
            if (first && !PNG_HEADER.equals(name)) {
                throw malformed(FileType.PNG, "첫 조각이 머리말이 아님");
            }
            first = false;
            if (PNG_KEPT.contains(name)) {
                out.write(in, pos, chunkLength);
            }
            pos += chunkLength;
            if (PNG_END.equals(name)) {
                // 여기서 끝낸다 — 뒤에 무엇이 붙어 있든 옮기지 않는다
                break;
            }
        }
        return out.toByteArray();
    }
}
