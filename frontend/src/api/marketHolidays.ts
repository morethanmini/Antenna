/* 증시 휴장일 (ANT-PRED-12). 주말이 아닌데 장이 안 서는 날이다.

   **서버 MarketHolidays.java 와 같은 목록이다 — 둘을 같이 고친다.** 등록 화면이 기간 고르개 옆에 만기일을
   미리 적는데(PredictTab dueLabel), 서버와 다르게 세면 등록 전에 본 만기와 등록 뒤의 만기가 달라진다.

   이 목록이 틀려도 판정은 틀리지 않는다 — 서버 판정 배치는 달력이 아니라 시세를 본다(만기일 이후 첫 거래일
   종가). 여기가 정하는 것은 화면에 미리 적는 날짜뿐이다. 해가 바뀌면 양쪽에 한 줄씩 더한다. */
const CLOSED = new Set([
  '2026-09-24', // 추석 연휴
  '2026-09-25', // 추석 (9/28 은 개장 — 추석은 일요일과 겹칠 때만 대체휴일)
  '2026-10-05', // 개천절(10/3 토) 대체공휴일
  '2026-10-09', // 한글날
  '2026-12-25', // 성탄절
  '2026-12-31', // 연말 휴장
])

const pad = (n: number) => String(n).padStart(2, '0')

/** 장이 서는 날인가 — 주말도 휴장일도 아니다. 로컬(KST) 날짜로 본다 */
export function isTradingDay(d: Date) {
  const dow = d.getDay()
  if (dow === 0 || dow === 6) return false
  return !CLOSED.has(`${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`)
}
