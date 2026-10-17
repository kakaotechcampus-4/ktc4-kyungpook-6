package com.ktc4.backend.domain.store.dto;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.store.enums.NtsLookupResult;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 가게와 함께 넣는 국세청 확인 기록.
 *
 * <p>이 칸이 없으면 가게만 들어가고, 그 가게는 {@code GET /api/stores/nts-checks} 에서
 * {@code ntsLookup=UNCONFIRMED} 로 나온다 &mdash; 배치가 아직 확인하지 않은 가게와 같은 모양이다.
 * 배포 환경에서 조사 화면을 돌려 보려면 이 칸이 있어야 한다.
 *
 * <p><b>배치가 덮어쓴다.</b> {@code NtsCheckScheduler} 가 매일 새벽 3시에 국세청으로 다시 확인하므로,
 * 여기 넣은 값은 그때까지만 유지된다. 꾸며낸 사업자등록번호로 넣었다면 다음 배치에서
 * {@code NOT_REGISTERED} 로 바뀔 수 있다.
 *
 * @param lookup    조회가 어떻게 끝났는지. {@code CONFIRMED} 면 {@code status} 가 있어야 한다
 * @param status    확인된 국세청 상태. {@code CONFIRMED} 일 때만 넣는다
 * @param closedAt  국세청 기준 폐업일. {@code status=CLOSED} 일 때만 넣는다
 * @param checkedAt 확인 시각. 없으면 서버의 현재 시각
 */
public record NtsCheckSeed(
        @Schema(description = "조회 결과 (CONFIRMED: 확인됨, UNCONFIRMED: 조회했지만 확인 못 함, "
                + "NO_BIZ_NO: 번호가 없거나 틀려 조회 안 함)", example = "CONFIRMED")
        @NotNull(message = "ntsCheck.lookup 은 비울 수 없습니다")
        NtsLookupResult lookup,

        @Schema(description = "국세청 상태 (ACTIVE: 계속사업자, SUSPENDED: 휴업, CLOSED: 폐업, "
                + "NOT_REGISTERED: 미등록). lookup 이 CONFIRMED 일 때만", example = "ACTIVE")
        BusinessState status,

        @Schema(description = "국세청 기준 폐업일. status 가 CLOSED 일 때만", example = "2025-05-08")
        LocalDate closedAt,

        @Schema(description = "확인 시각. 없으면 서버 현재 시각", example = "2026-10-13T03:00:00")
        LocalDateTime checkedAt
) {
}
