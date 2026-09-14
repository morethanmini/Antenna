package ssafy.a507.backend.domain.account.dto;

/**
 * 연동된 지갑 주소(소문자 정규화)와 가입 보너스 접수 (ANT-TOKEN-07).
 *
 * <p>{@code bonus} 는 보너스 작업을 만들지 않았을 때(체인 꺼짐)만 null 이다 — 필드를 지우지 않고 null 로 내린다.
 * 전송이 실패해도 작업(FAILED)은 있으니 번호를 준다. 화면은 이 번호로 {@code GET /operations/{id}} 를 폴링할 수 있다.
 */
public record WalletLinkResponse(String walletAddress, Bonus bonus) {

    /** @param amount 정수 ANT 문자열. 금액 필드는 문자열로 통일한다(명세). */
    public record Bonus(String operationId, String amount) {}
}
