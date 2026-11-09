package com.ktc4.backend.domain.store.dto;

import java.time.LocalDateTime;

/**
 * 가게 하나의 마지막 변경 시각. 변경 기록({@code store_change_history})에 남은 줄 중 가장 늦은 것이다.
 *
 * @param storeId       가게 id
 * @param lastChangedAt 마지막으로 값이 바뀐 시각
 */
public record StoreLastChange(Long storeId, LocalDateTime lastChangedAt) {
}
