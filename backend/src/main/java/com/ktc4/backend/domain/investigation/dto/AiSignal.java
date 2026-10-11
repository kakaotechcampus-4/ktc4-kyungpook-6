package com.ktc4.backend.domain.investigation.dto;

import com.ktc4.backend.domain.signal.enums.ChangeField;
import com.ktc4.backend.domain.signal.enums.SignalType;

/**
 * AI 가 잡은 변화 하나와 그 대표 근거. 저장하면 {@code Signal} 한 행(출처 {@code AI_WEB})이 된다.
 *
 * @param signalType   신호 등급 — AI 는 변화면 {@code SIGNAL_HIGH} 만 쓴다
 * @param field        어느 항목의 변화인가
 * @param observed     웹에서 본 새 값 (없을 수 있음)
 * @param evidenceText 근거 설명
 * @param evidenceUrl  근거 링크 (없을 수 있음)
 */
public record AiSignal(
        SignalType signalType,
        ChangeField field,
        String observed,
        String evidenceText,
        String evidenceUrl
) {
}
