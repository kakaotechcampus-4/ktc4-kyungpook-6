package com.ktc4.backend.domain.store.dto;

/**
 * 승인할 때 가입 신청서에서 가게로 옮겨 적는 메뉴 한 개.
 *
 * @param name        메뉴 이름
 * @param photoFileId 메뉴 사진의 파일 ID. 사진이 없으면 null
 */
public record MenuSeed(String name, String photoFileId) {
}
