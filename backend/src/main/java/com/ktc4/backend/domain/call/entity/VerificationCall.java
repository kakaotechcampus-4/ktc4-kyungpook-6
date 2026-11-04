package com.ktc4.backend.domain.call.entity;

import com.ktc4.backend.domain.call.enums.CallReason;
import com.ktc4.backend.domain.call.enums.CallStatus;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.task.entity.Task;
import com.ktc4.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 가게에 건 확인 전화 한 건. 전화 API 에 요청을 넣고 받은 {@code verificationId} 와, 그 뒤로 받아 온
 * 진행 상태·답을 담는다.
 *
 * <p><b>담당자 확인 기록({@code Verification})과 다른 것이다.</b> 이름이 비슷한 것은 전화 API 가
 * 자기 리소스를 {@code verification-call} 로 부르기 때문이고, 둘의 의미는 이렇게 나뉜다.
 *
 * <ul>
 *   <li>{@code VerificationCall}(이 클래스) — 전화를 걸어 점주에게 물은 기록. 자동으로 생긴다</li>
 *   <li>{@code Verification} — 담당자가 가게를 확인했다고 사람이 남긴 기록. 전화 결과를 보고 담당자가 남긴다</li>
 * </ul>
 *
 * <p>전화 한 통이 담당자 확인을 대신하지 않는다 — 점주가 전화로 "그대로입니다" 라고 해도 가게 확인일
 * ({@code Store.lastCheckedAt})은 올라가지 않는다. 그 판단은 답을 읽은 담당자가 한다.
 *
 * <p>{@code task} 는 조사 결과 카드에서 걸었을 때 채워진다. 가게 목록에서 바로 거는 길이 생기면 그때는
 * 비어 있을 수 있어 nullable 로 둔다({@code Verification.task} 와 같은 이유).
 *
 * <p>시각이 다섯이고 뜻이 다 다르다. {@code requestedAt} 은 우리가 요청을 넣은 시각이고, 나머지 셋
 * ({@code startedAt}·{@code connectedAt}·{@code endedAt})은 전화 API 가 알려 준 시각을 옮겨 적은 것이다.
 * {@code BaseTimeEntity} 의 {@code createdAt}·{@code updatedAt} 은 이 행이 언제 생기고 바뀌었는지일 뿐이다.
 */
@Entity
@Table(
        name = "verification_call",
        indexes = {
                @Index(name = "idx_verification_call_store_id", columnList = "store_id"),
                @Index(name = "idx_verification_call_task_id", columnList = "task_id"),
                // 폴러가 "아직 안 끝난 전화" 를 찾을 때 쓴다. 끝난 전화가 쌓여도 훑는 범위가 늘지 않게 한다.
                @Index(name = "idx_verification_call_status", columnList = "status, requested_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VerificationCall extends BaseTimeEntity {

    /** 실패 이유를 적는 칸의 길이. 전화 API 가 주는 코드와 우리가 정해 둔 문구가 들어간다. */
    public static final int FAILURE_REASON_MAX_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "call_id")
    private Long callId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    /** 이 전화를 띄운 조사 결과. 조사 결과 없이 걸었으면 null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id")
    private Task task;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30)
    private CallReason reason;

    /**
     * 전화 API 가 돌려준 {@code verificationId}. 전화 API 쪽 기록을 가리키는 유일한 열쇠라
     * DB 에서 unique 로 묶는다 — 같은 통화를 두 행으로 들고 있으면 폴러가 같은 전화를 두 번 읽는다.
     */
    @Column(name = "external_id", nullable = false, unique = true, length = 64)
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CallStatus status;

    /**
     * 통화에서 확인된 값들. 진행 중이거나 실패한 통화의 부분 결과도 들어온다 —
     * 전화 API 가 그렇게 보내고, 끊긴 통화에서 한 항목이라도 들었으면 그것도 담당자에게 쓸모가 있다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answers", columnDefinition = "jsonb")
    private List<CallAnswer> answers;

    /** 실패 사유. 전화 API 의 {@code failureReason} 코드이거나, 우리가 기다리다 닫았으면 그 문구. */
    @Column(name = "failure_reason", length = FAILURE_REASON_MAX_LENGTH)
    private String failureReason;

    /** 전화를 요청한 관리자 ID. {@code Verification.verifiedBy} 와 같은 모양으로 둔다. */
    @Column(name = "requested_by", nullable = false, length = 100)
    private String requestedBy;

    /** 우리가 전화 API 에 요청을 넣은 시각. 전화 API 의 {@code createdAt} 과 같은 뜻이지만 우리 시계로 잰 값이다. */
    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "connected_at")
    private LocalDateTime connectedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Builder
    private VerificationCall(Store store, Task task, CallReason reason, String externalId,
                             String requestedBy, LocalDateTime requestedAt) {
        this.store = store;
        this.task = task;
        this.reason = reason;
        this.externalId = externalId;
        // 요청을 넣은 직후의 상태다. 전화 API 도 접수 직후를 PENDING 으로 답한다.
        this.status = CallStatus.PENDING;
        this.requestedBy = requestedBy;
        this.requestedAt = requestedAt;
    }

    /**
     * 전화 API 에서 받아 온 진행 상태를 반영한다. 폴러가 부른다.
     *
     * <p>이미 끝난 전화는 바꾸지 않는다 — 전화 API 가 끝난 통화를 다시 열지 않으므로, 끝난 뒤에 다른
     * 값이 온다면 우리가 잘못 읽은 것이거나 같은 전화를 두 번 처리한 것이다. 그런 경우에 기록을 덮어쓰면
     * 담당자가 보던 답이 사라진다.
     *
     * @param status        전화 API 의 지금 상태
     * @param answers       확인된 값들. 아직 없으면 빈 목록이나 null
     * @param startedAt     통화 처리를 시작한 시각. 시작 전이면 null
     * @param connectedAt   상대와 연결된 시각. 연결 전이거나 실패했으면 null
     * @param endedAt       통화 처리가 끝난 시각. 진행 중이면 null
     * @param failureReason 실패 사유. 없으면 null
     * @return 실제로 반영했으면 true. 이미 끝난 전화라 그대로 뒀으면 false
     */
    public boolean apply(CallStatus status, List<CallAnswer> answers,
                         LocalDateTime startedAt, LocalDateTime connectedAt, LocalDateTime endedAt,
                         String failureReason) {
        if (this.status.isFinished()) {
            return false;
        }
        this.status = status;
        this.answers = answers == null || answers.isEmpty() ? null : List.copyOf(answers);
        this.startedAt = startedAt;
        this.connectedAt = connectedAt;
        this.endedAt = endedAt;
        this.failureReason = truncate(failureReason);
        return true;
    }

    /**
     * 기다리기를 그만두고 실패로 닫는다. 전화 API 가 상태를 끝으로 옮겨 주지 않아 영영 진행 중으로 남은
     * 전화를 폴러가 정리할 때 부른다 — 닫지 않으면 폴러가 그 전화를 끝없이 다시 읽고, 담당자 화면에도
     * "전화 중" 이 영영 남는다.
     *
     * @param reason    왜 닫는지. 화면에 나가므로 정해 둔 문구를 넘긴다
     * @param closedAt  닫은 시각
     * @return 실제로 닫았으면 true. 이미 끝난 전화면 false
     */
    public boolean giveUp(String reason, LocalDateTime closedAt) {
        if (status.isFinished()) {
            return false;
        }
        this.status = CallStatus.FAILED;
        this.failureReason = truncate(reason);
        this.endedAt = closedAt;
        return true;
    }

    // 전화 API 가 긴 사유를 보내도 저장이 실패하지 않게 칸 길이에 맞춘다. 사유는 길이보다 남는 것이 중요하다.
    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= FAILURE_REASON_MAX_LENGTH
                ? reason
                : reason.substring(0, FAILURE_REASON_MAX_LENGTH);
    }
}
