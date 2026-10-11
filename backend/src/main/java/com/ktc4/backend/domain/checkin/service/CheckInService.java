package com.ktc4.backend.domain.checkin.service;

import com.ktc4.backend.domain.checkin.dto.CheckInResponse;
import com.ktc4.backend.domain.checkin.entity.CheckIn;
import com.ktc4.backend.domain.checkin.repository.CheckInRepository;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.repository.StoreOwnerRepository;
import com.ktc4.backend.domain.qr.dto.QrResolution;
import com.ktc4.backend.domain.qr.service.QrCredentialService;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.AuthMember;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CheckInService {

    private final CheckInRepository checkInRepository;
    private final StoreService storeService;
    private final QrCredentialService qrCredentialService;
    private final StoreOwnerRepository storeOwnerRepository;

    /**
     * 점주가 스캔한 아동 QR 로 체크인을 기록한다.
     *
     * <p>가게를 QR 보다 먼저 확인한다. 둘 다 틀린 요청에 어떤 에러가 나갈지를 하나로 고정하기 위해서다.
     * QR 형식은 모르고 {@link QrCredentialService#resolveChildId} 에만 묻는다.
     *
     * <p>QR 이 맞지 않으면 {@code storeId} 와 실패 이유를 INFO 로그로 남긴다. 응답은 이유와 관계없이 같다.
     *
     * <p>점주는 자기에게 연결된 가게에만 기록할 수 있다. 이 확인을 가장 먼저 한다 — 연결되지 않은 점주에게는
     * 그 가게가 있는지도(404 와 403 의 차이), 그 QR 이 유효한지도 알려 주지 않기 위해서다.
     * 연결된 점주라면 그 가게는 반드시 있으므로 순서를 앞에 둬도 잃는 것이 없다.
     *
     * @param storeId    체크인할 가게 ID
     * @param qrPayload  점주 앱이 스캔한 QR 문자열
     * @param authMember 로그인한 사용자. 권한 검사 스위치가 꺼져 토큰 없이 들어온 요청이면 {@code null}
     * @return 체크인 기록 ID 와 시각. 아동 정보는 담지 않는다
     * @throws CustomException 점주가 연결되지 않은 가게면(없는 가게 포함) {@code FORBIDDEN},
     *                         가게가 없으면 {@code STORE_NOT_FOUND}, QR 이 맞지 않으면 {@code INVALID_QR_TOKEN}
     */
    @Transactional
    public CheckInResponse checkIn(Long storeId, String qrPayload, AuthMember authMember) {
        requireAccessTo(storeId, authMember);
        Store store = storeService.findStore(storeId);
        Long childId = switch (qrCredentialService.resolveChildId(qrPayload)) {
            case QrResolution.Resolved resolved -> resolved.childId();
            case QrResolution.Rejected rejected -> {
                // 응답은 이유와 관계없이 하나지만, 디버깅을 위해 이유는 남긴다. QR 문자열·토큰·해시는 남기지 않는다.
                log.info("QR 체크인 실패 - storeId={}, reason={}", storeId, rejected.reason());
                throw new CustomException(ErrorCode.INVALID_QR_TOKEN);
            }
        };

        CheckIn checkIn = checkInRepository.save(CheckIn.of(childId, store));
        return CheckInResponse.from(checkIn);
    }

    // 통과시킬 대상을 하나씩 적는다 — 관리자, 그리고 이 가게에 연결된 점주. 그 밖은 모두 거절한다.
    // "점주가 아니면 통과"로 쓰면, 나중에 역할이 늘 때 새 역할이 검사 없이 모든 가게에 기록할 수 있게 된다.
    private void requireAccessTo(Long storeId, AuthMember authMember) {
        // ⚠️ null 은 권한 검사 스위치(auth.enforce)가 꺼져 있을 때만 생긴다. 스위치를 지우면
        // (SecurityConfig 주석의 기한 참고) 이 API 는 항상 로그인한 사용자만 닿으므로 이 분기도 함께 지운다.
        if (authMember == null) {
            return;
        }
        if (authMember.role() == MemberRole.ADMIN) {
            return;
        }
        if (authMember.role() != MemberRole.OWNER
                || !storeOwnerRepository.existsByStoreIdAndMemberId(storeId, authMember.memberId())) {
            log.info("체크인 거절 - 연결되지 않은 가게, storeId={}, memberId={}", storeId, authMember.memberId());
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
    }
}
