package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.store.client.AddressGeocoder;
import com.ktc4.backend.domain.store.client.GeocodeResult;
import com.ktc4.backend.domain.store.repository.GeocodeTarget;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 좌표가 없는 가게의 좌표를 주소로 채운다 — 주변 가게(아동 앱 지도)는 좌표가 있는 가게만 보여 준다.
 *
 * <p>가게는 명단으로 들어오고 명단에 좌표가 없을 수 있다. 관리자가 주소를 바꾸며 좌표를 함께 주지 않으면 옛 좌표를 비운다
 * ({@link StoreService#updateStore}). 둘 다 여기서 채운다 — 주입·수정 요청 안에서 바깥(카카오)을 부르지 않아, 카카오가
 * 느리거나 죽어도 저장은 멀쩡하다.
 *
 * <p>카카오에 묻는 동안 DB 트랜잭션을 쥐지 않는다. 대상은 ID·주소만 읽어 두고, 답을 받은 뒤 한 곳씩 짧게 잠가 쓴다. 그 사이
 * 주소가 바뀌었거나 좌표가 이미 생겼으면 받은 답을 버린다.
 *
 * <ul>
 *   <li>찾음 → 좌표를 넣는다</li>
 *   <li>못 찾음(주소 탓) → 시각을 남겨 며칠 동안 다시 묻지 않는다</li>
 *   <li>묻지 못함(키·장애) → 남기지 않고 이번 회차를 멈춘다 — 남은 가게도 같은 이유로 실패할 것이라 다음 주기에 다시 묻는다</li>
 *   <li>예상 못 한 예외 → 그 가게만 건너뛰고 다음 가게로 간다 — 회차 밖으로 튀면 다음 주기에도 같은 가게에서 막혀
 *       그 뒤 가게가 영영 채워지지 않는다</li>
 * </ul>
 */
@Slf4j
@Service
public class StoreGeocodeService {

    private final StoreRepository storeRepository;
    private final AddressGeocoder geocoder;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final int retryAfterDays;

    public StoreGeocodeService(StoreRepository storeRepository, AddressGeocoder geocoder,
                               PlatformTransactionManager transactionManager,
                               @Value("${geocode.batch-size:50}") int batchSize,
                               @Value("${geocode.retry-after-days:7}") int retryAfterDays) {
        this.storeRepository = storeRepository;
        this.geocoder = geocoder;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.batchSize = batchSize;
        this.retryAfterDays = retryAfterDays;
    }

    /**
     * 한 회차를 돈다 — 대상을 최대 {@code batch-size} 곳 읽어 차례로 묻는다.
     *
     * @param now 이번 회차의 시각. 못 찾은 기록과 다시 물을 기준에 쓴다
     * @return 이번 회차의 결과 수
     */
    public Summary fillMissingCoordinates(LocalDateTime now) {
        if (!geocoder.isEnabled()) {
            return new Summary(0, 0, 0, 0, false);
        }
        List<GeocodeTarget> targets = storeRepository.findGeocodeTargets(now.minusDays(retryAfterDays),
                Limit.of(batchSize));
        int found = 0;
        int notFound = 0;
        int skipped = 0;
        for (GeocodeTarget target : targets) {
            GeocodeResult result;
            try {
                result = geocoder.geocode(target.addressRoad());
                if (!(result instanceof GeocodeResult.Failed) && apply(target, result, now)) {
                    if (result instanceof GeocodeResult.Found) {
                        found++;
                    } else {
                        notFound++;
                    }
                }
            } catch (RuntimeException e) {
                // 예외 메시지는 남기지 않는다 — 주소 같은 값이 섞일 수 있다
                log.warn("가게 좌표 채우기 - storeId={} 건너뜀 ({})", target.storeId(), e.getClass().getSimpleName());
                skipped++;
                continue;
            }
            if (result instanceof GeocodeResult.Failed failed) {
                log.warn("가게 좌표 채우기 중단 - {} (이번 회차 {}곳 중 {}곳에서 멈춤, 다음 주기에 다시)",
                        failed.reason(), targets.size(), found + notFound + skipped);
                return new Summary(targets.size(), found, notFound, skipped, true);
            }
        }
        if (!targets.isEmpty()) {
            log.info("가게 좌표 채우기 - 대상 {}곳, 찾음 {}, 못 찾음 {}, 건너뜀 {}", targets.size(), found, notFound, skipped);
        }
        return new Summary(targets.size(), found, notFound, skipped, false);
    }

    // 한 곳만 짧게 잠가 쓴다. 묻는 사이 주소가 바뀌었거나 좌표가 이미 생겼으면 버린다
    private boolean apply(GeocodeTarget target, GeocodeResult result, LocalDateTime now) {
        Boolean applied = transactionTemplate.execute(status -> storeRepository.findByIdForUpdate(target.storeId())
                .filter(store -> target.addressRoad().equals(store.getAddressRoad()))
                .filter(store -> store.getLat() == null || store.getLng() == null)
                .map(store -> {
                    if (result instanceof GeocodeResult.Found(double lat, double lng)) {
                        store.recordGeocodedCoordinates(lat, lng);
                    } else {
                        store.recordGeocodeMiss(now);
                    }
                    return true;
                })
                .orElse(false));
        return Boolean.TRUE.equals(applied);
    }

    /**
     * 한 회차의 결과.
     *
     * @param targets  읽은 대상 수
     * @param found    좌표를 넣은 수
     * @param notFound 못 찾아 기록한 수
     * @param skipped  예상 못 한 예외로 건너뛴 수
     * @param stopped  묻지 못해 중간에 멈췄는지
     */
    public record Summary(int targets, int found, int notFound, int skipped, boolean stopped) {
    }
}
