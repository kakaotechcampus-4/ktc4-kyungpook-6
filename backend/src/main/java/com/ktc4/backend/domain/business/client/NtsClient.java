package com.ktc4.backend.domain.business.client;

import com.ktc4.backend.domain.business.dto.NtsBusinessStatus;
import java.util.List;

public interface NtsClient {

    /**
     * 국세청 사업자등록 상태조회 API를 호출하여 사업자등록번호 목록의 상태를 조회한다.
     *
     * <p>입력된 사업자등록번호에 포함된 하이픈 등 숫자 이외의 문자는 호출 전에 자동으로 제거되어
     * 국세청 API에 전달된다. 반환되는 목록은 입력 목록과 순서·건수가 1:1로 대응하며, 매칭되지
     * 않은 사업자등록번호도 목록에서 빠지지 않고 상태 관련 필드가 빈 문자열로 채워진 채 포함된다.
     *
     * <p>국세청 API는 1회 호출당 최대 100건까지만 받지만, 이 메서드는 그 이상을 넘겨도 내부적으로
     * 100건씩 나눠 여러 번 호출하고 순서대로 이어붙여서 반환한다 — 호출하는 쪽은 건수 제한을
     * 신경 쓸 필요 없다.
     *
     * @param bizNos 조회할 사업자등록번호 목록 (하이픈 포함 여부 무관, 건수 제한 없음 — 100건 초과 시
     *               자동으로 분할 호출됨). {@code null}이거나 빈 리스트면 API를 호출하지 않고 빈
     *               리스트를 반환한다. 리스트 안에 {@code null} 원소가 섞여 있으면
     *               {@link com.ktc4.backend.global.error.ErrorCode#INVALID_REQUEST}로 예외를 던진다.
     * @return 입력 목록과 1:1로 대응하는 사업자등록 상태 목록
     */
    List<NtsBusinessStatus> getStatuses(List<String> bizNos);
}
