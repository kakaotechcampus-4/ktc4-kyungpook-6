package com.ktc4.backend.domain.auth.dto;

import com.ktc4.backend.domain.file.FileIdFormat;
import com.ktc4.backend.domain.store.dto.BusinessHourRequest;
import com.ktc4.backend.domain.store.dto.MenuRequest;
import com.ktc4.backend.domain.store.validation.NonOverlappingBusinessHours;
import com.ktc4.backend.global.util.LogMasking;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

import static com.ktc4.backend.global.util.TextPatterns.HAS_LETTER_OR_DIGIT;
import static com.ktc4.backend.global.util.TextPatterns.NO_INVISIBLE_CHARACTERS;
import static com.ktc4.backend.global.util.TextPatterns.NO_STACKED_MARKS;

/**
 * 점주 가입 신청.
 *
 * <p>사업자 정보는 관리자가 승인 여부를 판단하는 자료다. 사업자등록번호는 하이픈이 있어도 되고, 서버가
 * 숫자 10자리로 맞춰 확인한다 — 형식 확인을 여기서 하지 않는 이유는 "123-45-67890" 같은 표기를 그대로 받기 위해서다.
 * 휴대폰 번호도 같은 이유로 서버가 숫자만 남겨 확인한다.
 *
 * <p>상호명·대표자 이름은 글자 없는 이름과 보이지 않는 글자를 막는다 — 규칙과 이유는
 * {@link com.ktc4.backend.global.util.TextPatterns} 에 있다(메뉴 이름도 같은 규칙을 쓴다).
 *
 * <p>가게 정보(주소·가게 전화·영업시간·메뉴)는 관리자가 어느 가게인지 찾고 승인할지 판단하는 자료이고, 승인하면 그 가게에
 * 옮겨 적는다. 모두 필수다 — 메뉴가 있어야 주문을 받고, 영업시간은 가입 뒤 따로 채우지 않을 가능성이 크다.
 *
 * <p>사진·사업자등록증은 이 요청에 싣지 않는다. 먼저 {@code POST /api/files} 로 올리고, 받은 파일 ID 만 싣는다
 * (멘토 10주차 리뷰 — 가입 API 와 파일 업로드 API 를 나눈다). 사업자등록증은 필수, 가게 대표 사진은 선택,
 * 메뉴 사진은 메뉴마다 선택이다({@link SignupMenuRequest#photoFileId()}).
 *
 * <p>비밀번호 최대 길이(72)는 글자 수 기준이다. BCrypt 는 바이트 기준으로 72까지만 비교하므로, 한글처럼
 * 여러 바이트인 글자로 72바이트를 넘기면 {@code AuthService} 가 한 번 더 거절한다.
 */
public record OwnerSignupRequest(
        @Schema(description = "로그인 이메일", example = "owner@example.com")
        @NotBlank(message = "이메일을 입력해 주세요")
        @Email(message = "이메일 형식이 올바르지 않습니다")
        @Size(max = 254, message = "이메일은 254자를 넘을 수 없습니다")
        String email,

        @Schema(description = "비밀번호 (8~72자)", example = "password1234")
        @NotBlank(message = "비밀번호를 입력해 주세요")
        @Size(min = 8, max = 72, message = "비밀번호는 8~72자여야 합니다")
        String password,

        @Schema(description = "사업자등록번호 (하이픈 있어도 됨)", example = "123-45-67890")
        @NotBlank(message = "사업자등록번호를 입력해 주세요")
        @Size(max = 20, message = "사업자등록번호가 너무 깁니다")
        String bizNo,

        @Schema(description = "상호명", example = "예시분식")
        @NotBlank(message = "상호명을 입력해 주세요")
        @Size(max = 200, message = "상호명은 200자를 넘을 수 없습니다")
        @Pattern(regexp = HAS_LETTER_OR_DIGIT, message = "상호명에는 글자나 숫자가 하나 이상 있어야 합니다")
        @Pattern(regexp = NO_INVISIBLE_CHARACTERS, message = "상호명에 줄바꿈이나 보이지 않는 글자를 넣을 수 없습니다")
        @Pattern(regexp = NO_STACKED_MARKS, message = "상호명에 겹쳐 쓴 부호가 너무 많습니다")
        String storeName,

        @Schema(description = "대표자 이름", example = "홍길동")
        @NotBlank(message = "대표자 이름을 입력해 주세요")
        @Size(max = 50, message = "대표자 이름은 50자를 넘을 수 없습니다")
        @Pattern(regexp = HAS_LETTER_OR_DIGIT, message = "대표자 이름에는 글자나 숫자가 하나 이상 있어야 합니다")
        @Pattern(regexp = NO_INVISIBLE_CHARACTERS, message = "대표자 이름에 줄바꿈이나 보이지 않는 글자를 넣을 수 없습니다")
        @Pattern(regexp = NO_STACKED_MARKS, message = "대표자 이름에 겹쳐 쓴 부호가 너무 많습니다")
        String representativeName,

        @Schema(description = "점주 휴대폰 번호 (하이픈 있어도 됨). 가게를 등록할 때 적은 번호와 같으면 후보 가게를 찾는 데 쓰인다",
                example = "010-0000-0000")
        @NotBlank(message = "휴대폰 번호를 입력해 주세요")
        @Size(max = 20, message = "휴대폰 번호가 너무 깁니다")
        String phone,

        @Schema(description = """
                가게 주소 (500자 이하). 관리자가 어느 가게인지 찾는 단서다. 가게 자료의 도로명 주소(addressRoad)와
                자동으로 이어지지 않는다""", example = "대구광역시 북구 대학로 80")
        @NotBlank(message = "가게 주소를 입력해 주세요")
        @Size(max = 500, message = "가게 주소는 {max}자를 넘을 수 없습니다")
        @Pattern(regexp = NO_INVISIBLE_CHARACTERS, message = "가게 주소에 줄바꿈이나 보이지 않는 글자를 넣을 수 없습니다")
        String storeAddress,

        @Schema(description = "가게 전화번호 (20자 이하). 대표자 휴대폰과 달리 매장 유선 번호도 된다", example = "053-123-4567")
        @NotBlank(message = "가게 전화번호를 입력해 주세요")
        @Size(max = 20, message = "가게 전화번호는 {max}자를 넘을 수 없습니다")
        @Pattern(regexp = NO_INVISIBLE_CHARACTERS, message = "가게 전화번호에 줄바꿈이나 보이지 않는 글자를 넣을 수 없습니다")
        String storePhone,

        @ArraySchema(arraySchema = @Schema(description = "영업시간 (1 ~ 21줄). 같은 요일 여러 줄 가능, 줄이 없는 요일은 휴무"),
                minItems = 1, maxItems = BusinessHourRequest.MAX_LINES)
        @NotNull(message = "영업시간을 입력해 주세요")
        @Size(min = 1, max = BusinessHourRequest.MAX_LINES, message = "영업시간은 {min} ~ {max}줄이어야 합니다")
        @NonOverlappingBusinessHours
        List<@Valid @NotNull(message = "빈 영업시간 줄이 있습니다") BusinessHourRequest> businessHours,

        @ArraySchema(arraySchema = @Schema(description = "메뉴 (1 ~ 50개). 처음에는 모두 판매 중이다"),
                minItems = 1, maxItems = MenuRequest.MAX_COUNT)
        @NotNull(message = "메뉴를 입력해 주세요")
        @Size(min = 1, max = MenuRequest.MAX_COUNT, message = "메뉴는 {min} ~ {max}개여야 합니다")
        List<@Valid @NotNull(message = "빈 메뉴가 있습니다") SignupMenuRequest> menus,

        @Schema(description = "가게 대표 사진의 파일 ID (선택). `POST /api/files` 에 kind=STORE_IMAGE 로 올리고 받은 값",
                example = "3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f", nullable = true)
        @Pattern(regexp = FileIdFormat.PATTERN, message = FileIdFormat.MESSAGE)
        String storeImageFileId,

        @Schema(description = "사업자등록증의 파일 ID (필수). `POST /api/files` 에 kind=BIZ_CERT 로 올리고 받은 값",
                example = "7b1d0e4f-2c3a-4d5b-8e6f-9a0b1c2d3e4f")
        @NotBlank(message = "사업자등록증을 올려 주세요")
        @Pattern(regexp = FileIdFormat.PATTERN, message = FileIdFormat.MESSAGE)
        String bizCertFileId
) {

    // 로그에 요청 객체가 찍혀도 비밀번호·대표자 이름·휴대폰 번호가 새지 않고 이메일은 일부만 보이게 한다.
    // record 기본 toString 은 모든 필드를 그대로 출력한다. 가게 정보(주소·가게 전화·영업시간·메뉴)는 로그로
    // 찾을 일이 없고 목록이 길어 아예 찍지 않는다.
    @Override
    public String toString() {
        return "OwnerSignupRequest[email=" + LogMasking.maskEmail(email) + ", password=****, bizNo=" + bizNo
                + ", storeName=" + storeName + ", representativeName=****, phone=****]";
    }
}
