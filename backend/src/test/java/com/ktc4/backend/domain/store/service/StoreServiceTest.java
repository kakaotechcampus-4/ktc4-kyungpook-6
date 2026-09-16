package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.business.dto.NtsBusinessStatus;
import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.business.service.BusinessLookupService;
import com.ktc4.backend.domain.store.dto.StoreCheckResponse;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// DB 와 국세청 조회를 가짜(Mockito)로 바꿔 서비스 로직만 검증한다. 가게 정보는 모두 가짜 값이다.
@ExtendWith(MockitoExtension.class)
@DisplayName("StoreService")
class StoreServiceTest {

    @Mock
    private StoreRepository storeRepository;

    @Mock
    private BusinessLookupService businessLookupService;

    @InjectMocks
    private StoreService storeService;

    @Captor
    private ArgumentCaptor<List<String>> bizNosCaptor;

    private static Store store(long storeId, StoreStatus status, String bizNo) {
        Store store = Store.builder()
                .name("예시분식")
                .nameNormalized("예시분식")
                .addressRoad("가상특별시 예시구 샘플로 123")
                .addressNormalized("가상특별시예시구샘플로123")
                .phone("000-1234-5678")
                .status(status)
                .bizNo(bizNo)
                .build();
        // storeId 는 DB 가 채우는 값이라 builder 에 없어서 직접 넣는다
        ReflectionTestUtils.setField(store, "storeId", storeId);
        return store;
    }

    private static Store storeWith(String name, String nameNormalized,
                                   String addressRoad, String addressNormalized, String bizNo) {
        return Store.builder()
                .name(name)
                .nameNormalized(nameNormalized)
                .addressRoad(addressRoad)
                .addressNormalized(addressNormalized)
                .bizNo(bizNo)
                .build();
    }

    // 국세청 원본 응답 한 건. 우리가 쓰는 세 필드만 채운다.
    private static NtsBusinessStatus raw(String bNo, String bSttCd, String endDt) {
        return new NtsBusinessStatus(bNo, "", bSttCd, "", "", endDt, "", "", "", "", "");
    }

    @Nested
    @DisplayName("checkWithNts")
    class CheckWithNts {

        @Test
        @DisplayName("사업자번호를 정규화해서 10자리인 번호만, 중복 없이 국세청 조회에 넘긴다")
        void passesOnlyValidDistinctBizNos() {
            List<Long> ids = List.of(1L, 2L, 3L, 4L, 5L);
            when(storeRepository.findAllById(ids)).thenReturn(List.of(
                    store(1L, StoreStatus.OPEN, "123-45-67890"),
                    store(2L, StoreStatus.OPEN, "1234567890"),
                    store(3L, StoreStatus.OPEN, null),
                    store(4L, StoreStatus.OPEN, "12345"),
                    store(5L, StoreStatus.OPEN, "사업자번호없음")));
            when(businessLookupService.getNtsStatuses(anyList())).thenReturn(List.of());

            storeService.checkWithNts(ids);

            verify(businessLookupService).getNtsStatuses(bizNosCaptor.capture());
            assertThat(bizNosCaptor.getValue()).containsExactly("1234567890");
        }

        @Test
        @DisplayName("국세청 결과가 있으면 상태와 폐업일을 채우고, 원본 사업자번호는 그대로 둔다")
        void fillsNtsStatusWhenFound() {
            List<Long> ids = List.of(1L);
            when(storeRepository.findAllById(ids)).thenReturn(List.of(store(1L, StoreStatus.OPEN, "123-45-67890")));
            when(businessLookupService.getNtsStatuses(anyList()))
                    .thenReturn(List.of(raw("1234567890", "03", "20260301")));

            StoreCheckResponse response = storeService.checkWithNts(ids).get(0);

            assertThat(response.internalStatus()).isEqualTo(StoreStatus.OPEN);
            assertThat(response.ntsStatus()).isEqualTo(BusinessState.CLOSED);
            assertThat(response.ntsClosedAt()).isEqualTo(LocalDate.of(2026, 3, 1));
            assertThat(response.bizNo()).isEqualTo("123-45-67890");
        }

        @Test
        @DisplayName("번호가 없거나 형식이 틀리면 국세청 칸은 null 이다")
        void leavesNtsStatusNullWhenBizNoUnusable() {
            List<Long> ids = List.of(1L, 2L);
            when(storeRepository.findAllById(ids)).thenReturn(List.of(
                    store(1L, StoreStatus.OPEN, null),
                    store(2L, StoreStatus.OPEN, "12345")));

            List<StoreCheckResponse> responses = storeService.checkWithNts(ids);

            assertThat(responses).hasSize(2)
                    .allSatisfy(response -> assertThat(response.ntsStatus()).isNull());
        }

        @Test
        @DisplayName("응답에 그 번호가 없으면 국세청 칸은 null 이다")
        void leavesNtsStatusNullWhenMissingInResponse() {
            List<Long> ids = List.of(1L);
            when(storeRepository.findAllById(ids)).thenReturn(List.of(store(1L, StoreStatus.OPEN, "1234567890")));
            when(businessLookupService.getNtsStatuses(anyList())).thenReturn(List.of());

            assertThat(storeService.checkWithNts(ids).get(0).ntsStatus()).isNull();
        }

        @Test
        @DisplayName("응답의 b_no 가 요청한 번호와 다르면 엉뚱한 가게에 붙이지 않는다")
        void ignoresResultWithDifferentBizNo() {
            List<Long> ids = List.of(1L);
            when(storeRepository.findAllById(ids)).thenReturn(List.of(store(1L, StoreStatus.OPEN, "1234567890")));
            when(businessLookupService.getNtsStatuses(anyList()))
                    .thenReturn(List.of(raw("9999999999", "03", "20260301")));

            assertThat(storeService.checkWithNts(ids).get(0).ntsStatus()).isNull();
        }

        @Test
        @DisplayName("해석할 수 없는 응답(모르는 코드)은 그 가게만 null 이 된다")
        void skipsUninterpretableResult() {
            List<Long> ids = List.of(1L, 2L);
            when(storeRepository.findAllById(ids)).thenReturn(List.of(
                    store(1L, StoreStatus.OPEN, "1111111111"),
                    store(2L, StoreStatus.OPEN, "2222222222")));
            when(businessLookupService.getNtsStatuses(anyList())).thenReturn(List.of(
                    raw("1111111111", "99", ""),
                    raw("2222222222", "01", "")));

            List<StoreCheckResponse> responses = storeService.checkWithNts(ids);

            assertThat(responses.get(0).ntsStatus()).isNull();
            assertThat(responses.get(1).ntsStatus()).isEqualTo(BusinessState.ACTIVE);
        }

        @Test
        @DisplayName("100건씩 나눠 조회한다")
        void splitsIntoBatchesOf100() {
            List<Long> ids = IntStream.rangeClosed(1, 250).mapToObj(Long::valueOf).toList();
            when(storeRepository.findAllById(ids)).thenReturn(IntStream.rangeClosed(1, 250)
                    .mapToObj(i -> store(i, StoreStatus.OPEN, String.format("%010d", i)))
                    .toList());
            when(businessLookupService.getNtsStatuses(anyList())).thenAnswer(invocation -> {
                List<String> chunk = invocation.getArgument(0);
                return chunk.stream().map(bizNo -> raw(bizNo, "01", "")).toList();
            });

            List<StoreCheckResponse> responses = storeService.checkWithNts(ids);

            verify(businessLookupService, times(3)).getNtsStatuses(bizNosCaptor.capture());
            assertThat(bizNosCaptor.getAllValues()).extracting(List::size).containsExactly(100, 100, 50);
            assertThat(responses).hasSize(250)
                    .allSatisfy(response -> assertThat(response.ntsStatus()).isEqualTo(BusinessState.ACTIVE));
        }

        @Test
        @DisplayName("한 묶음이 실패해도 나머지 묶음 결과는 채운다")
        void keepsOtherBatchesWhenOneFails() {
            List<Long> ids = IntStream.rangeClosed(1, 150).mapToObj(Long::valueOf).toList();
            when(storeRepository.findAllById(ids)).thenReturn(IntStream.rangeClosed(1, 150)
                    .mapToObj(i -> store(i, StoreStatus.OPEN, String.format("%010d", i)))
                    .toList());
            when(businessLookupService.getNtsStatuses(anyList())).thenAnswer(invocation -> {
                List<String> chunk = invocation.getArgument(0);
                if (chunk.size() == 50) {
                    throw new CustomException(ErrorCode.NTS_API_ERROR);
                }
                return chunk.stream().map(bizNo -> raw(bizNo, "01", "")).toList();
            });

            List<StoreCheckResponse> responses = storeService.checkWithNts(ids);

            assertThat(responses).hasSize(150);
            assertThat(responses.subList(0, 100))
                    .allSatisfy(response -> assertThat(response.ntsStatus()).isEqualTo(BusinessState.ACTIVE));
            assertThat(responses.subList(100, 150))
                    .allSatisfy(response -> assertThat(response.ntsStatus()).isNull());
        }

        @Test
        @DisplayName("가게 정보(원본·정규화 이름과 주소, 전화번호, 우리 상태)를 그대로 담는다")
        void copiesStoreFields() {
            List<Long> ids = List.of(1L);
            when(storeRepository.findAllById(ids)).thenReturn(List.of(store(1L, StoreStatus.UNKNOWN, "1234567890")));
            when(businessLookupService.getNtsStatuses(anyList()))
                    .thenReturn(List.of(raw("1234567890", "01", "")));

            StoreCheckResponse response = storeService.checkWithNts(ids).get(0);

            assertThat(response.storeId()).isEqualTo(1L);
            assertThat(response.name()).isEqualTo("예시분식");
            assertThat(response.nameNormalized()).isEqualTo("예시분식");
            assertThat(response.addressRoad()).isEqualTo("가상특별시 예시구 샘플로 123");
            assertThat(response.addressNormalized()).isEqualTo("가상특별시예시구샘플로123");
            assertThat(response.phone()).isEqualTo("000-1234-5678");
            assertThat(response.internalStatus()).isEqualTo(StoreStatus.UNKNOWN);
            assertThat(response.ntsStatus()).isEqualTo(BusinessState.ACTIVE);
        }

        @Test
        @DisplayName("DB 가 순서를 섞어 돌려줘도 결과는 storeId 오름차순이다")
        void sortsByStoreId() {
            List<Long> ids = List.of(3L, 1L, 2L);
            when(storeRepository.findAllById(ids)).thenReturn(List.of(
                    store(3L, StoreStatus.OPEN, null),
                    store(1L, StoreStatus.OPEN, null),
                    store(2L, StoreStatus.OPEN, null)));

            assertThat(storeService.checkWithNts(ids))
                    .extracting(StoreCheckResponse::storeId)
                    .containsExactly(1L, 2L, 3L);
        }

        @Test
        @DisplayName("조회할 번호가 하나도 없으면 국세청을 부르지 않는다")
        void doesNotCallWhenNoValidBizNo() {
            List<Long> ids = List.of(1L);
            when(storeRepository.findAllById(ids)).thenReturn(List.of(store(1L, StoreStatus.OPEN, null)));

            storeService.checkWithNts(ids);

            verify(businessLookupService, times(0)).getNtsStatuses(anyList());
        }
    }

    @Nested
    @DisplayName("renormalizeAll")
    class RenormalizeAll {

        @Test
        @DisplayName("현재 규칙으로 이름·주소·사업자번호 정규화 값을 다시 계산한다")
        void recalculatesNormalizedValues() {
            Store store = storeWith("(주)예시분식", "옛날값",
                    "가상특별시 예시구 샘플로 123", "옛날값", "123-45-67890");
            when(storeRepository.findAll()).thenReturn(List.of(store));

            storeService.renormalizeAll();

            assertThat(store.getNameNormalized()).isEqualTo("예시분식");
            assertThat(store.getAddressNormalized()).isEqualTo("가상특별시예시구샘플로123");
            assertThat(store.getBizNo()).isEqualTo("1234567890");
        }

        @Test
        @DisplayName("사업자번호가 없으면 빈 값으로 두지 않고 그대로 null 을 유지한다")
        void keepsNullBizNo() {
            Store store = storeWith("예시분식", "예시분식", "샘플로 1", "샘플로1", null);
            when(storeRepository.findAll()).thenReturn(List.of(store));

            storeService.renormalizeAll();

            assertThat(store.getBizNo()).isNull();
        }

        @Test
        @DisplayName("값이 실제로 바뀐 가게 수만 센다")
        void countsOnlyChangedStores() {
            Store alreadyNormalized = storeWith("예시분식", "예시분식", "샘플로 1", "샘플로1", "1234567890");
            Store outdatedBizNo = storeWith("샘플카페", "샘플카페", "샘플로 2", "샘플로2", "123-45-67890");
            when(storeRepository.findAll()).thenReturn(List.of(alreadyNormalized, outdatedBizNo));

            assertThat(storeService.renormalizeAll()).isEqualTo(1);
        }

        @Test
        @DisplayName("가게가 없으면 0 을 돌려준다")
        void returnsZeroWhenEmpty() {
            when(storeRepository.findAll()).thenReturn(List.of());

            assertThat(storeService.renormalizeAll()).isZero();
        }
    }
}
