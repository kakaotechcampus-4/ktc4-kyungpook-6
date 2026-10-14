package com.ktc4.backend.domain.member.service;

import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.repository.StoreOwnerRepository;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.AuthMember;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인한 사용자가 그 가게를 다룰 수 있는지 확인한다 — 체크인, 가게 상세 조회, 가게 운영 정보 저장이 같이 쓴다.
 *
 * <p>"그 가게에 연결된 점주"의 정의를 한 곳에 둔다. 끊긴 연결은 연결이 아니다
 * ({@link StoreOwnerRepository#existsByStoreIdAndMemberId}). API 마다 따로 확인하면 정의가 조금씩 어긋난다.
 *
 * <p>⚠️ 이 서비스는 가게 쪽 서비스에 의존하지 않는다. 가게 쪽 서비스가 이 서비스를 부르므로, 거꾸로 부르면
 * 두 도메인이 서로를 부르는 순환이 생긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreAccessService {

    private final StoreOwnerRepository storeOwnerRepository;

    /**
     * 관리자이거나 그 가게에 연결된 점주인지 확인한다. 아니면 막는다.
     *
     * <p>가게를 읽기 전에 부른다 — 연결되지 않은 점주에게는 그 가게가 있는지(404 와 403 의 차이)도 알려 주지 않기
     * 위해서다. 연결된 점주라면 그 가게는 반드시 있으므로 순서를 앞에 둬도 잃는 것이 없다.
     *
     * <p>통과시킬 대상을 하나씩 적는다 — 관리자, 그리고 이 가게에 연결된 점주. 그 밖은 모두 거절한다.
     * "점주가 아니면 통과"로 쓰면, 나중에 역할이 늘 때 새 역할이 검사 없이 모든 가게에 닿게 된다.
     *
     * @param storeId    가게 ID
     * @param authMember 로그인한 사용자
     * @throws CustomException 로그인 정보가 없으면 {@code UNAUTHORIZED},
     *                         관리자도 아니고 이 가게에 연결된 점주도 아니면(없는 가게 포함) {@code FORBIDDEN}
     */
    public void requireAccessTo(Long storeId, AuthMember authMember) {
        // 보안 필터가 점주·관리자만 들여보내므로 null 은 오지 않는다. 그래도 규칙이 잘못 열렸을 때 통과시키거나
        // 500 으로 터지지 않고 막히게 한다 — "누가"를 모르면 가게를 다루게 하지 않는다.
        if (authMember == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        if (authMember.role() == MemberRole.ADMIN) {
            return;
        }
        if (authMember.role() != MemberRole.OWNER
                || !storeOwnerRepository.existsByStoreIdAndMemberId(storeId, authMember.memberId())) {
            log.info("가게 접근 거절 - 연결되지 않은 가게, storeId={}, memberId={}", storeId, authMember.memberId());
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
    }
}
