package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.store.dto.StoreImportItem;
import com.ktc4.backend.domain.store.dto.StoreImportRequest;
import com.ktc4.backend.domain.store.dto.StoreImportResponse;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 가게 자료 주입 로직 확인. DB 는 가짜(Mockito)로 바꿔, 정규화·중복 판별·거절 판단만 본다.
 *
 * <p>가게 이름·주소·번호는 모두 꾸며낸 값이다 — 실제 명단에는 제3자 상호와 사업자등록번호가 들어 있어
 * 테스트에 쓰지 않는다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StoreImportService")
class StoreImportServiceTest {

    @Mock
    private StoreRepository storeRepository;

    @InjectMocks
    private StoreImportService storeImportService;

    @Captor
    private ArgumentCaptor<List<Store>> savedCaptor;

    private static StoreImportItem item(String name, String addressRoad) {
        return new StoreImportItem(name, addressRoad, null, null, null, null, null, null, null);
    }

    private static Store existing(String nameNormalized, String addressNormalized) {
        return Store.builder()
                .name("이미 있는 가게")
                .nameNormalized(nameNormalized)
                .addressRoad("가상특별시 예시구 샘플로 1")
                .addressNormalized(addressNormalized)
                .build();
    }

    private StoreImportResponse importOf(StoreImportItem... items) {
        return storeImportService.importStores(new StoreImportRequest(List.of(items)));
    }

    @Nested
    @DisplayName("새 가게 넣기")
    class Inserting {

        @Test
        @DisplayName("원본은 그대로 두고 비교용 정규화 값을 만들어 저장한다")
        void 원본은_그대로_두고_정규화_값을_만들어_저장한다() {
            when(storeRepository.findByNameNormalizedIn(any())).thenReturn(List.of());

            StoreImportResponse response = importOf(new StoreImportItem(
                    "㈜예시분식 (샘플점)", "가상특별시 예시구 샘플로 80-1",
                    35.8884, 128.6107, StoreStatus.OPEN, "분식",
                    "053-810-5344", "010-1234-5678", "123-45-67890"));

            assertThat(response.inserted()).isEqualTo(1);
            verify(storeRepository).saveAll(savedCaptor.capture());
            Store saved = savedCaptor.getValue().getFirst();

            assertThat(saved.getName()).isEqualTo("㈜예시분식 (샘플점)");
            assertThat(saved.getNameNormalized()).isEqualTo("예시분식샘플점");
            assertThat(saved.getAddressRoad()).isEqualTo("가상특별시 예시구 샘플로 80-1");
            assertThat(saved.getAddressNormalized()).isEqualTo("가상특별시예시구샘플로80-1");
            assertThat(saved.getStatus()).isEqualTo(StoreStatus.OPEN);
            assertThat(saved.getCategory()).isEqualTo("분식");
            // 가게 전화번호는 담당자가 보고 거는 번호라 명단 표기를 그대로 둔다.
            assertThat(saved.getPhone()).isEqualTo("053-810-5344");
            // 점주 개인 번호는 가입 신청서의 번호와 맞춰 보는 값이라 숫자만 남긴다.
            assertThat(saved.getOwnerPhone()).isEqualTo("01012345678");
            assertThat(saved.getBizNo()).isEqualTo("1234567890");
            // 명단에서 온 가게는 담당자가 확인한 적이 없다 — 비어 있어야 재조사 대상에 들어온다.
            assertThat(saved.getLastCheckedAt()).isNull();
        }

        @Test
        @DisplayName("영업 상태를 안 보내면 UNKNOWN 으로 들어간다")
        void 영업_상태를_안_보내면_UNKNOWN_이다() {
            when(storeRepository.findByNameNormalizedIn(any())).thenReturn(List.of());

            importOf(item("예시분식", "가상특별시 예시구 샘플로 1"));

            verify(storeRepository).saveAll(savedCaptor.capture());
            assertThat(savedCaptor.getValue().getFirst().getStatus()).isEqualTo(StoreStatus.UNKNOWN);
        }

        @Test
        @DisplayName("사업자등록번호가 10자리가 아니어도 숫자만 남겨 저장한다")
        void 사업자번호가_10자리가_아니어도_저장한다() {
            when(storeRepository.findByNameNormalizedIn(any())).thenReturn(List.of());

            importOf(new StoreImportItem("예시분식", "가상특별시 예시구 샘플로 1",
                    null, null, null, null, null, null, "123-45-678"));

            verify(storeRepository).saveAll(savedCaptor.capture());
            assertThat(savedCaptor.getValue().getFirst().getBizNo()).isEqualTo("12345678");
        }

        @Test
        @DisplayName("빈 칸은 null 로 저장한다 — 빈 문자열이 남으면 번호가 있는 것처럼 보인다")
        void 빈_칸은_null_로_저장한다() {
            when(storeRepository.findByNameNormalizedIn(any())).thenReturn(List.of());

            importOf(new StoreImportItem("예시분식", "가상특별시 예시구 샘플로 1",
                    null, null, null, "  ", "", "", "---"));

            verify(storeRepository).saveAll(savedCaptor.capture());
            Store saved = savedCaptor.getValue().getFirst();
            assertThat(saved.getCategory()).isNull();
            assertThat(saved.getPhone()).isNull();
            assertThat(saved.getOwnerPhone()).isNull();
            assertThat(saved.getBizNo()).isNull();
        }
    }

    @Nested
    @DisplayName("같은 가게 건너뛰기")
    class Skipping {

        @Test
        @DisplayName("이미 있는 가게는 건너뛰고 덮어쓰지 않는다")
        void 이미_있는_가게는_건너뛴다() {
            when(storeRepository.findByNameNormalizedIn(any()))
                    .thenReturn(List.of(existing("예시분식", "가상특별시예시구샘플로1")));

            StoreImportResponse response = importOf(item("(주) 예시분식", "가상특별시 예시구 샘플로 1"));

            assertThat(response.inserted()).isZero();
            assertThat(response.skipped()).isEqualTo(1);
            verify(storeRepository).saveAll(savedCaptor.capture());
            assertThat(savedCaptor.getValue()).isEmpty();
        }

        @Test
        @DisplayName("한 요청에 같은 가게가 두 번 나오면 한 번만 들어간다")
        void 요청_안의_중복은_한_번만_들어간다() {
            when(storeRepository.findByNameNormalizedIn(any())).thenReturn(List.of());

            StoreImportResponse response = importOf(
                    item("예시분식", "가상특별시 예시구 샘플로 1"),
                    item("㈜예시분식", "가상특별시  예시구 샘플로 1"));

            assertThat(response.inserted()).isEqualTo(1);
            assertThat(response.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("이름이 같고 주소가 다르면 둘 다 들어간다 — 같은 체인의 다른 지점이다")
        void 이름이_같고_주소가_다르면_둘_다_들어간다() {
            when(storeRepository.findByNameNormalizedIn(any()))
                    .thenReturn(List.of(existing("예시분식", "가상특별시예시구샘플로1")));

            StoreImportResponse response = importOf(
                    item("예시분식", "가상특별시 예시구 샘플로 2"),
                    item("예시분식", "가상특별시 예시구 샘플로 3"));

            assertThat(response.inserted()).isEqualTo(2);
            assertThat(response.skipped()).isZero();
        }
    }

    @Nested
    @DisplayName("넣지 못하는 줄")
    class Rejecting {

        @Test
        @DisplayName("정규화하면 남는 글자가 없는 이름은 몇 번째 줄인지와 함께 돌려준다")
        void 정규화하면_빈_이름은_거절한다() {
            when(storeRepository.findByNameNormalizedIn(any())).thenReturn(List.of());

            StoreImportResponse response = importOf(
                    item("예시분식", "가상특별시 예시구 샘플로 1"),
                    item("!!!", "가상특별시 예시구 샘플로 2"));

            assertThat(response.requested()).isEqualTo(2);
            assertThat(response.inserted()).isEqualTo(1);
            assertThat(response.rejected()).isEqualTo(1);
            assertThat(response.rejections()).singleElement().satisfies(rejection -> {
                assertThat(rejection.index()).isEqualTo(1);
                assertThat(rejection.name()).isEqualTo("!!!");
                assertThat(rejection.reason()).isEqualTo(StoreImportService.REASON_NAME_NOT_COMPARABLE);
            });
        }

        @Test
        @DisplayName("정규화하면 남는 글자가 없는 주소도 거절한다")
        void 정규화하면_빈_주소는_거절한다() {
            StoreImportResponse response = importOf(item("예시분식", " ​ "));

            assertThat(response.inserted()).isZero();
            assertThat(response.rejections()).singleElement().satisfies(rejection ->
                    assertThat(rejection.reason()).isEqualTo(StoreImportService.REASON_ADDRESS_NOT_COMPARABLE));
        }

        @Test
        @DisplayName("한 줄이 잘못돼도 나머지는 들어간다")
        void 한_줄이_잘못돼도_나머지는_들어간다() {
            when(storeRepository.findByNameNormalizedIn(any())).thenReturn(List.of());

            StoreImportResponse response = importOf(
                    item("예시분식", "가상특별시 예시구 샘플로 1"),
                    item("@@@", "가상특별시 예시구 샘플로 2"),
                    item("샘플카페", "가상특별시 예시구 샘플로 3"));

            assertThat(response.inserted()).isEqualTo(2);
            assertThat(response.rejected()).isEqualTo(1);
            assertThat(response.rejected()).isEqualTo(response.rejections().size());
        }
    }
}
