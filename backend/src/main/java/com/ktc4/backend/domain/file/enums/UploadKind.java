package com.ktc4.backend.domain.file.enums;

import java.util.Set;

// 올리는 파일의 종류. 종류마다 받는 형식과 크기가 다르고, 누가 볼 수 있는지도 다르다 —
// 가게 사진은 승인 뒤 그 가게의 점주와 관리자가 보고, 사업자등록증은 언제나 관리자만 본다.
public enum UploadKind {
    STORE_IMAGE(Set.of(FileType.JPEG, FileType.PNG), 5),  // 가게 대표 사진
    MENU_IMAGE(Set.of(FileType.JPEG, FileType.PNG), 5),   // 메뉴 사진 — 메뉴 하나에 한 장
    // 사업자등록증 — 스캔본(PDF)이거나 휴대폰으로 찍은 사진이다. 사진이면 다른 사진처럼 위치 정보를 지우고, 크기는 사진 상한을 따른다
    BIZ_CERT(Set.of(FileType.PDF, FileType.JPEG, FileType.PNG), 10);

    /**
     * 가게 사진으로 보여 주는 종류. 사업자등록증은 여기에 없다 — 언제나 관리자만 본다.
     * 종류를 새로 만들면 기본은 "보여 주지 않음"이다. 보여 줄 종류만 여기에 적는다.
     */
    public static final Set<UploadKind> STORE_PHOTO_KINDS = Set.of(STORE_IMAGE, MENU_IMAGE);

    private static final long BYTES_PER_MB = 1024L * 1024L;

    /**
     * 사진의 크기 상한 — 종류를 가리지 않는다. 사진은 위치 정보를 지우느라 원본과 지운 결과를 함께 메모리에 올리므로
     * PDF(디스크로 바로 씀)보다 작게 둔다.
     */
    public static final long IMAGE_MAX_BYTES = 5 * BYTES_PER_MB;

    private final Set<FileType> allowedTypes;
    private final long maxBytes;

    UploadKind(Set<FileType> allowedTypes, int maxMegabytes) {
        this.allowedTypes = allowedTypes;
        this.maxBytes = maxMegabytes * BYTES_PER_MB;
    }

    public boolean allows(FileType type) {
        return type != null && allowedTypes.contains(type);
    }

    /** 이 종류로 받는 가장 큰 크기. 형식을 가리기 전에 본다. */
    public long maxBytes() {
        return maxBytes;
    }

    /** 이 종류·형식으로 받는 가장 큰 크기. 사진이면 {@link #IMAGE_MAX_BYTES} 를 넘지 않는다. */
    public long maxBytes(FileType type) {
        return type.isImage() ? Math.min(maxBytes, IMAGE_MAX_BYTES) : maxBytes;
    }
}
