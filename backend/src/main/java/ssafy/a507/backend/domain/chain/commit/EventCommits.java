package ssafy.a507.backend.domain.chain.commit;

import java.math.BigInteger;
import java.util.regex.Pattern;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;
import ssafy.a507.backend.domain.prediction.entity.Prediction;

/**
 * 배팅 이벤트의 온체인 커밋 (ANT-EVENT-02).
 *
 * <p>{@code keccak256(abi.encodePacked(uint256 eventId, uint16 stockIdx, uint8 side, bytes32 noteHash))} — 32+2+1+32 = 67 바이트.
 * 컨트랙트 {@code PredictionEventPool._revealOne} 이 같은 식으로 재계산해 대조하므로 <b>바이트 단위로 같아야 한다.</b>
 * 여기서 어긋나면 리빌이 전부 {@code BadCommit} 으로 revert 하고 배팅금은 환불로 빠진다.
 *
 * <p>기존 예측 커밋(줄 구분 문자열의 keccak, {@code CommitPayload})을 그대로 안 쓰는 이유: 온체인에서 그 문자열을 재조립하려면
 * 목표가 소수 표기·줄바꿈까지 맞춰야 한다. {@code abi.encodePacked} 는 한 줄이다. {@code noteHash} 는 예측 커밋의
 * {@code keccak(note ‖ noteSalt)}({@code CommitHashes.noteHash}) 그대로라 방향 두 가지만 해시해도 사전 공격이 안 된다 —
 * 두 커밋이 같은 난수를 물려받는다.
 *
 * <p>고정 벡터는 Hardhat 의 {@code solidityPackedKeccak256} 으로 만들었다({@code EventCommitsTest}).
 */
public final class EventCommits {

    /** encodePacked 결과 길이. 테스트가 이 값으로 패킹을 고정한다. */
    static final int PACKED_LENGTH = 67;

    private static final Pattern BYTES32_HEX = Pattern.compile("^0x[0-9a-fA-F]{64}$");
    private static final BigInteger UINT256_MAX = BigInteger.TWO.pow(256).subtract(BigInteger.ONE);
    private static final int UINT16_MAX = 0xFFFF;

    private EventCommits() {}

    /** 컨트랙트 {@code Side} 값. UP=0 · DOWN=1 — enum 선언 순서에 기대지 않고 여기서 못박는다. */
    public static int sideOf(Prediction.Direction side) {
        return switch (side) {
            case UP -> 0;
            case DOWN -> 1;
        };
    }

    /** {@code prediction_events.id} 가 곧 온체인 eventId 라 보통은 long 이다. */
    public static byte[] commit(long eventId, int stockIdx, Prediction.Direction side, String noteHashHex) {
        return commit(BigInteger.valueOf(eventId), stockIdx, side, noteHashHex);
    }

    /** @return 32바이트 커밋. {@code placeBet(…, commit)} 인자이자 {@code prediction_event_bets.event_commit} 의 원본 */
    public static byte[] commit(BigInteger eventId, int stockIdx, Prediction.Direction side, String noteHashHex) {
        return Hash.sha3(pack(eventId, stockIdx, side, noteHashHex));
    }

    /** {@code 0x} + 소문자 64 hex. DB 컬럼({@code varchar(66)})에 들어가는 형식. */
    public static String commitHex(long eventId, int stockIdx, Prediction.Direction side, String noteHashHex) {
        return Numeric.toHexString(commit(eventId, stockIdx, side, noteHashHex));
    }

    /**
     * {@code abi.encodePacked} — 각 인자를 자기 타입 크기로만 쓰고 패딩·길이 접두사가 없다.
     * uint256 은 32바이트 빅엔디언, uint16 은 2바이트, uint8 은 1바이트, bytes32 는 그대로.
     */
    static byte[] pack(BigInteger eventId, int stockIdx, Prediction.Direction side, String noteHashHex) {
        if (eventId == null || eventId.signum() < 0 || eventId.compareTo(UINT256_MAX) > 0) {
            throw new IllegalArgumentException("eventId 는 0 이상 uint256 이하여야 한다: " + eventId);
        }
        if (stockIdx < 0 || stockIdx > UINT16_MAX) {
            throw new IllegalArgumentException("stockIdx 는 0 ~ 65535 여야 한다: " + stockIdx);
        }
        if (side == null) {
            throw new IllegalArgumentException("side 가 없다");
        }
        if (noteHashHex == null || !BYTES32_HEX.matcher(noteHashHex).matches()) {
            throw new IllegalArgumentException("noteHash 는 0x + 64 hex 여야 한다: " + noteHashHex);
        }
        byte[] out = new byte[PACKED_LENGTH];
        System.arraycopy(Numeric.toBytesPadded(eventId, 32), 0, out, 0, 32);
        out[32] = (byte) (stockIdx >>> 8);
        out[33] = (byte) stockIdx;
        out[34] = (byte) sideOf(side);
        System.arraycopy(Numeric.hexStringToByteArray(noteHashHex), 0, out, 35, 32);
        return out;
    }
}
