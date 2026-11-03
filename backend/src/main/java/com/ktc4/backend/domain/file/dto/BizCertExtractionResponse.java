package com.ktc4.backend.domain.file.dto;

import com.ktc4.backend.domain.file.enums.NtsVerification;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 사업자등록증에서 읽은 값 — 점주 앱이 가입 form 을 채우는 데 쓴다. 못 읽은 칸은 {@code null} 이다.
 *
 * <p>칸 이름은 가입 신청({@code OwnerSignupRequest})의 같은 값과 같은 이름이다. 앱이 그대로 옮겨 담게 하려는 것이고,
 * 이미 채운 칸을 덮어쓸지는 앱이 정한다. 대표자 생년월일·법인등록번호처럼 가입에 쓰지 않는 항목은 칸을 두지 않는다.
 */
public record BizCertExtractionResponse(
        @Schema(description = "사업자등록번호 — 숫자 10자리, 하이픈 없이", example = "1234567890") String bizNo,
        @Schema(description = "상호 — 개인사업자 등록증에 적힌 값. 법인 등록증이면 비어 있을 수 있다", example = "예시분식")
        String storeName,
        @Schema(description = "대표자 이름 — 개인은 성명, 법인은 대표자", example = "홍길동") String representativeName,
        @Schema(description = "사업장 소재지(법인의 본점 소재지 아님). 실제 가게 주소와 다를 수 있다",
                example = "대구광역시 북구 대학로 80")
        String storeAddress,
        @Schema(description = "개업연월일", example = "2019-03-02") LocalDate openDate,
        @Schema(description = "법인명 — 법인 등록증에만 있다. 개인사업자면 비어 있다", example = "주식회사 예시")
        String corpName,
        @Schema(description = "업태 — 여러 개면 쉼표로 잇는다", example = "음식점업") String businessType,
        @Schema(description = "종목 — 여러 개면 쉼표로 잇는다", example = "한식, 분식") String businessItem,
        @Schema(description = """
                국세청 진위확인 결과 — 비어 있지 않다.
                VERIFIED: 사업자번호·개업일·대표자가 국세청 기록과 맞음 /
                MISMATCH: 국세청이 맞지 않다고 답함(잘못 읽었거나 다른 등록증) /
                UNVERIFIED: 확인 못 함(세 값 중 못 읽은 것이 있거나 국세청을 부르지 못함)""",
                example = "VERIFIED", requiredMode = Schema.RequiredMode.REQUIRED)
        NtsVerification ntsVerification
) {
    public BizCertExtractionResponse {
        // 확인 못 했으면 UNVERIFIED 로 적는다. 비워 두면 앱이 null 분기를 하나 더 써야 한다
        Objects.requireNonNull(ntsVerification, "ntsVerification");
    }
}
