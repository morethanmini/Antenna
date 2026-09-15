package ssafy.a507.backend.domain.monetize.report;

import java.security.SecureRandom;
import java.util.HexFormat;
import ssafy.a507.backend.domain.monetize.entity.Report;
import ssafy.a507.backend.domain.prediction.commit.CommitHashes;

/**
 * 리포트 봉인 규격 (ANT-CHAIN-14). 예측 커밋({@code CommitPayload} · {@code CommitHashes})과 <b>같은 모양</b>을 쓴다.
 *
 * <pre>
 * antenna:report:v1
 * authorId=3
 * title=반도체 업황 점검
 * visibility=true
 * bodyHash=0x…
 * </pre>
 *
 * <p>두 겹인 이유가 예측과 같다.
 * <ul>
 *   <li>{@code bodyHash = keccak256(body ‖ salt)} — 본문을 가린 채로 "안 바뀌었다" 만 증명한다.
 *   <li>{@code reportHash = keccak256(위 문자열)} — 머클 트리의 리프. 제목·공개범위·작성자까지 함께 봉인한다.
 * </ul>
 * 그래서 <b>비구독자도 구독자 전용 리포트가 수정되지 않았음을 검증할 수 있다</b> — 본문 없이 {@code bodyHash} 만으로
 * 바깥 해시를 다시 조립하면 된다. 본문 열람과 무결성 검증이 갈리는 지점이 예측의 {@code noteHash} 와 정확히 같다.
 *
 * <p><b>salt 를 서버가 만든다 — 예측과 다른 점이다.</b> 예측의 {@code noteSalt} 는 클라이언트가 만드는데,
 * 커밋-리빌이 "등록 시점에 작성자조차 뒤집을 수 없다" 를 서명과 함께 보장해야 해서다. 리포트에는 서명도 리빌도 없고
 * 발행 즉시 내용이 (적어도 구독자에게) 공개된다. salt 가 하는 일은 하나뿐이다 — 구독자 전용 리포트의 짧은 제목·본문을
 * 비구독자가 후보 해시로 맞추지 못하게 막는 것. 그 일에는 서버 난수로 충분하고, {@code POST /reports} 요청 스키마를
 * 건드리지 않아도 된다.
 *
 * <p><b>{@code createdAt} 과 {@code reportId} 는 넣지 않는다.</b> 예측 커밋과 같은 이유다 — 서버가 정하는 값이라
 * 검증자가 재현할 수 없다. "언제 있었다" 는 앵커 블록이 증명한다. id 가 없어도 충돌하지 않는다: salt 가 리포트마다
 * 달라 {@code bodyHash} 가 갈리고, 그 값이 바깥 문자열에 들어간다.
 */
public record ReportSeal(String salt, String bodyHash, String reportHash) {

    public static final String HEADER = "antenna:report:v1";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SALT_BYTES = 32;

    /** 발행 시점에 부른다. {@code report} 는 id 가 없어도 된다 — 해시 재료에 id 가 들어가지 않는다. */
    public static ReportSeal of(Report report, String body) {
        String salt = randomSalt();
        String bodyHash = CommitHashes.noteHash(body == null ? "" : body, salt);
        return new ReportSeal(salt, bodyHash, CommitHashes.keccak256Hex(canonical(report, bodyHash)));
    }

    /**
     * 해시 대상 문자열. 구분은 {@code \n} 하나, 마지막 줄 뒤 개행 없음, UTF-8.
     *
     * <p>본문은 여기 없다 — {@code bodyHash} 로만 들어간다. 제목은 그대로 들어간다: 목록이 미구독자에게도 제목을
     * 보여 주므로(설계 §5) 가릴 대상이 아니고, 가리면 "제목만 바꿔치기" 를 못 잡는다.
     */
    public static String canonical(Report report, String bodyHash) {
        return HEADER
                + "\nauthorId=" + report.getUser().getId()
                + "\ntitle=" + report.getTitle()
                + "\nvisibility=" + report.isPublic()
                + "\nbodyHash=" + bodyHash;
    }

    private static String randomSalt() {
        byte[] bytes = new byte[SALT_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
