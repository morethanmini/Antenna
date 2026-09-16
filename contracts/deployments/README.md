# 배포 기록 — 운영 한 벌(SSAFY) · 팀원 로컬은 로컬 체인

SSAFY 체인(chainId 31221, `wss://ws.ssafy-blockchain.com`, 가스 0)에는 **운영 한 벌만** 쓴다. 실서버 테스트와 서비스가 이 세트다.
**팀원 로컬 서버의 기본은 로컬 체인이다**(2026-09-14, ANT-CHAIN-15) — `docker compose up -d` 가 Hardhat 체인을 띄우고 배포까지 한다(아래 "로컬 체인").
로컬 서버를 운영 세트에 붙이는 건 운영 릴레이어 키 보유자(김성령)만이다.

역할(`ANCHOR_ROLE` · `OPERATOR_ROLE`)은 컨트랙트마다 **특정 주소**에 주어진다. 그래서 키와 컨트랙트는 한 벌로 움직인다.
운영 릴레이어 키는 저장소에 두지 않고 팀원에게 나누지 않는다.

**이 파일이 단일 진실이다.** 주소를 바꾸면 여기와 "넣는 곳" 을 같이 바꾼다.

> 결정 경위. 09-11 오후 개발/운영 두 벌 → 저녁 "세트는 하나, 로컬은 체인 밖(Hardhat)" → 09-12 "로컬도 운영 세트" →
> **09-14 "운영 세트는 키 보유자만, 팀원은 로컬 체인"**(ANT-CHAIN-15). 09-12 방식은 서버마다 자기 DB 로 1인 1회를 판정해,
> 운영 키를 여럿이 쥐면 가입 보너스가 중복으로 나가고 nonce 가 부딪힌다(ANT-TOKEN-07 검토). 로컬 체인은 각자 따로라 둘 다 없다.
> 운영 세트로 로컬을 쓰는 사람은 로컬 서버의 `ANCHOR_CRON` 을 끄고 `BONUS_RETRY_CRON` 을 운영과 비킨다(`backend/.env.example` 체인 절 맨 아래).

## 운영 (prod) — SSAFY 체인

| 항목 | 값 | 보관 · 넣는 곳 |
|---|---|---|
| CommitAnchor **v3** | `0x7c661F80EA46Ff7d63ab7F5Bb59d8d66E256DF99` (블록 11244276, 2026-09-11) | 장부 칸의 키 = 머클루트(ANT-CHAIN-13). 옛 v2 `0x8fDb…1C7e` 는 한 번도 안 쓴 채 교체 — 기록은 `prod/archive/CommitAnchor.v2.json` |
| PredictToken (ANT) | `0xEee56721cd2c0383139756c88B6DB06024a412Cb` (블록 11241835) | **서비스 ANT. 재배포 금지 — 잔액이 여기 산다** |
| PredictionEventPool | `0xc0cD9D8a7A29B0D6358CbB6acE6BD59b6E9F12aa` (블록 11288437, 2026-09-16) | 배팅 이벤트 돈통(ANT-EVENT-01). **진행 중인 회차의 배팅금이 여기 산다 — 재배포 금지.** 회차가 늘어도 `eventId` 만 는다 |
| ↳ 토큰 `MOVER_ROLE` 부여 | tx `0x7363acad7f1f0cc0bd56a83e5b09308549061a841b03cb47d4b855f7b0fcc111` (블록 11288438) | 풀이 사용자 ANT 를 옮기려면 필요하다. 이게 없으면 `placeBet` 이 전부 revert. `prod/PredictionEventPool.json` 의 `moverGranted` 는 **배포 시점 값(false)** 이라 여기가 진실이다 |
| 관리자 (`DEFAULT_ADMIN_ROLE`, 세 컨트랙트 공통) | `0xed9A22D3c39e5ddf161638b82a18a96134707491` | 비밀번호 관리자. 서버·저장소에 없다 |
| 릴레이어 = 오퍼레이터 (`ANCHOR_ROLE` · 토큰 `OPERATOR_ROLE` · 풀 `OPERATOR_ROLE`) | `0x55085F3F5568ED3936A734DbaEF179F8045322E3` | GitLab CI/CD 변수 `RELAYER_PRIVATE_KEY` (Masked · Protected) |
| 수납 (플랫폼 30%) | `0x1E189420b1Abb9F1ff3A556bd068C5B03b6B17bB` | 비밀번호 관리자 |
| 인덱서 시작 블록 | 11241835 (두 배포 블록 중 작은 값 = PredictToken) | — |
| 주소를 넣는 곳 | `.gitlab-ci.yml` 의 `.env` 블록 | — |

- **테스트 흔적은 체인에 영구히 남는다.** 서비스 화면에서 걸러 보여 준다. 서비스 시작 때의 정리 방식(테스트 잔액을 오퍼레이터 `burn` 으로 소각 ·
  DB 초기화 vs 기준 시점 필터)은 보류다.
- 체인은 RPC 로 누구나 읽을 수 있다. "안 보여 준다" 는 화면 이야기지 비밀이 아니다. 앵커에는 해시만 올라가 예측 내용은 안 읽힌다.

## 로컬 체인 — 팀원 로컬 서버의 기본 (ANT-CHAIN-15)

**`docker compose up -d` 한 번이면 된다.** `docker-compose.override.yml` 의 `chain` 컨테이너가 Hardhat 노드(chainId 31337, 8545)를 띄우고
아래 표의 주소로 배포까지 한다. `backend/.env.example` · `frontend/.env.example` 의 체인 기본값이 이 체인이라 복사만 하면 붙는다.
override 파일은 운영 CI(`-f docker-compose.yml -f docker-compose.prod.yml`)가 읽지 않는다 — 운영 서버에는 안 뜬다.

**체인은 컨테이너가 다시 뜰 때마다 새로 생긴다**(Hardhat 은 메모리 체인). 그때 컨테이너가 알아서 맞춘다(`scripts/localchain-sync.mjs`):

1. 컨트랙트 배포 — 새 노드라 주소는 늘 표와 같다
2. 로컬 DB 의 체인 사본(`chain_events` · `token_ledger` · `operations`)을 비운다 — 회원·지갑 연동·예측·앵커 배치·시세는 그대로.
   연동 회원의 가입 보너스는 서버의 재시도 배치가 새 체인에 다시 준다. 로컬 잔액 이력은 사라진다(잔액은 보너스 1만으로 돌아간다)
3. 확정(CONFIRMED)된 로컬 앵커 배치의 루트를 새 체인에 다시 앵커한다 — 리프 순서는 prediction id 오름차순(서버와 같다).
   tx·블록 참조는 백엔드 인덱서가 새 값으로 고친다 → 예전 로컬 예측의 검증 화면이 그대로 산다

- **2·3 은 `backend/.env` 가 이 체인(CHAIN_ID 31337 + 아래 CommitAnchor 주소)을 볼 때만 한다.** 운영 세트로 로컬을 쓰는 사람의 DB 에는
  운영 체인 사본이 있다 — 그 DB 는 건드리지 않고 배포만 한다.
- ⚠️ chain 컨테이너가 다시 떴으면 **백엔드도 재시작**한다. 인덱서가 옛 체인의 블록 높이를 메모리에 들고 있어 새 체인 이벤트를 못 읽는다
  (백엔드가 떠 있으면 컨테이너 로그에 경고가 찍힌다).
- 지갑 연동은 체인과 무관하다(DB + 서명). 한 번 연동하면 체인이 새로 생겨도 그대로다. 메타마스크 네트워크도 안 바꿔도 된다.
- 로그: `docker compose logs -f chain`. 손으로 다시 맞추기: `docker compose exec chain npm run localchain:sync`(이미 배포된 노드면 할 일 없음).
- 컨트랙트(.sol)나 스크립트를 고치면 이미지를 다시 굽는다: `docker compose up -d --build chain`.

컨테이너 없이 손으로 띄울 때(컨트랙트 개발):

```bash
cd contracts
npx hardhat node                          # 터미널 1 — chainId 31337, 계정 20개(키가 화면에 찍힌다. 공개된 테스트 키다)
npm run localchain:sync                   # 터미널 2 — 배포 + (backend/.env 가 이 체인을 보면) 로컬 DB 맞추기. 배포만: npm run deploy:local
MINT_TO=0x내지갑 npm run mint:local        # 잔액이 더 필요할 때 — 기본 10000 ANT, reason SIGNUP_BONUS, localhost 가 아니면 거부
```

chain 컨테이너가 8545 를 쥐고 있으면 손으로 띄운 노드는 못 뜬다 — `docker compose stop chain` 먼저.

| 역할 | Hardhat 계정 | 주소 |
|---|---|---|
| 관리자(배포자) | #0 | `0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266` |
| 릴레이어 — CommitAnchor `ANCHOR_ROLE` · PredictToken `OPERATOR_ROLE` | #1 | `0x70997970C51812dc3A010C7d01b50e0d17dc79C8` |
| 수납(구독 30%) | #2 | `0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC` |
| CommitAnchor v3 | #0 nonce 0 | `0x5FbDB2315678afecb367f032d93F642f64180aa3` |
| PredictToken | #0 nonce 1 | `0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512` |
| PredictionEventPool (ANT-EVENT-01) | #0 nonce 2 | `0x9fE46736679d2D9a65F0992F2272dE9f3c7fa6e0` — `deploy:local` 이 MOVER_ROLE 까지 준다 |

**새 노드에서 배포하면 두 주소가 늘 같다**(ANT-CHAIN-14) — 서버를 붙일 때 주소를 매번 찾지 않아도 된다.
`deploy.js` 의 배포 순서(CommitAnchor → PredictToken)를 바꾸면 주소가 바뀐다. 릴레이어 #1 의 키는 `npx hardhat node` 가 화면에 찍는다(공개 테스트 키).

- **노드를 재시작하면 체인이 새로 생긴다** — 다시 `deploy:local` 하고, 로컬 DB 의 옛 체인 기록을 비운다. 안 비우면 인덱서가 마지막 이벤트의 블록 해시가
  체인과 다르다며 **정지**한다(reorg 로 본다). `chain_events` 는 DELETE 가 트리거로 막혀 있지만 `TRUNCATE` 는 행 트리거를 타지 않는다:
  `docker exec antenna-postgres psql -U antenna -d antenna -c "TRUNCATE chain_events CASCADE"` (참조하는 `token_ledger` 도 같이 비워진다 — 옛 체인 잔액이라 맞다).
- `hardhat.config.js` 가 base fee 를 0 으로 둔다(`initialBaseFeePerGas: 0`, ANT-CHAIN-13) — 서버는 SSAFY 규칙대로 `gasPrice 0` 으로 서명하므로
  이게 없으면 로컬 노드가 서버 tx 를 전부 거부한다.
- 노드를 끌 땐 창을 닫거나 Ctrl+C — 셸만 죽이면 node 프로세스가 8545 를 쥔 채 남는다.

## 폐기 — 옛 dev 배포본 (2026-09-11 저녁)

| 항목 | 값 |
|---|---|
| CommitAnchor v2 | `0x07f8CfE2bc6174D62BE8226E5e8699ffBf0d6D6a` (batchId 1~4 소모) |
| PredictToken | `0xe11d728b157240DCf4a8c831176D248BAFD33077` (테스트 mint 흔적) |
| 관리자 · 릴레이어 · 수납 | `0x22B5…DEA1` · `0xb7f2…2a47` · `0x893D…7fb3` |

기록(`dev/*.json`)과 흔적표(`contracts/README.md`)는 역사로 남긴다. 새로 쓰지 않는다 — `DEPLOY_ENV=dev` 배포도 하지 않는다.

## 폴더

```
deployments/
├── prod/    CommitAnchor.json(v3) · PredictToken.json · PredictionEventPool.json   ← 운영
│   └── archive/  CommitAnchor.v2.json                 ← 교체된 옛 기록(안 씀)
├── local/   CommitAnchor.json · PredictToken.json   ← Hardhat (npm run deploy:local) — 추적 안 함(deployments/.gitignore). 주소는 위 표
└── dev/     CommitAnchor.json · PredictToken.json   ← 폐기(역사)
```

- 각 JSON 의 `environment` 필드와 폴더 이름이 같다.
- SSAFY 배포 스크립트는 `DEPLOY_ENV` 가 없으면 tx 를 보내기 전에 멈춘다.
- `prod/` 에 기록이 있으면 `DEPLOY_ENV=prod` 배포도 멈춘다 — 잔액(PredictToken)과 운영 앵커 기록(CommitAnchor)이 그 주소에 묶여 있다.

## 짝이 맞는지 확인하는 법

- **서버 부팅 로그** (`ChainRoleCheck`) — 키·RPC·주소가 다 있으면 부팅 직후 한 번:
  ```
  체인 환경 확인 — 릴레이어 0x5508…
    CommitAnchor 0x7c66… ANCHOR_ROLE ✓
    PredictToken 0xeee5… OPERATOR_ROLE ✓
  ```
  ✗ 가 하나라도 있으면 ERROR 로 뜬다. 부팅은 막지 않는다.
- **실체인 테스트** `ChainRoleCheckE2ELiveTest` — env 의 키·주소 짝이 ✓ 인지(Hardhat 로컬 체인에도 돌릴 수 있다).

## prod 를 만든 절차 (2026-09-11, 한 번 돌렸다)

```bash
cd contracts && npm ci && npx hardhat compile
# 키 3개 — 주소만 화면에, 비밀키는 저장소 밖 파일로
node scripts/keygen.mjs --out <저장소 밖>/prod-keys.env prod-admin prod-relayer prod-treasury
set -a; . <저장소 밖>/prod-keys.env; set +a
DEPLOY_ENV=prod ANCHOR_ADMIN_PRIVATE_KEY=$PROD_ADMIN_PRIVATE_KEY ANCHOR_RELAYER=$PROD_RELAYER_ADDRESS npm run deploy:ssafy
DEPLOY_ENV=prod ANCHOR_ADMIN_PRIVATE_KEY=$PROD_ADMIN_PRIVATE_KEY TOKEN_OPERATOR=$PROD_RELAYER_ADDRESS \
  TOKEN_TREASURY=$PROD_TREASURY_ADDRESS npm run deploy:token:ssafy
```

그 뒤: 관리자·수납 키 → 비밀번호 관리자, 릴레이어 키 → GitLab CI/CD 변수, 키 파일 삭제.

## 운영 컨트랙트를 바꿔야 할 때

| 상황 | 할 일 |
|---|---|
| **릴레이어 키가 샜다** | 재배포하지 않는다. 운영 관리자 키로 두 컨트랙트에 `revokeRole(옛 주소)` + `grantRole(새 주소)` → GitLab 변수 교체 → 서버 재배포 |
| **관리자 키가 샜다** | 공격자가 역할을 가져갈 수 있다. 재배포밖에 없다 — 아래 두 줄 |
| **CommitAnchor 를 바꿔야 한다** | 옛 앵커는 옛 주소에 남는다(`anchor_batches.contract_address` 가 배치별 주소를 든다). `prod/CommitAnchor.json` 을 `prod/archive/` 로 옮긴 뒤 배포 |
| **PredictToken 을 바꿔야 한다** | 재배포가 아니라 v2 이관(`contracts/README.md` 함정 5). 잔액이 옛 주소에 산다 |

어느 경우든 이 표 · `.gitlab-ci.yml` · API 명세 §4 를 같이 바꾼다.
