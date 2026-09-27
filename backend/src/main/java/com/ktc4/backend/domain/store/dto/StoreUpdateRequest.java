package com.ktc4.backend.domain.store.dto;

import com.ktc4.backend.domain.store.enums.StoreStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 가게 정보 수정 요청 DTO.
 *
 * <p>부분 수정이라 네 필드 모두 선택이다. 담아 보낸 필드만 바뀌고 빠뜨린 필드는 기존 값을 유지한다.
 * 길이 제한은 {@code Store} 엔티티의 컬럼 길이와 맞췄다.
 *
 * <p>이름·주소는 NOT NULL 컬럼이라 빈 값으로 지울 수 없다. {@code @Size(min = 1)} 만으로는
 * 공백뿐인 문자열({@code "   "})이 통과하므로 {@code @Pattern} 으로 한 번 더 막는다.
 * 전화번호는 빈 문자열로 지우는 것을 허용한다.
 *
 * <p>업종·위경도·사업자등록번호는 수정 화면에 입력 칸이 없어 여기 담지 않는다.
 * 확인 시각({@code lastCheckedAt})은 서버가 정해야 하는 값이라
 * {@code POST /api/stores/{storeId}/confirm} 으로 분리했다.
 */
public record StoreUpdateRequest(
        @Schema(description = "가게명", example = "맛나 치킨")
        @Size(min = 1, max = 200, message = "가게명은 1~200자여야 합니다")
        @Pattern(regexp = "(?s).*\\S.*", message = "가게명은 공백만으로 채울 수 없습니다")
        String name,

        @Schema(description = "도로명 주소", example = "대구광역시 북구 대학로 80")
        @Size(min = 1, max = 500, message = "도로명 주소는 1~500자여야 합니다")
        @Pattern(regexp = "(?s).*\\S.*", message = "도로명 주소는 공백만으로 채울 수 없습니다")
        String addressRoad,

        @Schema(description = "전화번호", example = "010-1234-5678")
        @Size(max = 20, message = "전화번호는 20자를 넘을 수 없습니다")
        String phone,

        @Schema(description = "영업 상태 (OPEN: 영업중, SUSPENDED: 휴업, CLOSED: 폐업, UNKNOWN: 미확인)",
                example = "OPEN")
        StoreStatus status
) {
}
