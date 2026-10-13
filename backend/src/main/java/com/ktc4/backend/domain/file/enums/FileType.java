package com.ktc4.backend.domain.file.enums;

// 올릴 수 있는 파일 형식. 형식은 파일 이름이나 보낸 쪽이 적은 Content-Type 이 아니라 파일 앞부분의 실제 내용으로 가린다 —
// 둘 다 보내는 쪽이 마음대로 적을 수 있어, 그것만 믿으면 이름만 .jpg 인 HTML·스크립트가 들어온다.
// SVG 는 받지 않는다. 글자로 된 형식이라 안에 스크립트를 넣을 수 있고, 브라우저가 열면 실행된다.
//
// 확장자와 미디어 타입도 여기서 정한다. 저장할 때의 파일 이름과 내려줄 때의 Content-Type 을 보낸 쪽이 적은 값이 아니라
// 서버가 가린 형식으로 고정하기 위해서다.
public enum FileType {
    JPEG("jpg", "image/jpeg") {
        @Override
        boolean matches(byte[] head) {
            return startsWith(head, 0, 0xFF, 0xD8, 0xFF);
        }
    },
    PNG("png", "image/png") {
        @Override
        boolean matches(byte[] head) {
            return startsWith(head, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
        }
    },
    WEBP("webp", "image/webp") {
        // "RIFF" + 크기 4바이트 + "WEBP"
        @Override
        boolean matches(byte[] head) {
            return startsWith(head, 0, 'R', 'I', 'F', 'F') && startsWith(head, 8, 'W', 'E', 'B', 'P');
        }
    },
    PDF("pdf", "application/pdf") {
        // "%PDF-"
        @Override
        boolean matches(byte[] head) {
            return startsWith(head, 0, '%', 'P', 'D', 'F', '-');
        }
    };

    /** 형식을 가리는 데 필요한 파일 앞부분의 길이. */
    public static final int HEAD_LENGTH = 12;

    private final String extension;
    private final String mediaType;

    FileType(String extension, String mediaType) {
        this.extension = extension;
        this.mediaType = mediaType;
    }

    /** 저장할 때 파일 이름 뒤에 붙이는 확장자(점 없이). */
    public String extension() {
        return extension;
    }

    /** 내려줄 때 쓰는 Content-Type. */
    public String mediaType() {
        return mediaType;
    }

    abstract boolean matches(byte[] head);

    /**
     * 파일 앞부분으로 형식을 가린다.
     *
     * @param head 파일의 앞 {@value #HEAD_LENGTH}바이트. 파일이 그보다 짧으면 있는 만큼
     * @return 알아본 형식. 받지 않는 형식이면 {@code null}
     */
    public static FileType detect(byte[] head) {
        for (FileType type : values()) {
            if (type.matches(head)) {
                return type;
            }
        }
        return null;
    }

    private static boolean startsWith(byte[] head, int offset, int... signature) {
        if (head.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((head[offset + i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
