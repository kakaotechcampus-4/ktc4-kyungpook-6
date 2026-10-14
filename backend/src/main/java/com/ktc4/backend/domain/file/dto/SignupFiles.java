package com.ktc4.backend.domain.file.dto;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 점주 가입 신청에 함께 온 파일 묶음.
 *
 * @param storeImage 가게 대표 사진
 * @param menuImages 메뉴 사진. 없으면 빈 목록
 * @param bizCert    사업자등록증
 */
public record SignupFiles(MultipartFile storeImage, List<MultipartFile> menuImages, MultipartFile bizCert) {

    public SignupFiles {
        // 메뉴 사진 칸을 아예 보내지 않으면 null 로 온다 — 받는 쪽이 매번 가리지 않게 빈 목록으로 맞춘다
        menuImages = menuImages == null ? List.of() : List.copyOf(menuImages);
    }
}
