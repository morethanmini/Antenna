package ssafy.a507.backend.domain.chain.commit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.web3j.utils.Numeric;
import ssafy.a507.backend.domain.prediction.commit.CommitHashes;
import ssafy.a507.backend.domain.prediction.entity.Prediction.Direction;

/**
 * ANT-EVENT-02 — 자바 커밋이 컨트랙트 {@code keccak256(abi.encodePacked(uint256, uint16, uint8, bytes32))} 와 바이트 단위로 같은지.
 *
 * <p>기대값은 2026-09-16 Hardhat 에서 {@code ethers.solidityPackedKeccak256(['uint256','uint16','uint8','bytes32'], …)} 로 만들었고,
 * 같은 값으로 {@code placeBet} → {@code reveal} 이 실제 통과했다(픽스처 스크립트가 전 흐름을 돈다). 여기서 어긋나면 리빌이 전부
 * {@code BadCommit} 으로 revert 하고 배팅금은 환불로 빠진다.
 */
@DisplayName("이벤트 온체인 커밋")
class EventCommitsTest {

    @Test
    @DisplayName("Hardhat 실행 벡터 — eventId 9001 · 종목 0~2 · noteHash = keccak(utf8) 4건")
    void 실행_벡터() {
        // noteHash 는 예측 커밋의 CommitHashes.keccak256Hex 와 같은 함수라 그대로 재현된다.
        assertThat(EventCommits.commitHex(9001, 0, Direction.UP, CommitHashes.keccak256Hex("alice-0")))
                .isEqualTo("0x489bf1900ebee098f75749f1b8fa8c1b9d92778e54683f5ee7713d9d12a40f52");
        assertThat(EventCommits.commitHex(9001, 0, Direction.DOWN, CommitHashes.keccak256Hex("bob-0")))
                .isEqualTo("0x80bbfdd2c88f84dd7662ab7e1e01849aacaf1f0980dd61bf083dfb5fa7b3947c");
        assertThat(EventCommits.commitHex(9001, 1, Direction.UP, CommitHashes.keccak256Hex("carol-1")))
                .isEqualTo("0xc6359d4d677f9bac185f1af21388a05245ccdff0cfb0ddf661ab46f9be423bcb");
        assertThat(EventCommits.commitHex(9001, 2, Direction.DOWN, CommitHashes.keccak256Hex("alice-2")))
                .isEqualTo("0x558e3e7aa22f869e534fbc7d3abec31ee35e57e60ad528224cbda1e5bbcda483");
    }

    @Test
    @DisplayName("경계 벡터 — eventId 1 · idx 9 · DOWN, 그리고 eventId 2^128 · idx 65535 · noteHash 0xff…")
    void 경계_벡터() {
        assertThat(EventCommits.commitHex(1, 9, Direction.DOWN, CommitHashes.keccak256Hex("x")))
                .isEqualTo("0xf349f3f73b8d22810c125abf31184dd52bd245df61344c307d1048f057f82d5e");
        byte[] big =
                EventCommits.commit(
                        BigInteger.TWO.pow(128), 65535, Direction.UP, "0x" + "ff".repeat(32));
        assertThat(Numeric.toHexString(big)).isEqualTo("0xe564efdabe3af9ad184b1c31e876f12b33a4c7edb0b27eae11a03b3b311c99e8");
    }

    @Test
    @DisplayName("패킹은 67바이트 — uint256 32 · uint16 2 · uint8 1 · bytes32 32, 패딩·길이 접두사 없음")
    void 패킹_형태() {
        String note = "0x" + "ab".repeat(32);
        byte[] packed = EventCommits.pack(BigInteger.valueOf(9001), 258, Direction.DOWN, note);

        assertThat(packed).hasSize(EventCommits.PACKED_LENGTH);
        assertThat(Numeric.toHexString(packed))
                .isEqualTo("0x" + "00".repeat(30) + "2329" // 9001 = 0x2329, 빅엔디언 32바이트
                        + "0102" // 258 = 0x0102
                        + "01" // DOWN
                        + "ab".repeat(32));
        assertThat(EventCommits.sideOf(Direction.UP)).isZero();
        assertThat(EventCommits.sideOf(Direction.DOWN)).isEqualTo(1);
    }

    @Test
    @DisplayName("방향이 다르면 커밋이 다르다 — 그래야 리빌에서 UP/DOWN 바꿔치기가 막힌다")
    void 방향이_다르면_다르다() {
        String note = CommitHashes.keccak256Hex("n");
        assertThat(EventCommits.commitHex(1, 0, Direction.UP, note)).isNotEqualTo(EventCommits.commitHex(1, 0, Direction.DOWN, note));
        assertThat(EventCommits.commitHex(1, 0, Direction.UP, note)).isNotEqualTo(EventCommits.commitHex(1, 1, Direction.UP, note));
        assertThat(EventCommits.commitHex(1, 0, Direction.UP, note)).isNotEqualTo(EventCommits.commitHex(2, 0, Direction.UP, note));
    }

    @Test
    @DisplayName("인자 범위 — eventId 음수 · idx 65536 · noteHash 형식 오류는 IllegalArgumentException")
    void 인자_검증() {
        String note = "0x" + "00".repeat(32);
        assertThatThrownBy(() -> EventCommits.commit(-1, 0, Direction.UP, note)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventCommits.commit(1, 65536, Direction.UP, note)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventCommits.commit(1, -1, Direction.UP, note)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventCommits.commit(1, 0, null, note)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventCommits.commit(1, 0, Direction.UP, "00".repeat(32))).isInstanceOf(IllegalArgumentException.class); // 0x 없음
        assertThatThrownBy(() -> EventCommits.commit(1, 0, Direction.UP, "0x" + "00".repeat(31))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventCommits.commit(BigInteger.TWO.pow(256), 0, Direction.UP, note)).isInstanceOf(IllegalArgumentException.class);
    }
}
