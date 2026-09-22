package ssafy.a507.backend.domain.monetize.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import ssafy.a507.backend.common.security.SignatureScope;
import ssafy.a507.backend.common.security.WalletSigned;
import ssafy.a507.backend.common.validation.NoProfanity;
import ssafy.a507.backend.domain.prediction.commit.CommitHashes;

/**
 * POST /api/v1/reports 요청 본문.
 *
 * <p>{@code visibility} 를 {@code Boolean} 으로 둔 이유 — {@code boolean} 이면 필드를 아예
 * 빼고 보낸 요청이 false(구독자 전용)로 조용히 처리된다. 공개 여부는 기본값으로 정할 값이
 * 아니라서 누락을 400 으로 잡는다.
 *
 * <p><b>지갑 서명을 받는다(ANT-CHAIN-16).</b> 리포트가 온체인에 올라가는데(ANT-CHAIN-14) 서명이 없으면
 * 앵커가 증명하는 것이 "이 내용이 그때 있었고 안 바뀌었다" 까지다 — <b>"이 지갑 주인이 썼다" 는 서버를
 * 믿어야만 성립</b>했다. {@code reportHash} 재료의 {@code authorId} 는 서버가 넣은 값이라 서버가
 * 바꿔치기하지 않았음을 체인이 증명하지 못한다. 예측은 서명이 있어 서버를 믿지 않아도 작성자가 증명된다.
 */
public record ReportCreateRequest(

        @NotBlank(message = "제목을 입력해야 합니다.")
        @Size(max = 100, message = "제목은 100자를 넘을 수 없습니다.")
        @NoProfanity
        String title,

        @NotBlank(message = "본문을 입력해야 합니다.")
        @Size(max = 20000, message = "본문은 20000자를 넘을 수 없습니다.")
        @NoProfanity
        String body,

        @NotNull(message = "공개 여부를 지정해야 합니다.")
        Boolean visibility,

        @NotBlank(message = "서명이 필요합니다.")
        @Pattern(regexp = "^0x[0-9a-fA-F]{130}$", message = "서명 형식이 올바르지 않습니다.")
        String signature
)
        implements WalletSigned {

    @Override
    public SignatureScope scope() {
        return SignatureScope.REPORT_PUBLISH;
    }

    /**
     * 서명 문자열.
     *
     * <pre>
     * antenna:report-publish:v1
     * title=…
     * visibility=…
     * bodyHash=0x…
     * chainId=…
     * nonce=…
     * </pre>
     *
     * <p><b>본문 대신 {@code bodyHash} 에 서명한다.</b> 20000자를 지갑 확인창에 띄우면 사용자가 읽지 않고
     * 누르게 되고, 서명 payload 는 사용자가 무엇에 동의하는지 보이는 것이 존재 이유다. 해시면 본문이 한 글자만
     * 달라도 값이 갈리므로 증명력은 같다.
     *
     * <p><b>{@code salt} 는 서버가 만든다</b> — 그래서 서명 시점에는 아직 없다. 서명은 {@code bodyHash} 에
     * 붙는데 {@code bodyHash = keccak256(body ‖ salt)} 라 salt 가 필요하다. 그래서 서명 payload 의
     * {@code bodyHash} 는 <b>salt 없는 본문 해시</b>를 쓴다({@link #signedBodyHash()}) — 봉인의
     * {@code bodyHash} 와 다른 값이고, 이 둘은 하는 일이 다르다: 서명은 "내가 이 본문에 동의했다",
     * 봉인은 "이 본문이 안 바뀌었다(비구독자에게 본문을 가린 채로)".
     *
     * <p><b>{@code authorId} 는 서명 재료에 없다.</b> 서명자 주소를 {@code users.wallet_address} 와
     * 대조하는 것이 곧 작성자 확인이라({@code SignatureGuard.verify}) 서버 내부 id 를 서명에 넣을 이유가 없다.
     */
    @Override
    public String signingPayload(String nonce, long chainId) {
        return "antenna:" + scope().tag() + ":v1"
                + "\ntitle=" + title
                + "\nvisibility=" + visibility
                + "\nbodyHash=" + signedBodyHash()
                + "\nchainId=" + chainId
                + "\nnonce=" + nonce;
    }

    /**
     * 서명이 붙는 본문 해시 — salt 가 섞이지 않은 {@code keccak256(body)} 다.
     *
     * <p>봉인의 {@code bodyHash}({@code keccak256(body ‖ salt)}) 와 다른 값이라는 것이 중요하다.
     * 이 값은 프론트도 서명 직전에 같은 식으로 계산할 수 있어야 한다 — salt 를 서버가 만드는 이상
     * 클라이언트가 봉인 해시를 미리 알 방법이 없다.
     */
    public String signedBodyHash() {
        return CommitHashes.keccak256Hex(body == null ? "" : body);
    }
}
