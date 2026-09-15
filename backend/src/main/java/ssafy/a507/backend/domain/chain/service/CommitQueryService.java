package ssafy.a507.backend.domain.chain.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.domain.chain.anchor.AnchorLeaves;
import ssafy.a507.backend.domain.chain.dto.CommitItemResponse;
import ssafy.a507.backend.domain.chain.dto.CommitListResponse;
import ssafy.a507.backend.domain.monetize.entity.Report;
import ssafy.a507.backend.domain.monetize.repository.ReportRepository;
import ssafy.a507.backend.domain.prediction.entity.PredictionCommit;
import ssafy.a507.backend.domain.prediction.repository.PredictionCommitRepository;

/**
 * 커밋 원장 목록 (화면 D-01).
 *
 * <p>{@link AnchorQueryService} 와 나눈 이유 — 저쪽은 <b>배치</b>를 다루고 여기는 <b>봉인 한 건</b>을 다룬다.
 * 배치 상세({@code GET /anchors/{id}})는 그대로 남는다 — 머클루트를 재현하는 검증용 뷰라 성격이 다르다.
 *
 * <p><b>예측 커밋과 리포트를 한 목록으로 내린다 (ANT-CHAIN-15).</b> 2026-09-15 부터 둘이 같은 머클 트리에
 * 올라가는데(ANT-CHAIN-14) 원장은 예측만 보여 주고 있었다 — 체인에 올라간 것의 절반만 보여 주는 원장은
 * 이 서비스가 하는 주장을 스스로 깎는다.
 *
 * <p>읽기만 한다. 게이팅이 없는 것도 앵커 조회와 같다(결정 F2·F3) — 해시·번호·종목·제목으로는 예측 근거도
 * 리포트 본문도 알 수 없고, 내용 공개 여부는 {@code GET /predictions/{id}/proof} 와 {@code GET /reports/{id}} 가 판단한다.
 *
 * <h2>왜 두 번 읽어 메모리에서 합치나</h2>
 *
 * <p>두 표의 UNION 을 DB 에서 정렬하려면 네이티브 질의가 되고, 그러면 두 엔티티의 연관 fetch 를 손으로 다시
 * 짜야 한다. 한 장이 최대 100건이라 표마다 {@code size + 1} 건씩 읽어 합치는 편이 짧고, 페이지 경계 판정도
 * 이 클래스 안에서 끝난다. 원장이 커져 이 방식이 버거워지면 그때 뷰나 네이티브 질의로 바꾼다.
 */
@Service
@RequiredArgsConstructor
public class CommitQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 목록 정렬: 발행 시각 내림차순 → 같은 시각이면 <b>예측이 리포트보다 앞</b> → 그 안에서 id 내림차순.
     *
     * <p>가운데 규칙은 임의로 정한 것이다. 두 표의 id 는 다른 시퀀스라 비교할 뜻이 없어, 같은 시각이 겹쳤을 때
     * 순서를 결정할 무언가가 필요하다. <b>이 순서가 곧 커서의 의미</b>이므로 레포지토리 두 곳의 커서 조건이
     * 여기에 맞춰져 있다 — 바꾸면 페이지 경계에서 줄이 빠지거나 겹친다.
     */
    private static final Comparator<Row> ORDER = Comparator.comparing(Row::createdAt, Comparator.reverseOrder())
            .thenComparingInt(row -> row.kind() == AnchorLeaves.Kind.PREDICTION ? 0 : 1)
            .thenComparing(Row::id, Comparator.reverseOrder());

    private final PredictionCommitRepository commits;
    private final ReportRepository reports;

    /** 정렬 키를 들고 다니는 한 줄. {@code item} 이 응답이고 나머지 셋이 커서 재료다. */
    private record Row(Instant createdAt, AnchorLeaves.Kind kind, long id, CommitItemResponse item) {}

    /**
     * 최신 봉인부터. 커서는 마지막으로 받은 줄의 {@code "<epochMicros>:<kind>:<id>"} 이고, 클라이언트는 받은 값을
     * 그대로 되돌려주기만 한다(리포트 피드와 같은 계약).
     *
     * <p>아직 배치에 묶이지 않은 것도 포함한다 — 봉인은 끝났고 다음 배치를 기다리는 중이라, 빼면 방금 등록한
     * 사람에게 "봉인했는데 원장에 없다" 로 보인다. 그 줄은 배치 자리가 비어 온다.
     */
    @Transactional(readOnly = true)
    public CommitListResponse list(String cursor, Integer size) {
        int pageSize = pageSize(size);
        LedgerCursor after = LedgerCursor.parse(cursor);
        Limit limit = Limit.of(pageSize + 1);

        List<Row> rows = new ArrayList<>();
        for (PredictionCommit c :
                commits.findLedgerPage(after.at(), after.idBoundOf(AnchorLeaves.Kind.PREDICTION), limit)) {
            rows.add(new Row(
                    c.getPrediction().getCreatedAt(),
                    AnchorLeaves.Kind.PREDICTION,
                    c.getPredictionId(),
                    CommitItemResponse.of(c)));
        }
        for (Report r : reports.findLedgerPage(after.at(), after.idBoundOf(AnchorLeaves.Kind.REPORT), limit)) {
            rows.add(new Row(r.getCreatedAt(), AnchorLeaves.Kind.REPORT, r.getId(), CommitItemResponse.of(r)));
        }
        rows.sort(ORDER);

        /* 두 표에서 각각 size + 1 건까지 읽었으므로, 합친 것이 size 를 넘으면 다음 장이 확실히 있다.
           반대로 넘지 않으면 두 질의가 남은 것을 전부 가져왔다는 뜻이라 다음 장이 없다. */
        boolean hasNext = rows.size() > pageSize;
        List<Row> page = hasNext ? rows.subList(0, pageSize) : rows;
        if (page.isEmpty()) {
            return new CommitListResponse(List.of(), null, false);
        }
        Row last = page.get(page.size() - 1);
        return new CommitListResponse(
                page.stream().map(Row::item).toList(),
                hasNext ? LedgerCursor.encode(last.createdAt(), last.kind(), last.id()) : null,
                hasNext);
    }

    private int pageSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * 원장 커서 {@code "<epochMicros>:<kind>:<id>"}.
     *
     * <p>문자열 커서 선례가 {@code GET /reports?sort=POPULAR} 의 {@code "<viewCount>:<id>"} 다. 한 목록이 두 표에서
     * 오므로 id 하나로는 경계를 정할 수 없고, 정렬 키 셋을 그대로 실어야 한다.
     *
     * <p><b>마이크로초로 자른다.</b> Postgres 의 {@code timestamp(6)} 과 같은 정밀도라 DB 에서 읽은 값을 그대로
     * 되돌려보내면 {@code createdAt = :at} 비교가 성립한다. 나노초까지 실으면 DB 에 없는 자리를 비교하게 된다.
     */
    private record LedgerCursor(Instant at, AnchorLeaves.Kind kind, Long id) {

        private static final String SEPARATOR = ":";

        /**
         * 커서가 없을 때 쓰는 시각. 모든 행이 이보다 앞서므로 첫 장이 전부 들어온다.
         *
         * <p>null 을 넣지 않는 이유가 여기 있다 — PostgreSQL 은 {@code :at is null} 의 파라미터 타입을
         * 정하지 못해 질의를 거절한다. H2 는 통과시켜서 이 사고가 운영에서만 났다(2026-09-15).
         */
        private static final Instant FAR_FUTURE = Instant.parse("9999-12-31T23:59:59Z");

        private static final LedgerCursor NONE = new LedgerCursor(FAR_FUTURE, null, null);

        static String encode(Instant at, AnchorLeaves.Kind kind, long id) {
            long micros = at.getEpochSecond() * 1_000_000L + at.getNano() / 1_000L;
            return micros + SEPARATOR + kind.name() + SEPARATOR + id;
        }

        static LedgerCursor parse(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return NONE;
            }
            String[] parts = cursor.trim().split(SEPARATOR);
            if (parts.length != 3) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "cursor");
            }
            try {
                long micros = Long.parseLong(parts[0]);
                return new LedgerCursor(
                        Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L),
                        AnchorLeaves.Kind.valueOf(parts[1]),
                        Long.parseLong(parts[2]));
            } catch (IllegalArgumentException e) {
                // 숫자가 아니거나 없는 kind. 손으로 만든 커서이므로 400 이다.
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "cursor");
            }
        }

        /**
         * 이 종류의 표에 넘길 id 경계. <b>null 을 돌려주지 않는다</b> — 질의가 {@code :cursorId is null} 을
         * 쓰지 않도록, 같은 뜻의 경계값으로 바꿔 넣는다.
         *
         * <ul>
         *   <li>커서가 이 종류를 가리키면 그 id — 같은 시각 안에서 그보다 작은 것만 다음 장이다.
         *   <li>커서가 <b>다른</b> 종류를 가리키면, 같은 시각의 이 종류가 앞 장에서 나갔는지로 갈린다.
         *       정렬이 "예측 먼저" 이므로 예측은 이미 나갔고(→ {@code MIN_VALUE}, 아무것도 안 들어온다)
         *       리포트는 아직 안 나갔다(→ {@code MAX_VALUE}, 전부 들어온다).
         *   <li>커서 자체가 없으면 {@code at} 이 먼 미래라 이 값은 쓰이지 않는다.
         * </ul>
         */
        Long idBoundOf(AnchorLeaves.Kind wanted) {
            if (kind == wanted) {
                return id;
            }
            return wanted == AnchorLeaves.Kind.PREDICTION ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }
}
