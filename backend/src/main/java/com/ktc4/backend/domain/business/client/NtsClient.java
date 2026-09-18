package com.ktc4.backend.domain.business.client;

import com.ktc4.backend.domain.business.dto.NtsBusinessStatus;
import java.util.List;

public interface NtsClient {

    /**
     * 국세청 API 가 1회 호출당 받는 최대 건수.
     *
     * <p>실제 호출로 확인함 — 100건은 정상 처리, 101건은 HTTP 413 Payload Too Large 로 거절됨.
     * 분할 책임이 호출하는 쪽에 있으므로, 나눠 보내야 하는 쪽이 같은 숫자를 각자 적어 두지 않도록
     * 계약과 함께 여기에 둔다.
     */
    int MAX_BATCH_SIZE = 100;

    /**
     * 국세청 사업자등록 상태조회 API를 호출하여 사업자등록번호 목록의 상태를 조회한다.
     *
     * <p>입력된 사업자등록번호에 포함된 하이픈 등 숫자 이외의 문자는 호출 전에 자동으로 제거되어
     * 국세청 API에 전달된다. 정상적인 경우 반환되는 목록은 입력 목록과 건수가 1:1로 대응하며,
     * 매칭되지 않은 사업자등록번호도 목록에서 빠지지 않고 상태 관련 필드가 빈 문자열로 채워진 채
     * 포함된다. 다만 국세청 응답 자체가 요청 건수보다 적게 올 수도 있어(서버 로그에 경고로 남는다),
     * 호출하는 쪽은 결과를 리스트 위치(index)가 아니라 각 항목의 {@code bNo} 값으로 찾아 대응시켜야
     * 한다 — 위치로 대응시키면 이 드문 경우에 엉뚱한 사업자등록번호와 매칭될 수 있다.
     *
     * <p>국세청 API는 1회 호출당 최대 100건까지만 받는다. 분할 책임은 이 메서드가 아니라 호출하는
     * 쪽에 있으며, 100건을 넘기면 API를 호출하지 않고 즉시 예외를 던진다.
     *
     * @param bizNos 조회할 사업자등록번호 목록 (하이픈 포함 여부 무관, 최대 100건). {@code null}이거나
     *               빈 리스트면 API를 호출하지 않고 빈 리스트를 반환한다. 리스트 안에 {@code null}
     *               원소가 섞여 있거나 100건을 넘으면
     *               {@link com.ktc4.backend.global.error.ErrorCode#INVALID_REQUEST}로 예외를 던진다.
     * @return 정상적인 경우 입력 목록과 1:1로 대응하는 사업자등록 상태 목록 (드물게 국세청 응답이
     *         짧게 오면 그보다 적을 수 있음 — 위 설명 참고)
     */
    List<NtsBusinessStatus> getStatuses(List<String> bizNos);
}
