package com.ktc4.backend.domain.file.service;

import com.ktc4.backend.domain.file.enums.FileType;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.ktc4.backend.support.SignupMultipart.concat;
import static com.ktc4.backend.support.SignupMultipart.pngChunk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 지워야 할 내용에는 눈에 띄는 글자(SECRET…)를 심어 두고, 결과에 그 글자가 남아 있는지로 확인한다.
// 좌표처럼 보이는 값도 모두 지어낸 것이다.
@DisplayName("ImageMetadataStripper")
class ImageMetadataStripperTest {

    private static final byte[] SOI = bytes(0xFF, 0xD8);
    private static final byte[] EOI = bytes(0xFF, 0xD9);
    private static final byte[] JFIF = jpegSegment(0xE0, concat(ascii("JFIF\0"), bytes(1, 1, 0, 0, 1, 0, 1, 0, 0)));
    // 그림을 이루는 조각들 — 내용은 구조만 맞춘 것이다
    private static final byte[] DQT = jpegSegment(0xDB, bytes(0, 1, 2, 3));
    private static final byte[] SOF = jpegSegment(0xC0, bytes(8, 0, 1, 0, 1, 1, 1, 0x11, 0));
    private static final byte[] DHT = jpegSegment(0xC4, bytes(0, 1, 2, 3));
    private static final byte[] SOS = jpegSegment(0xDA, bytes(1, 1, 0, 0, 0x3F, 0));

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] jpegSegment(int marker, byte[] payload) {
        int length = payload.length + 2;
        return concat(bytes(0xFF, marker, length >> 8, length & 0xFF), payload);
    }

    // 방향과 "좌표"를 담은 EXIF 조각
    private static byte[] exif(ByteOrder order, int orientation) {
        ByteBuffer tiff = ByteBuffer.allocate(8 + 2 + 12 + 4).order(order);
        tiff.put(order == ByteOrder.BIG_ENDIAN ? ascii("MM") : ascii("II"));
        tiff.putShort((short) 42).putInt(8);
        tiff.putShort((short) 1);
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        tiff.putInt(0);
        return jpegSegment(0xE1, concat(ascii("Exif\0\0"), tiff.array(), ascii("SECRET-GPS-00.0000,000.0000")));
    }

    private static byte[] strip(FileType type, byte[] content) {
        return ImageMetadataStripper.strip(type, content);
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) {
                return true;
            }
        }
        return false;
    }

    private static void assertRejected(FileType type, byte[] content) {
        assertThatThrownBy(() -> strip(type, content))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE));
    }

    // 방향만 적은 조각 — 지운 뒤 다시 써 넣는 것
    private static byte[] orientationOnly(int orientation) {
        return bytes(0xFF, 0xE1, 0x00, 0x22, 'E', 'x', 'i', 'f', 0, 0, 'M', 'M', 0, 0x2A, 0, 0, 0, 8,
                0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation, 0, 0, 0, 0, 0, 0);
    }

    @Nested
    @DisplayName("JPEG")
    class Jpeg {

        @Test
        @DisplayName("위치 정보(EXIF)·XMP·글자 조각·그 밖의 부가 조각을 지우고, 그림을 이루는 조각은 그대로 둔다")
        void removesMetadataKeepsImage() {
            byte[] scan = bytes(0x12, 0x34, 0xFF, 0x00, 0x56, 0xFF, 0xD0, 0x78);   // FF00 과 다시 시작 표시는 그림 데이터다
            byte[] photo = concat(SOI, JFIF,
                    exif(ByteOrder.BIG_ENDIAN, 1),
                    jpegSegment(0xE1, ascii("http://ns.adobe.com/xap/1.0/\0<x>SECRET-XMP</x>")),
                    jpegSegment(0xED, ascii("Photoshop 3.0\0SECRET-IPTC")),
                    jpegSegment(0xFE, ascii("SECRET-COMMENT")),
                    jpegSegment(0xE5, ascii("SECRET-UNKNOWN-APP")),
                    DQT, SOF, DHT, SOS, scan, EOI);

            byte[] cleaned = strip(FileType.JPEG, photo);

            assertThat(contains(cleaned, ascii("SECRET"))).isFalse();
            assertThat(cleaned).isEqualTo(concat(SOI, JFIF, DQT, SOF, DHT, SOS, scan, EOI));
        }

        @ParameterizedTest(name = "[{index}] 방향 {0}")
        @ValueSource(ints = {2, 3, 6, 8})
        @DisplayName("사진의 방향은 남긴다 — 방향만 적은 조각으로 바꿔 넣는다. 안 그러면 세로 사진이 누워 보인다")
        void keepsOrientationOnly(int orientation) {
            byte[] photo = concat(SOI, JFIF, exif(ByteOrder.BIG_ENDIAN, orientation), DQT, SOS, bytes(1, 2), EOI);

            byte[] cleaned = strip(FileType.JPEG, photo);

            assertThat(contains(cleaned, ascii("SECRET"))).isFalse();
            assertThat(cleaned).isEqualTo(concat(SOI, JFIF, orientationOnly(orientation), DQT, SOS, bytes(1, 2), EOI));
        }

        @Test
        @DisplayName("작은 자리부터 적는 EXIF(II)에서도 방향을 읽고, JFIF 머리말이 없으면 시작 표시 바로 뒤에 넣는다")
        void readsLittleEndianOrientation() {
            byte[] photo = concat(SOI, exif(ByteOrder.LITTLE_ENDIAN, 6), DQT, SOS, bytes(1, 2), EOI);

            assertThat(strip(FileType.JPEG, photo))
                    .isEqualTo(concat(SOI, orientationOnly(6), DQT, SOS, bytes(1, 2), EOI));
        }

        @Test
        @DisplayName("부가 정보가 없는 사진은 한 바이트도 바뀌지 않는다")
        void cleanPhotoIsUnchanged() {
            byte[] photo = concat(SOI, JFIF, DQT, SOF, DHT, SOS, bytes(1, 2, 3), EOI);

            assertThat(strip(FileType.JPEG, photo)).isEqualTo(photo);
        }

        @Test
        @DisplayName("색 프로파일과 Adobe 색 표시는 남긴다 — 버리면 색이 달라 보인다. 미리보기 그림(JFXX)과 MPF 목차는 버린다")
        void keepsColorSegmentsOnly() {
            byte[] icc = jpegSegment(0xE2, ascii("ICC_PROFILE\0\1\1profile"));
            byte[] adobe = jpegSegment(0xEE, ascii("Adobe\0d\0\0\0\0\1"));
            byte[] photo = concat(SOI, JFIF,
                    jpegSegment(0xE0, ascii("JFXX\0SECRET-THUMBNAIL")),
                    icc,
                    jpegSegment(0xE2, ascii("MPF\0SECRET-MPF")),
                    adobe, DQT, SOS, bytes(1), EOI);

            byte[] cleaned = strip(FileType.JPEG, photo);

            assertThat(cleaned).isEqualTo(concat(SOI, JFIF, icc, adobe, DQT, SOS, bytes(1), EOI));
        }

        @Test
        @DisplayName("그림에 쓰이는 표시로 적어 두지 않은 조각은 버린다 — 표준이 비워 둔 번호, Adobe 가 아닌 APP14")
        void dropsUnlistedSegments() {
            byte[] restartInterval = jpegSegment(0xDD, bytes(0, 8));   // 다시 시작 간격 — 그림에 쓰인다
            byte[] photo = concat(SOI, JFIF,
                    jpegSegment(0xF0, ascii("SECRET-RESERVED-JPG0")),
                    jpegSegment(0xFD, ascii("SECRET-RESERVED-JPG13")),
                    jpegSegment(0x02, ascii("SECRET-RESERVED-02")),
                    jpegSegment(0xBF, ascii("SECRET-RESERVED-BF")),
                    jpegSegment(0xEE, ascii("NotAdobe-SECRET-APP14")),
                    DQT, SOF, restartInterval, DHT, SOS, bytes(1), EOI);

            byte[] cleaned = strip(FileType.JPEG, photo);

            assertThat(contains(cleaned, ascii("SECRET"))).isFalse();
            assertThat(cleaned).isEqualTo(concat(SOI, JFIF, DQT, SOF, restartInterval, DHT, SOS, bytes(1), EOI));
        }

        @Test
        @DisplayName("그림의 끝 뒤에 붙은 내용은 버린다 — 덧붙인 동영상·두 번째 사진·숨긴 스크립트")
        void dropsTrailingData() {
            byte[] photo = concat(SOI, JFIF, DQT, SOS, bytes(1, 2), EOI);
            byte[] withTrailer = concat(photo, SOI, exif(ByteOrder.BIG_ENDIAN, 1), EOI, ascii("<script>SECRET</script>"));

            assertThat(strip(FileType.JPEG, withTrailer)).isEqualTo(photo);
        }

        @Test
        @DisplayName("그림 데이터가 여러 번 나뉘어 있어도(점점 선명해지는 JPEG) 끝까지 따라가고, 사이에 낀 부가 조각도 지운다")
        void followsMultipleScans() {
            byte[] photo = concat(SOI, JFIF, DQT, SOF,
                    DHT, SOS, bytes(1, 0xFF, 0x00, 2),
                    jpegSegment(0xFE, ascii("SECRET-BETWEEN-SCANS")),
                    DHT, SOS, bytes(3, 4, 0xFF, 0xD7, 5),
                    EOI, ascii("SECRET-TRAILER"));

            assertThat(strip(FileType.JPEG, photo)).isEqualTo(concat(SOI, JFIF, DQT, SOF,
                    DHT, SOS, bytes(1, 0xFF, 0x00, 2), DHT, SOS, bytes(3, 4, 0xFF, 0xD7, 5), EOI));
        }

        @Test
        @DisplayName("끝 표시 없이 끝난 사진은 받아 주고 끝 표시를 붙인다")
        void appendsMissingEndMarker() {
            byte[] photo = concat(SOI, JFIF, DQT, SOS, bytes(1, 2, 3));

            assertThat(strip(FileType.JPEG, photo)).isEqualTo(concat(photo, EOI));
        }

        @Test
        @DisplayName("EXIF 가 깨져 있으면 방향을 모르는 것으로 보고 지우기만 한다 — 사진을 거절하지 않는다")
        void toleratesBrokenExif() {
            // 목록 위치가 조각 밖을 가리킨다 / 항목 수가 조각보다 많다 / 순서 표시가 아니다 / 너무 짧다
            byte[] offsetOutside = jpegSegment(0xE1,
                    concat(ascii("Exif\0\0MM"), bytes(0, 42, 0x7F, 0xFF, 0xFF, 0xFF), ascii("SECRET")));
            byte[] tooManyEntries = jpegSegment(0xE1,
                    concat(ascii("Exif\0\0MM"), bytes(0, 42, 0, 0, 0, 8, 0xFF, 0xFF), ascii("SECRET")));
            byte[] badOrder = jpegSegment(0xE1, concat(ascii("Exif\0\0XX"), bytes(0, 42, 0, 0, 0, 8, 0, 0)));
            byte[] tooShort = jpegSegment(0xE1, ascii("Exif\0\0MM"));
            byte[] clean = concat(SOI, JFIF, DQT, SOS, bytes(1), EOI);

            for (byte[] broken : new byte[][]{offsetOutside, tooManyEntries, badOrder, tooShort}) {
                assertThat(strip(FileType.JPEG, concat(SOI, JFIF, broken, DQT, SOS, bytes(1), EOI))).isEqualTo(clean);
            }
        }

        @Test
        @DisplayName("방향 값이 1~8 밖이면 쓰지 않는다")
        void ignoresInvalidOrientation() {
            byte[] clean = concat(SOI, JFIF, DQT, SOS, bytes(1), EOI);

            assertThat(strip(FileType.JPEG, concat(SOI, JFIF, exif(ByteOrder.BIG_ENDIAN, 9), DQT, SOS, bytes(1), EOI)))
                    .isEqualTo(clean);
            assertThat(strip(FileType.JPEG, concat(SOI, JFIF, exif(ByteOrder.BIG_ENDIAN, 0), DQT, SOS, bytes(1), EOI)))
                    .isEqualTo(clean);
        }

        @Test
        @DisplayName("구조가 맞지 않으면 거절한다 — 조각 길이가 파일을 벗어남, 길이가 끊김, 조각 자리에 표시가 없음")
        void rejectsMalformed() {
            assertRejected(FileType.JPEG, concat(SOI, bytes(0xFF, 0xE0, 0x00, 0x10), ascii("JFIF")));   // 길이 16 인데 4바이트뿐
            assertRejected(FileType.JPEG, concat(SOI, bytes(0xFF, 0xE0, 0x00)));
            assertRejected(FileType.JPEG, concat(SOI, bytes(0xFF, 0xE0, 0x00, 0x01)));                  // 길이가 2보다 작다
            assertRejected(FileType.JPEG, concat(SOI, bytes(0x12, 0x34)));
            assertRejected(FileType.JPEG, concat(SOI, bytes(0xFF, 0xFF)));
            assertRejected(FileType.JPEG, concat(SOI, SOI, EOI));
            assertRejected(FileType.JPEG, bytes(0xFF, 0xD8, 0xFF));
            assertRejected(FileType.JPEG, bytes(0x00, 0x01, 0x02, 0x03));
        }

        @Test
        @DisplayName("실제로 만든 사진에 위치 정보를 끼워 넣고 지워도 사진이 그대로 열린다")
        void realPhotoStillDecodes() throws IOException {
            byte[] real = encode("jpg");
            // 시작 표시 바로 뒤에 EXIF 를 끼운다
            byte[] withExif = concat(Arrays.copyOfRange(real, 0, 2), exif(ByteOrder.LITTLE_ENDIAN, 6),
                    Arrays.copyOfRange(real, 2, real.length), ascii("SECRET-TRAILER"));

            byte[] cleaned = strip(FileType.JPEG, withExif);

            assertThat(contains(cleaned, ascii("SECRET"))).isFalse();
            assertThat(contains(cleaned, orientationOnly(6))).isTrue();
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(cleaned));
            assertThat(decoded).isNotNull();
            assertThat(decoded.getWidth()).isEqualTo(16);
            assertThat(decoded.getHeight()).isEqualTo(8);
            // 지운 결과를 한 번 더 지워도 달라지지 않는다
            assertThat(strip(FileType.JPEG, cleaned)).isEqualTo(cleaned);
        }

        @Test
        @DisplayName("그림 데이터가 여러 덩어리로 나뉜 실제 사진(점점 선명해지는 JPEG)도 지운 뒤 그대로 열린다")
        void realProgressivePhotoStillDecodes() throws IOException {
            byte[] real = encodeProgressiveJpeg();
            // 덩어리가 실제로 여러 개인 파일인지 먼저 확인한다 — 아니면 이 테스트가 보려는 길을 타지 않는다
            assertThat(countOf(real, bytes(0xFF, 0xDA))).isGreaterThan(1);
            byte[] withExif = concat(Arrays.copyOfRange(real, 0, 2), exif(ByteOrder.BIG_ENDIAN, 8),
                    jpegSegment(0xFE, ascii("SECRET-COMMENT")), Arrays.copyOfRange(real, 2, real.length));

            byte[] cleaned = strip(FileType.JPEG, withExif);

            assertThat(contains(cleaned, ascii("SECRET"))).isFalse();
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(cleaned));
            assertThat(decoded).isNotNull();
            assertThat(decoded.getWidth()).isEqualTo(64);
            assertThat(decoded.getHeight()).isEqualTo(48);
            // 부가 조각을 끼우기 전의 사진에서 달라진 것은 방향 조각 하나뿐이다 — 그림 데이터는 한 바이트도 잃지 않았다
            assertThat(cleaned.length).isEqualTo(real.length + orientationOnly(8).length);
        }
    }

    private static int countOf(byte[] haystack, byte[] needle) {
        int count = 0;
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) {
                count++;
            }
        }
        return count;
    }

    private static byte[] encodeProgressiveJpeg() throws IOException {
        BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 48; y++) {
                image.setRGB(x, y, (x * 4) << 16 | (y * 5) << 8 | ((x * y) & 0xFF));
            }
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    @Nested
    @DisplayName("PNG")
    class Png {

        private static final byte[] SIGNATURE = bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
        private static final byte[] IHDR = pngChunk("IHDR", bytes(0, 0, 0, 1, 0, 0, 0, 1, 8, 0, 0, 0, 0));
        private static final byte[] IDAT = pngChunk("IDAT", bytes(0x78, 0x9C, 0x63, 0, 0, 0, 2, 0, 1));
        private static final byte[] IEND = pngChunk("IEND", new byte[0]);

        @Test
        @DisplayName("EXIF·글자·시각 조각과 모르는 조각을 지우고, 그림과 색·화소 밀도 조각은 그대로 둔다")
        void removesMetadataKeepsImage() {
            byte[] phys = pngChunk("pHYs", bytes(0, 0, 0x0B, 0x13, 0, 0, 0x0B, 0x13, 1));
            byte[] gamma = pngChunk("gAMA", bytes(0, 0, 0xB1, 0x8F));
            byte[] photo = concat(SIGNATURE, IHDR, gamma, phys,
                    pngChunk("eXIf", ascii("MM\0*SECRET-GPS")),
                    pngChunk("tEXt", ascii("Comment\0SECRET-TEXT")),
                    pngChunk("zTXt", ascii("Raw\0\0SECRET-ZTXT")),
                    pngChunk("iTXt", ascii("XML:com.adobe.xmp\0\0\0\0\0SECRET-XMP")),
                    pngChunk("tIME", bytes(7, 0xEA, 10, 14, 9, 0, 0)),
                    pngChunk("prVt", ascii("SECRET-PRIVATE-CHUNK")),
                    IDAT, IEND);

            byte[] cleaned = strip(FileType.PNG, photo);

            assertThat(contains(cleaned, ascii("SECRET"))).isFalse();
            assertThat(cleaned).isEqualTo(concat(SIGNATURE, IHDR, gamma, phys, IDAT, IEND));
        }

        @Test
        @DisplayName("부가 정보가 없는 사진은 한 바이트도 바뀌지 않는다")
        void cleanPhotoIsUnchanged() {
            byte[] photo = concat(SIGNATURE, IHDR, IDAT, IDAT, IEND);

            assertThat(strip(FileType.PNG, photo)).isEqualTo(photo);
        }

        @Test
        @DisplayName("끝 조각 뒤에 붙은 내용은 버린다")
        void dropsTrailingData() {
            byte[] photo = concat(SIGNATURE, IHDR, IDAT, IEND);

            assertThat(strip(FileType.PNG, concat(photo, ascii("<script>SECRET</script>")))).isEqualTo(photo);
        }

        @Test
        @DisplayName("구조가 맞지 않으면 거절한다 — 끝 조각이 없음, 조각 길이가 파일을 벗어남, 첫 조각이 머리말이 아님")
        void rejectsMalformed() {
            assertRejected(FileType.PNG, concat(SIGNATURE, IHDR, IDAT));
            assertRejected(FileType.PNG, concat(SIGNATURE, bytes(0, 0, 0, 0x0D)));                          // 앞 12바이트뿐
            assertRejected(FileType.PNG, concat(SIGNATURE, bytes(0x7F, 0xFF, 0xFF, 0xFF), ascii("IHDR"), new byte[20]));
            assertRejected(FileType.PNG, concat(SIGNATURE, bytes(0xFF, 0xFF, 0xFF, 0xFF), ascii("IHDR"), new byte[20]));
            assertRejected(FileType.PNG, concat(SIGNATURE, IDAT, IHDR, IEND));
            assertRejected(FileType.PNG, concat(SIGNATURE, IEND));
            assertRejected(FileType.PNG, bytes(0x89, 'P', 'N', 'G'));
        }

        @Test
        @DisplayName("실제로 만든 사진에 위치 정보를 끼워 넣고 지워도 사진이 그대로 열린다")
        void realPhotoStillDecodes() throws IOException {
            byte[] real = encode("png");
            // 머리말 조각(서명 8 + 조각 25) 바로 뒤에 부가 조각을 끼운다
            int afterHeader = 8 + 25;
            byte[] withMetadata = concat(Arrays.copyOfRange(real, 0, afterHeader),
                    pngChunk("eXIf", ascii("MM\0*SECRET-GPS")),
                    pngChunk("tEXt", ascii("Location\0SECRET-TEXT")),
                    Arrays.copyOfRange(real, afterHeader, real.length), ascii("SECRET-TRAILER"));

            byte[] cleaned = strip(FileType.PNG, withMetadata);

            assertThat(contains(cleaned, ascii("SECRET"))).isFalse();
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(cleaned));
            assertThat(decoded).isNotNull();
            assertThat(decoded.getWidth()).isEqualTo(16);
            assertThat(decoded.getHeight()).isEqualTo(8);
            assertThat(strip(FileType.PNG, cleaned)).isEqualTo(cleaned);
        }
    }

    @Test
    @DisplayName("사진이 아닌 형식(PDF)은 그대로 돌려준다")
    void pdfIsUntouched() {
        byte[] pdf = ascii("%PDF-1.7\n%anything");

        assertThat(strip(FileType.PDF, pdf)).isSameAs(pdf);
        assertThatCode(() -> strip(FileType.PDF, new byte[0])).doesNotThrowAnyException();
    }

    // 자바로 실제 그림을 만들어 인코딩한다 — 손으로 짠 조각이 아니라 진짜 인코더가 만든 파일이다
    private static byte[] encode(String format) throws IOException {
        BufferedImage image = new BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 8; y++) {
                image.setRGB(x, y, (x * 16) << 16 | (y * 32) << 8 | 0x40);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, out)).isTrue();
        return out.toByteArray();
    }
}
