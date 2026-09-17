package ssafy.a507.backend.domain.monetize.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 광고 게재 조건 (ANT-COMMUNITY-05). 경매가 아니라 고정 단가 × 기간제 선착순이다.
 *
 * <p>단가는 여기 없다 — 토큰 금액표 {@code app.token.amounts.ad-per-day}({@code TokenAmountProperties}, ANT-TOKEN-08)에 있다.
 * 금액을 도메인마다 따로 두면 슬롯 초과·광고·보너스가 서로 말이 되는지 한눈에 볼 수 없다(광고가 1 ANT = 1원으로 사실상 공짜였던 걸
 * 아무도 못 봤다).
 *
 * <p>{@code pending-grace-minutes} 는 없어졌다(ANT-ADMIN-03). 승인제 전에는 "확정을 기다리는 신청이 자리를 잡는 시간" 이었는데,
 * 승인 대기는 몇 시간일 수 있어 그 창이 지나면 자리를 놓친 것으로 오판한다. 이제 대기는 자리를 기간 내내 잡고, 자리만 붙잡는 신청은
 * 관리자가 반려하거나 신청자가 취소한다. yaml 에 그 키가 남아 있어도 무시된다.
 *
 * @param slotCount 같은 기간에 받을 수 있는 배너 수. 메인 배너 자리가 하나라 1 이다 —
 *     자리를 늘리면 이 값만 바꾼다.
 * @param maxDays 한 번에 살 수 있는 최대 기간. 상한이 없으면 한 명이 몇 년치를 선점한다.
 */
@ConfigurationProperties(prefix = "app.ads")
public record AdProperties(Integer slotCount, Integer maxDays) {

    public AdProperties {
        slotCount = slotCount == null ? 1 : slotCount;
        maxDays = maxDays == null ? 30 : maxDays;
    }
}
