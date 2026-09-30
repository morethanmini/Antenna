package ssafy.a507.backend.common.security;

/**
 * 지갑 서명이 붙는 요청의 종류.
 *
 * <p>한 값이 두 곳에 동시에 쓰인다 — payload 첫 줄의 용도 태그(<code>antenna:&lt;tag&gt;:v1</code>)와
 * nonce Redis 키의 마지막 칸(<code>sig:nonce:{userId}:{tag}</code>). 둘을 따로 두면 어긋나는 순간
 * 원인 모를 401이 나므로 여기 하나로 묶는다.
 */
public enum SignatureScope {
    WALLET_LINK("wallet-link"),
    /** 예측 등록(ANT-PRED-01, 결정 B4). */
    PREDICTION("prediction"),
    PREDICTION_BURN("prediction-burn"),
    SUBSCRIBE("subscribe"),
    /**
     * 배팅 이벤트 묶음 등록(ANT-EVENT-05). 한 서명에 5~10 종목이 함께 들어간다 — 종목마다 서명을 받으면
     * 지갑 팝업이 다섯 번 뜨고, 그 사이 하나만 거절해도 "5종목 이상" 규칙이 깨진 채 절반만 등록된다.
     */
    EVENT_ENTRY("event-entry"),
    /**
     * 리포트 발행(ANT-CHAIN-16). 태그가 {@code report} 가 아니라 {@code report-publish} 인 이유 —
     * 봉인 헤더가 이미 {@code antenna:report:v1} 이다({@code ReportSeal.HEADER}). 태그를 {@code report}
     * 로 두면 서명 payload 첫 줄과 봉인 문자열 첫 줄이 같아져, 한쪽 문자열을 다른 쪽으로 오인·재사용할 여지가
     * 생긴다. 예측이 {@code antenna:prediction:v1}(서명) 과 {@code antenna:commit:v1}(봉인)로 갈라 둔 것과 같은 이유다.
     */
    REPORT_PUBLISH("report-publish"),
    AD("ad"),
    SEASON_JOIN("season-join");

    private final String tag;

    SignatureScope(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }
}
