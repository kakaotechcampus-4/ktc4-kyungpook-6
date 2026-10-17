package com.ktc4.backend.domain.file.dto;

import java.util.List;
import java.util.Objects;

/**
 * 가입 신청에 실린 파일 ID 묶음.
 *
 * @param storeImageFileId 가게 대표 사진. 없으면 {@code null}
 * @param bizCertFileId    사업자등록증
 * @param menuPhotoFileIds 메뉴 사진. 사진이 없는 메뉴는 빠진다
 */
public record SignupFileIds(String storeImageFileId, String bizCertFileId, List<String> menuPhotoFileIds) {

    public SignupFileIds {
        menuPhotoFileIds = menuPhotoFileIds == null
                ? List.of()
                : menuPhotoFileIds.stream().filter(Objects::nonNull).toList();
    }
}
