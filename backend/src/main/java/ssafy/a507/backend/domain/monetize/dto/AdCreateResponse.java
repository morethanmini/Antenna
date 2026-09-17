package ssafy.a507.backend.domain.monetize.dto;

import ssafy.a507.backend.domain.monetize.entity.AdBanner;

/**
 * POST /api/v1/ads 201 응답.
 *
 * <p>{@code operationId} 가 없어졌다(ANT-ADMIN-03). 신청은 접수만 하고 소각 tx 를 보내지 않으므로 폴링할 작업이 없다.
 * 돈은 관리자가 승인하는 순간 나간다. 화면은 "심사 중" 으로 끝내고, 마음이 바뀌면 {@code DELETE /ads/{adId}} 로 취소한다.
 */
public record AdCreateResponse(Long adId, AdBanner.Status status) {}
