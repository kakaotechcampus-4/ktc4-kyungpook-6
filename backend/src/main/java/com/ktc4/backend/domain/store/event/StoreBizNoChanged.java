package com.ktc4.backend.domain.store.event;

/**
 * 가게의 사업자등록번호가 실제로 바뀌었다.
 *
 * <p>번호가 바뀌면 저장해 둔 국세청 확인 결과는 <b>다른 사업자의 상태</b>라 쓸 수 없다. 그래서 수정
 * 경로가 그 기록을 되돌리는데, 다시 채우는 것은 새벽 배치뿐이라 그때까지 화면이 "확인 대기" 로 남는다.
 * 오타를 고치러 온 담당자가 고친 결과를 하루 뒤에야 본다.
 *
 * <p>이 이벤트를 받아 그 한 건만 즉시 다시 조회한다. 커밋된 뒤에 받아야 하므로
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 로 듣는다 — 커밋 전에 조회하면 아직 저장되지
 * 않은 번호를 읽거나, 조회가 느릴 때 수정 트랜잭션을 그만큼 붙잡는다.
 *
 * @param storeId 가게 id
 * @param bizNo   바뀐 뒤의 사업자등록번호 (하이픈 없는 숫자 10자리)
 */
public record StoreBizNoChanged(Long storeId, String bizNo) {
}
