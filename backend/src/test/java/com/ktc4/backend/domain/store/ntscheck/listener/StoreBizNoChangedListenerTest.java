package com.ktc4.backend.domain.store.ntscheck.listener;

import com.ktc4.backend.domain.business.dto.BusinessStatus;
import com.ktc4.backend.domain.business.dto.NtsBusinessStatus;
import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.business.service.BusinessLookupService;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.event.StoreBizNoChanged;
import com.ktc4.backend.domain.store.ntscheck.service.StoreNtsCheckService;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 번호를 고친 뒤 그 한 건만 다시 묻는 부분을 본다 — 무엇을 조회하고, 실패를 어디까지 삼키는지.
 *
 * <p>가게 정보는 모두 가짜 값이다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StoreBizNoChangedListener.recheck")
class StoreBizNoChangedListenerTest {

    private static final String BIZ_NO = "2222222222";

    @Mock
    private BusinessLookupService businessLookupService;

    @Mock
    private StoreNtsCheckService storeNtsCheckService;

    @Mock
    private StoreRepository storeRepository;

    @InjectMocks
    private StoreBizNoChangedListener listener;

    @Captor
    private ArgumentCaptor<Map<String, BusinessStatus>> statusesCaptor;

    private static Store store(long storeId, String bizNo) {
        Store store = Store.builder()
                .name("예시분식").nameNormalized("예시분식")
                .addressRoad("가상특별시 예시구 샘플로 123").addressNormalized("가상특별시예시구샘플로123")
                .status(StoreStatus.OPEN).bizNo(bizNo)
                .build();
        ReflectionTestUtils.setField(store, "storeId", storeId);
        return store;
    }

    // 국세청 원본 응답 한 건. 우리가 쓰는 세 필드만 채운다.
    private static NtsBusinessStatus raw(String bNo, String bSttCd) {
        return new NtsBusinessStatus(bNo, "", bSttCd, "", "", "", "", "", "", "", "");
    }

    @Test
    @DisplayName("고친 번호 하나만 조회해 그 가게에 기록한다")
    void looksUpOnlyTheChangedBizNo() {
        when(storeRepository.findById(1L)).thenReturn(Optional.of(store(1L, BIZ_NO)));
        when(businessLookupService.getNtsStatuses(List.of(BIZ_NO)))
                .thenReturn(List.of(raw(BIZ_NO, "01")));

        listener.recheck(new StoreBizNoChanged(1L, BIZ_NO));

        verify(storeNtsCheckService).markChecked(anyList(), statusesCaptor.capture(), any(LocalDateTime.class));
        assertThat(statusesCaptor.getValue()).containsOnlyKeys(BIZ_NO);
        assertThat(statusesCaptor.getValue().get(BIZ_NO).state()).isEqualTo(BusinessState.ACTIVE);
    }

    @Test
    @DisplayName("응답에 그 번호가 없으면 빈 결과로 기록한다 — 화면에 \"확인 대기\" 로 남는다")
    void recordsEmptyWhenAnswerIsMissing() {
        when(storeRepository.findById(1L)).thenReturn(Optional.of(store(1L, BIZ_NO)));
        // 요청하지 않은 번호만 돌아온 경우 — 순서로 짝지으면 남의 답이 붙는 자리다
        when(businessLookupService.getNtsStatuses(anyList()))
                .thenReturn(List.of(raw("9999999999", "01")));

        listener.recheck(new StoreBizNoChanged(1L, BIZ_NO));

        verify(storeNtsCheckService).markChecked(anyList(), statusesCaptor.capture(), any(LocalDateTime.class));
        assertThat(statusesCaptor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("해석하지 못하는 상태 코드면 빈 결과로 기록한다")
    void recordsEmptyWhenAnswerCannotBeRead() {
        when(storeRepository.findById(1L)).thenReturn(Optional.of(store(1L, BIZ_NO)));
        when(businessLookupService.getNtsStatuses(anyList()))
                .thenReturn(List.of(raw(BIZ_NO, "알 수 없는 코드")));

        listener.recheck(new StoreBizNoChanged(1L, BIZ_NO));

        verify(storeNtsCheckService).markChecked(anyList(), statusesCaptor.capture(), any(LocalDateTime.class));
        assertThat(statusesCaptor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("국세청 조회가 실패하면 아무것도 쓰지 않는다 — 수정 경로가 비워 둔 채로 두고 배치에 맡긴다")
    void writesNothingWhenLookupFails() {
        when(storeRepository.findById(1L)).thenReturn(Optional.of(store(1L, BIZ_NO)));
        when(businessLookupService.getNtsStatuses(anyList()))
                .thenThrow(new CustomException(ErrorCode.NTS_API_ERROR));

        listener.recheck(new StoreBizNoChanged(1L, BIZ_NO));

        verify(storeNtsCheckService, never()).markChecked(anyList(), anyMap(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("저장이 실패해도 예외를 밖으로 내보내지 않는다 — 수정 요청은 이미 커밋됐다")
    void swallowsSaveFailure() {
        when(storeRepository.findById(1L)).thenReturn(Optional.of(store(1L, BIZ_NO)));
        when(businessLookupService.getNtsStatuses(anyList())).thenReturn(List.of(raw(BIZ_NO, "01")));
        org.mockito.Mockito.doThrow(new IllegalStateException("DB 실패"))
                .when(storeNtsCheckService).markChecked(anyList(), anyMap(), any(LocalDateTime.class));

        listener.recheck(new StoreBizNoChanged(1L, BIZ_NO));
    }

    @Test
    @DisplayName("고친 직후 가게가 지워졌으면 조회하지 않는다")
    void skipsWhenStoreIsGone() {
        when(storeRepository.findById(1L)).thenReturn(Optional.empty());

        listener.recheck(new StoreBizNoChanged(1L, BIZ_NO));

        verifyNoInteractions(businessLookupService, storeNtsCheckService);
    }
}
