package com.ktc4.backend.domain.file.enums;

import java.util.Set;

// 올리는 파일의 종류. 종류마다 받는 형식과 크기가 다르고, 누가 볼 수 있는지도 다르다 —
// 가게 사진은 승인 뒤 누구나 보고, 사업자등록증은 언제나 관리자만 본다.
public enum UploadKind {
    STORE_IMAGE(Set.of(FileType.JPEG, FileType.PNG, FileType.WEBP), 5),  // 가게 대표 사진
    MENU_IMAGE(Set.of(FileType.JPEG, FileType.PNG, FileType.WEBP), 5),   // 메뉴 사진
    BIZ_CERT(Set.of(FileType.PDF), 10);                                  // 사업자등록증 — 스캔본이라 사진보다 크게 받는다

    /**
     * 가입이 승인된 뒤 누구나 볼 수 있는 종류. 사업자등록증은 여기에 없다 — 언제나 관리자만 본다.
     * 종류를 새로 만들면 기본은 "공개 안 함"이다. 공개할 종류만 여기에 적는다.
     */
    public static final Set<UploadKind> PUBLIC_KINDS = Set.of(STORE_IMAGE, MENU_IMAGE);

    private static final long BYTES_PER_MB = 1024L * 1024L;

    private final Set<FileType> allowedTypes;
    private final long maxBytes;

    UploadKind(Set<FileType> allowedTypes, int maxMegabytes) {
        this.allowedTypes = allowedTypes;
        this.maxBytes = maxMegabytes * BYTES_PER_MB;
    }

    public boolean allows(FileType type) {
        return type != null && allowedTypes.contains(type);
    }

    public long maxBytes() {
        return maxBytes;
    }
}
