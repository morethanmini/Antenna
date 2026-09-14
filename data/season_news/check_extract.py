"""extract_candidates.py 의 게임일 경계·이름 매칭·정리 규칙 확인. 실패하면 AssertionError.

    python3 check_extract.py
"""
import re
from datetime import datetime

import extract_candidates as ex
import select_news as sn

# 게임일: 전 거래일 15:30 초과 ~ 그 거래일 15:30 이하
cut = [datetime(2020, 2, 14, 15, 30), datetime(2020, 2, 17, 15, 30), datetime(2020, 2, 18, 15, 30)]
assert ex.game_day(cut, datetime(2020, 2, 14, 15, 30)) is None, "워밍업 마지막 날 마감까지는 시즌 밖"
assert ex.game_day(cut, datetime(2020, 2, 14, 15, 31)) == 1, "금요일 마감 뒤 기사는 월요일(1일차)"
assert ex.game_day(cut, datetime(2020, 2, 16, 9, 0)) == 1, "주말 기사는 다음 거래일"
assert ex.game_day(cut, datetime(2020, 2, 17, 15, 30)) == 1, "그날 마감 시각까지는 그날"
assert ex.game_day(cut, datetime(2020, 2, 17, 15, 31)) == 2, "마감 뒤는 다음 날 — 그날 종가로 체결되므로"
assert ex.game_day(cut, datetime(2020, 2, 18, 15, 31)) is None, "마지막 날 마감 뒤는 시즌 밖"

# 발행 시각 4형식
assert ex.published_at("2020-03-12 오후 3:05:00") == datetime(2020, 3, 12, 15, 5)
assert ex.published_at("2020-03-12 오전 12:10:00") == datetime(2020, 3, 12, 0, 10)
assert ex.published_at("2020.03.12 09:01") == datetime(2020, 3, 12, 9, 1)
assert ex.published_at("2023-01-16 07:00:12") == datetime(2023, 1, 16, 7, 0)

# 이름 매칭
names = {n: (c, n) for c, n in [("000270", "기아"), ("004000", "효성"), ("001680", "대상"), ("078930", "GS"),
                                ("030200", "KT"), ("033780", "KT&G"), ("034730", "SK"), ("017670", "SK텔레콤"),
                                ("011200", "HMM")]}
names["SKT"] = names["SK텔레콤"]
names["현대상선"] = names["HMM"]
season = dict(names=names, pattern=re.compile("|".join(re.escape(n) for n in sorted(names, key=len, reverse=True))))
hits = lambda title: [t for _, t, _ in ex.match_tickers(season, title)]
assert hits("김기현 “탄핵 얘기 아냐”") == [], "띄어쓰기를 지우지 않는다"
assert hits("자가격리 실효성 논란") == [], "이름 앞이 한글이면 버린다"
assert hits("<코>GST, 3.22% 오르며") == [], "영문 이름 뒤가 영문자면 버린다"
assert hits("KTX 호남선 증편") == []
assert hits("현대상선, 장중 신저가") == ["HMM"], "옛 사명(별칭)은 긴 이름이 먼저 — '대상' 으로 읽지 않는다"
assert hits("SKT, 중간요금제 개편") == ["SK텔레콤"], "별칭이 짧은 이름보다 먼저"
assert hits("KT, 포스코·KT&G도 좌불안석") == ["KT", "KT&G"]
assert hits("현대차·기아, 탄소감축 협약") == ["기아"]
assert hits("입국자 대상 특별절차") == ["대상"], "흔한 낱말은 여기서 못 거른다 — GPU 판정 몫"

# 정리
assert ex.clean("#039;전 키움#039; 웰스") == "'전 키움' 웰스"
assert ex.clean("도요타 임원 amp;quot;투자amp;quot;") == '도요타 임원 "투자"'
assert ex.clean("S&amp;P 500") == "S&P 500"
# 가림(select_news)
assert sn.mask_dates("2020년 3월 15일 밝혔다") == "○○○○년 ○월 ○일 밝혔다"
assert sn.mask_dates("13일로 예정된 재운영") == "○일로 예정된 재운영", "날짜 뒤 조사"
assert sn.mask_dates("지난 13일 재선임") == "지난 ○일 재선임"
assert sn.mask_dates("3월 결산 법인") == "○월 결산 법인"
assert sn.mask_dates("일본 증시, 일부 종목") == "일본 증시, 일부 종목", "숫자 없는 '일' 은 그대로"
assert sn.clean_excerpt("(서울=연합뉴스) 김기훈 기자 = 현대자동차는 2020년 3월 15일 밝혔다.", "연합뉴스") == "현대자동차는 ○○○○년 ○월 ○일 밝혔다."
assert "재무분석차트영역상세보기" not in sn.clean_excerpt("KT(030200) 재무분석차트영역상세보기가 세계은행과", "서울경제")
same = lambda a, b: len(sn.grams(a) & sn.grams(b)) / len(sn.grams(a) | sn.grams(b)) >= sn.SIMILAR
assert same("삼성전자 장중 5만원 붕괴...현대차 10만원 밑돌아", "삼성전자 장중 5만원선 붕괴…외국인 매도 ‘집중‘")
assert not same("삼성전자 등 314곳, 다음주 정기주총 개최", "삼성전자, 지난해 웨어러블 기기 출하량 3위…한 계단 상승")
assert sn.mask_dates("‘인터배터리 2023’ 참가") == "‘인터배터리 ○○○○’ 참가", "행사 이름 속 연도"
assert sn.mask_dates("[2023서울모빌리티쇼]") == "[○○○○서울모빌리티쇼]"
assert sn.mask_dates("2021학년도부터 신설") == "○○○○학년도부터 신설"
assert sn.mask_dates("8만 2000원으로 상향") == "8만 2000원으로 상향", "금액은 그대로"
assert sn.mask_dates("자기자본(1956억원) 대비") == "자기자본(1956억원) 대비"
assert sn.mask_dates("대리점 2000곳") == "대리점 2000곳"
assert sn.PROMO.search("[필수] 삼성전자 지금이 기회! AI+5G+핵심반도체 삼성도 투자한 1200% 급등株")
assert not sn.PROMO.search("[이번주 추천주] 이마트·LG이노텍·네이버 “실적 호전 보라“"), "증권사 추천 기사는 광고가 아니다"
print("ok")
