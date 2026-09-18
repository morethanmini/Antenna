package ssafy.a507.backend.common.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * API 명세 §1 의 오류 code 어휘. 대문자 스네이크로 고정한다.
 * 프론트가 code 로 분기하므로 한 번 정한 값은 바꾸지 않는다.
 *
 * <p>명세 §1 "오류 code 어휘" 표가 원본이다. 여기에 값을 추가하면 그 표도 함께 고친다 —
 * 표에 없는 code 를 즉석에서 만들지 않기로 팀에서 정했다.
 */
@Getter
public enum ErrorCode {

    // 공통
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    /**
     * <b>매핑되지 않은 경로</b>. 도메인 404(USER_NOT_FOUND · REPORT_NOT_FOUND …)와 다르다 —
     * 저쪽은 "그 리소스가 없다" 이고 이것은 "그런 경로가 애초에 없다" 다.
     *
     * <p>프론트가 둘을 구분해야 한다: 도메인 404 는 화면이 빈 상태로 그리면 되지만, 이 코드는
     * 클라이언트 쪽 오타이거나 서버에서 사라진 엔드포인트라 화면이 할 수 있는 일이 없다.
     */
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),
    /**
     * 경로는 있는데 그 메서드를 받지 않는다 — {@code DELETE /predictions/1}.
     *
     * <p>{@link #NOT_FOUND} 와 가른다: 저쪽은 "그런 주소가 없다", 이쪽은 "주소는 맞는데 그렇게는 못 부른다" 다.
     * 클라이언트가 고칠 곳이 다르므로 같은 code 로 뭉개지 않는다.
     */
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "이 경로에서 지원하지 않는 요청 방식입니다."),
    /** 본문 형식을 못 읽는다 — {@code Content-Type} 이 없거나 {@code application/json} 이 아니다. */
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 본문 형식입니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),

    // 인증 (ANT-AUTH-01 · ANT-AUTH-02)
    /** 어휘에는 있지만 아직 붙이지 않은 프로바이더. 프론트 오타(INVALID_REQUEST)와 구분한다. */
    PROVIDER_NOT_SUPPORTED(HttpStatus.NOT_IMPLEMENTED, "아직 지원하지 않는 로그인 방식입니다."),
    /** 제재로 막힌 계정. 다시 로그인해도 풀리지 않으므로 401 이 아니라 403 이다. */
    ACCOUNT_BANNED(HttpStatus.FORBIDDEN, "이용이 제한된 계정입니다."),

    // 멱등성 (명세 §1)
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency-Key 헤더가 필요합니다."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "같은 Idempotency-Key 로 다른 요청을 보냈습니다."),

    // 회원 (ANT-AUTH-03)
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."),
    DUPLICATE_NICKNAME(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),

    // 신고 (ANT-COMMUNITY-04)
    TARGET_NOT_FOUND(HttpStatus.NOT_FOUND, "신고 대상을 찾을 수 없습니다."),
    SELF_REPORT(HttpStatus.BAD_REQUEST, "자신을 신고할 수 없습니다."),
    DUPLICATE_REPORT(HttpStatus.CONFLICT, "이미 접수된 신고입니다."),

    // 관리자 (ANT-ADMIN-01)
    /**
     * 관리자 전용 엔드포인트를 일반 회원이 불렀다.
     *
     * <p>뜻이 같은 {@code EVENT_ADMIN_ONLY} 가 이미 있는데 새로 두는 이유 — 그쪽은 이벤트 화면이
     * 분기에 쓰고 있을 수 있는 이름이라 범용으로 바꾸지 않는다. 한 번 나간 code 어휘는 고정이다.
     */
    ADMIN_ONLY(HttpStatus.FORBIDDEN, "관리자만 할 수 있습니다."),
    /** 이미 처리한 신고를 또 처리하려 했다. 두 번 눌러도 두 번 반영되지 않게 막는 자리다. */
    REPORT_ALREADY_HANDLED(HttpStatus.CONFLICT, "이미 처리한 신고입니다."),
    /**
     * 관리자가 자기 계정을 차단하려 했다.
     *
     * <p>막지 않으면 되돌릴 길이 없다 — {@code AuthService} 가 BANNED 로그인을 거부하므로
     * 스스로를 내린 관리자는 다시 들어와 풀 수 없고, DB 를 직접 고치는 수밖에 없어진다.
     */
    ADMIN_SELF_DEMOTE(HttpStatus.CONFLICT, "자신의 계정 상태는 바꿀 수 없습니다."),

    // 피드 글 (ANT-COMMUNITY-02)
    POST_NOT_FOUND(HttpStatus.NOT_FOUND, "글을 찾을 수 없습니다."),
    REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "리포트를 찾을 수 없습니다."),
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."),
    PREDICTION_NOT_FOUND(HttpStatus.NOT_FOUND, "예측을 찾을 수 없습니다."),

    // 댓글 · 좋아요 (ANT-COMMUNITY-03)
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다."),
    /** 이미 누른 좋아요를 다시 눌렀다. 취소는 멱등(204)이지만 등록은 중복을 알려준다. */
    DUPLICATE_LIKE(HttpStatus.CONFLICT, "이미 좋아요를 누른 대상입니다."),

    // 업로드 (ANT-COMMUNITY-06)
    /** 다른 API 가 넘겨받은 fileId 가 upload_files 에 없다. 남의 fileId 를 지목한 경우도 여기로 온다. */
    UPLOAD_FILE_NOT_FOUND(HttpStatus.NOT_FOUND, "업로드한 파일을 찾을 수 없습니다."),
    UNSUPPORTED_IMAGE_TYPE(HttpStatus.BAD_REQUEST, "png · jpeg · webp 이미지만 올릴 수 있습니다."),
    /** 배너는 노출 자리가 고정이라 비율이 어긋나면 잘리거나 늘어난다. 올리는 시점에 막는다. */
    INVALID_IMAGE_RATIO(HttpStatus.BAD_REQUEST, "배너 이미지 비율이 규격과 다릅니다."),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "파일 용량이 5MB 를 넘습니다."),
    /** 용량과 별개다 — 헤더만 큰 크기를 선언한 작은 파일이 렌더 시점에 클라이언트를 마비시킨다. */
    IMAGE_TOO_LARGE(HttpStatus.BAD_REQUEST, "이미지 크기가 너무 큽니다."),

    // 광고 (ANT-COMMUNITY-05)
    /** 고정 단가 × 기간 만큼의 토큰이 없다. 명세 §광고의 409 두 사유 중 하나다. */
    INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "토큰 잔액이 부족합니다."),
    /** 같은 기간에 이미 슬롯이 다 찼다. 선착순이므로 기다렸다 다시 신청하는 수밖에 없다. */
    AD_SLOT_SOLD_OUT(HttpStatus.CONFLICT, "해당 기간의 광고 슬롯이 마감되었습니다."),
    // 광고 승인제 (ANT-ADMIN-03)
    AD_NOT_FOUND(HttpStatus.NOT_FOUND, "광고 신청을 찾을 수 없습니다."),
    /** 남의 신청을 취소하려 했다. 404 로 숨기지 않는 이유 — 광고 id 는 순번이라 어차피 존재를 짐작할 수 있다. */
    AD_FORBIDDEN(HttpStatus.FORBIDDEN, "본인의 광고 신청만 취소할 수 있습니다."),
    /** 이미 승인·반려·취소됐거나 소각 tx 가 나가는 중이다. 두 번 눌러도 두 번 태우지 않는다. */
    AD_ALREADY_HANDLED(HttpStatus.CONFLICT, "이미 처리된 광고 신청입니다."),

    // 작업 리소스 (ANT-COMMUNITY-07)
    /** 없는 operationId 이거나 종료 후 24h 가 지나 정리된 작업이다. 둘을 구분해 주지 않는다. */
    OPERATION_NOT_FOUND(HttpStatus.NOT_FOUND, "작업을 찾을 수 없습니다."),
    /** 남의 작업이다. 404 로 숨기지 않는 이유는 명세가 403 으로 못박았기 때문이다. */
    OPERATION_FORBIDDEN(HttpStatus.FORBIDDEN, "다른 사용자의 작업입니다."),

    // 시세 (ANT-DATA-03)
    /** 종목코드가 종목 마스터에 없다. 구간에 시세가 없는 것(200 + 빈 목록)과 구분한다. */
    STOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "종목을 찾을 수 없습니다."),

    // 모의투자 시즌 (ANT-SEASON-01)
    /**
     * 시즌이 없다. 관리자 전용 모드(DEMO)를 일반 사용자가 부른 것도 이 code 다 —
     * 403 으로 답하면 "그런 시즌이 있다" 는 사실이 새고, 시연 시즌은 존재 자체가 비공개다.
     */
    SEASON_NOT_FOUND(HttpStatus.NOT_FOUND, "시즌을 찾을 수 없습니다."),
    /** 그 시즌에 없는 종목. 남의 시즌 종목 id 를 넣어 가격을 떠보는 것도 여기로 막힌다. */
    SEASON_TICKER_NOT_FOUND(HttpStatus.NOT_FOUND, "시즌 종목을 찾을 수 없습니다."),
    /** 진행 중인 회차가 있다. 끝내야 다음 회차를 시작할 수 있다(ANT-SEASON-03). */
    SEASON_ALREADY_JOINED(HttpStatus.CONFLICT, "이미 진행 중인 회차가 있습니다."),
    /** RUNNING 이 아닌 시즌에는 참가할 수 없다. */
    SEASON_NOT_RUNNING(HttpStatus.CONFLICT, "참가할 수 있는 상태의 시즌이 아닙니다."),
    /** 대회 참가는 참가비 소각 서명이 붙는다 — 아직 없다(ANT-SEASON-06 · TOKEN). */
    SEASON_JOIN_NOT_SUPPORTED(HttpStatus.NOT_IMPLEMENTED, "대회 참가는 아직 지원하지 않습니다."),
    /** 참가한 적이 없는 시즌의 현황·주문·체결 내역을 불렀다. 참가가 먼저다(ANT-SEASON-03). */
    SEASON_NOT_JOINED(HttpStatus.CONFLICT, "참가하지 않은 시즌입니다."),
    /** 매도 수량이 보유 수량을 넘는다. 매수의 예수금 부족은 INSUFFICIENT_BALANCE 다. */
    SEASON_INSUFFICIENT_QTY(HttpStatus.CONFLICT, "보유 수량이 부족합니다."),
    /** 그 종목에 내 진행일 봉이 없다. 게임일 구간은 전 종목이 빠짐없이 있어야 하므로 데이터 결함이다. */
    SEASON_PRICE_NOT_FOUND(HttpStatus.NOT_FOUND, "그 게임일의 가격이 없습니다."),
    /** expectedDay 가 서버의 현재 게임일과 다르다 — 낙관적 잠금(ANT-SEASON-04). 화면은 다시 읽고 다시 누른다. */
    DAY_MISMATCH(HttpStatus.CONFLICT, "보고 있는 게임일이 서버와 다릅니다."),
    /** 대회는 공용 진행일이라 개인이 넘길 수 없다 — 배치 B5 가 넘긴다(ANT-SEASON-08). */
    SEASON_ADVANCE_NOT_ALLOWED(HttpStatus.CONFLICT, "대회 시즌은 수동으로 진행할 수 없습니다."),
    /** 끝난(DONE) 회차에 주문·진행을 보냈다. 다시 하려면 새 회차로 참가한다. */
    SEASON_ATTEMPT_ENDED(HttpStatus.CONFLICT, "끝난 회차입니다."),
    /** 마지막 게임일에서 더 넘기려 했다. 종료(finish)가 다음 단계다. */
    SEASON_LAST_DAY(HttpStatus.CONFLICT, "마지막 게임일입니다. 종료해 주세요."),
    /** 마지막 게임일 전에 종료하려 했다. 연습은 끝까지 가야 결과가 있다. */
    SEASON_NOT_LAST_DAY(HttpStatus.CONFLICT, "아직 마지막 게임일이 아닙니다."),
    /** 끝나지 않은 회차의 결과를 불렀다. 결과는 finish 가 만든다. */
    SEASON_RESULT_NOT_FOUND(HttpStatus.NOT_FOUND, "아직 결과가 없습니다."),
    SEASON_REVIEW_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "AI 복기를 만들지 못했습니다. 잠시 후 다시 시도해 주세요."),

    // AI 브리핑 (ANT-RESEARCH-03)
    BRIEFING_NOT_FOUND(HttpStatus.NOT_FOUND, "브리핑을 찾을 수 없습니다."),

    // 투자 포인트 (ANT-RESEARCH-04) — 요청 시점 생성이 실패했다. 다시 부르면 다시 만든다.
    POINT_GENERATION_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "투자 포인트를 만들지 못했습니다. 잠시 후 다시 시도해 주세요."),

    // 관심 종목 (ANT-DATA-06)
    /** 이미 담은 종목을 다시 담았다. 빼기(DELETE)는 멱등(204)이라 이 code 를 쓰지 않는다. */
    DUPLICATE_WATCHLIST_ITEM(HttpStatus.CONFLICT, "이미 관심 종목에 있습니다."),

    // 지갑 · 서명 (ANT-AUTH-04 · ANT-AUTH-06)
    INVALID_SIGNATURE(HttpStatus.BAD_REQUEST, "서명 형식이 올바르지 않습니다."),
    /** 발급받은 적이 없거나 이미 썼거나 5분이 지났다. 셋을 구분해 주지 않는다 — 대응은 "재발급"으로 같다. */
    NONCE_NOT_FOUND(HttpStatus.BAD_REQUEST, "인증 요청이 만료되었습니다. 다시 시도해주세요."),
    /** 서명에서 복원한 주소가 기대한 주소와 다르다. 변조이거나 다른 지갑으로 서명한 것이다. */
    SIGNER_MISMATCH(HttpStatus.UNAUTHORIZED, "서명한 지갑이 일치하지 않습니다."),
    /** 프론트가 이 코드를 보고 지갑 연동 화면으로 유도한다. */
    WALLET_NOT_LINKED(HttpStatus.BAD_REQUEST, "지갑을 먼저 연동해주세요."),
    WALLET_ALREADY_LINKED(HttpStatus.CONFLICT, "이미 연동된 지갑입니다."),

    // 온체인 (ANT-CHAIN-05)
    /**
     * RPC 노드에 닿지 못했거나 릴레이어가 설정되지 않았다. 온체인 동반 요청만 이 코드로 실패하고
     * 조회 API 는 정상이다(명세 §1). 503 인 이유: 서버 잘못이 아니라 의존 서비스가 없는 상태라서다.
     */
    CHAIN_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "블록체인 네트워크에 연결할 수 없습니다. 잠시 후 다시 시도해주세요."),

    // 예측 등록 (ANT-PRED-01)
    /**
     * 오늘 무료 슬롯(3건)을 다 썼다. 명세는 "서명 동반 소각(202) 경로로 전환" 이라는 뜻으로 이 code 를 두었다 —
     * 소각할 토큰(ANT-CHAIN-03)이 생기면 그 재요청을 받는 202 경로가 여기에 붙는다. 지금은 여기서 끝난다.
     */
    PREDICTION_SLOT_EXCEEDED(HttpStatus.CONFLICT, "오늘 무료 예측 슬롯을 모두 사용했습니다."),

    // 온체인 검증 (ANT-CHAIN-06)
    /** 없는 batchId 다. 체인에는 있는데 DB 에 없는 번호(데모 소모분)도 여기로 온다 — 원장은 DB 기준이다. */
    ANCHOR_NOT_FOUND(HttpStatus.NOT_FOUND, "앵커 배치를 찾을 수 없습니다."),
    /**
     * 미판정(BASE/OPEN) 예측을 작성자도 구독자도 아닌 사람이 열었다. 404 로 숨기지 않는다 — 명세 §예측 공개 규칙은
     * "존재와 커밋 무결성은 공개" 이고, 프론트가 이 code 로 구독 CTA(SubscriptionGate)를 띄운다.
     */
    PREDICTION_FORBIDDEN(HttpStatus.FORBIDDEN, "판정 전 예측은 작성자와 구독자만 볼 수 있습니다."),

    // 리포트 좋아요 · 댓글 (ANT-REPORT-SOCIAL)
    /**
     * 구독자 전용 리포트에 미구독자가 좋아요·댓글을 시도했다. 열람은 200 + {@code locked:true} 인데
     * 반응은 403 인 이유는, 열람에는 보여 줄 것(제목·미리보기)이 있지만 반응에는 없기 때문이다.
     * 댓글은 본문에 대한 반응이고, 좋아요만 허용하면 읽지 않고 누른 인기순이 된다.
     * 프론트는 이 code 로 구독 CTA 를 띄운다 — {@code PREDICTION_FORBIDDEN} 과 같은 쓰임이다.
     */
    REPORT_FORBIDDEN(HttpStatus.FORBIDDEN, "구독자 전용 리포트에는 구독자만 반응할 수 있습니다."),

    // 리포트 신고 블라인드 (ANT-FE-ABUSE-REPORT)
    /**
     * 신고가 승인돼 가려진 리포트를 열었다(상세·좋아요·댓글).
     *
     * <p>{@code REPORT_NOT_FOUND} 로 뭉개지 않는 이유 — 알림이나 북마크로 들어온 사람에게 "없는 리포트"
     * 라고 하면 고장으로 읽는다. 있었고 가려졌다는 사실은 말해 준다. 410 인 이유는 지금 이 주소의 내용을
     * 내줄 수 없다는 뜻이어서다 — 관리자가 {@code PATCH /admin/reports/{id}/status} 로 복구하면 다시 열린다.
     */
    REPORT_BLOCKED(HttpStatus.GONE, "신고로 가려진 리포트입니다."),

    // 리포트 검산 (ANT-CHAIN-17)
    /**
     * 봉인 이전에 발행된 리포트의 검산을 요청했다({@code body_hash} NULL). 404 지만
     * {@code REPORT_NOT_FOUND} 와 다른 code 인 이유는 <b>리포트는 있기 때문</b>이다 — 없는 것으로 말하면
     * 화면이 "지워진 글" 로 그린다. 소급 봉인을 하지 않기로 한 결정(ANT-CHAIN-14)의 겉면이라, 프론트는
     * 이 code 로 "봉인 이전 발행분" 이라고 적는다.
     */
    REPORT_NOT_SEALED(HttpStatus.NOT_FOUND, "봉인 이전에 발행된 리포트입니다."),

    /**
     * 자기 채널을 자기가 구독하려 했다 (ANT-TOKEN-01). 400 이 아니라 409 인 이유 — 요청 <b>모양</b>은
     * 옳고 지금 상태에서 할 수 없는 일이다. 명세도 자기 구독·중복 구독·잔액 부족을 409 한 줄로 묶었다.
     */
    SELF_SUBSCRIBE(HttpStatus.CONFLICT, "자기 채널은 구독할 수 없습니다."),

    /**
     * 이미 유효한 구독이 있다 (ANT-TOKEN-01). PENDING 도 막는다 — 결제가 체인에서 확정되기를 기다리는
     * 중에 한 번 더 누르면 같은 채널에 두 번 결제된다. 만료된 구독은 막지 않는다(재구독은 정상이다).
     */
    ALREADY_SUBSCRIBED(HttpStatus.CONFLICT, "이미 구독 중인 채널입니다."),

    /**
     * 채널이 구독료를 아직 정하지 않았다 (ANT-TOKEN-01). 0 원으로 열어 주지 않는다 — 구독료는 정해야
     * 생기는 값이고(publisher_fees 에 행이 없다), 0 으로 보면 무료 구독이 만들어진다.
     */
    FEE_NOT_SET(HttpStatus.CONFLICT, "이 채널은 아직 구독료를 정하지 않았습니다."),

    /**
     * 내 구독이 아니거나 없는 구독이다 (ANT-TOKEN-05). 남의 구독에 403 을 주면 "그 id 의 구독은
     * 있다" 가 새 나가므로 둘을 같은 404 로 묶는다 — {@code OPERATION_NOT_FOUND} 와 같은 판단이다.
     */
    SUBSCRIPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "구독을 찾을 수 없습니다."),

    // ── 배팅 이벤트 (ANT-EVENT) — REST 작업명세 v1.2 §7 ──────────────────────────
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "이벤트를 찾을 수 없습니다."),
    EVENT_NOT_OPEN(HttpStatus.CONFLICT, "접수 중인 이벤트가 아닙니다."),
    EVENT_SEALED(HttpStatus.CONFLICT, "접수가 마감된 이벤트입니다."),
    EVENT_MIN_STOCKS(HttpStatus.BAD_REQUEST, "처음 참가할 때는 5종목 이상 선택해야 합니다."),
    EVENT_STOCK_NOT_INCLUDED(HttpStatus.BAD_REQUEST, "이벤트 종목이 아닙니다."),
    EVENT_STOCK_ALREADY_BET(HttpStatus.CONFLICT, "이미 배팅한 종목입니다."),
    EVENT_STAKE_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "배팅 금액은 500 ~ 2,000 ANT 사이여야 합니다."),
    EVENT_INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "ANT 잔액이 부족합니다."),
    EVENT_DUPLICATE_SLUG(HttpStatus.CONFLICT, "이미 쓰고 있는 대회 주소입니다."),
    EVENT_NOT_READY(HttpStatus.CONFLICT, "정산 조건이 아직 갖춰지지 않았습니다."),
    // 정산 러너 (ANT-EVENT-03). 셋 다 "다시 누르면 온체인 상태를 읽어 이어 간다" 가 대응이다.
    EVENT_SETTLING(HttpStatus.CONFLICT, "정산이 이미 진행 중입니다."),
    EVENT_SETTLE_TX_REVERTED(HttpStatus.CONFLICT, "블록체인이 정산 트랜잭션을 거절했습니다. 상태를 확인하고 다시 실행하세요."),
    EVENT_SETTLE_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE, "정산 트랜잭션 확정을 기다리다 시간이 초과됐습니다. 다시 실행하세요."),

    /**
     * 관리자 전용 엔드포인트를 일반 회원이 불렀다 (작업명세 v1.2 §0.2 ④).
     *
     * <p>매처나 {@code @PreAuthorize} 가 아니라 <b>서비스 안에서 {@code role} 을 비교</b>해 던진다 —
     * JWT 의 {@code role} 클레임을 Spring 권한({@code ROLE_ADMIN})으로 바꾸는 컨버터가 없어, 그 둘은
     * {@code SecurityConfig}(공용) 수정과 {@code @EnableMethodSecurity} 가 함께 필요하다. 기존 관례도
     * 서비스 안 비교다({@code SeasonJoinService} 의 DEMO 시즌). 매처 도입은 전 관리자 API 공통 후속 이슈.
     */
    EVENT_ADMIN_ONLY(HttpStatus.FORBIDDEN, "관리자만 할 수 있습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }
}
