# Cherry-pick 03 — `7c29456a9` feat(Frontend): implement 2-fetch (2-fetch 본체)

- **적용 커밋(local)**: `977ef629d`
- **분류**: core
- **변경 규모**: 16 files, +1484/−1147
- **선행**: `cfe100e29`(2-prefetch). **`806ccd8c5`(topdown)는 [[02-topdown-decision]]에 따라 스킵** → topdown 배선은 Path B로 적응.

## 충돌 4파일 / 해결 방식

| 파일 | hunks | 해결 |
|---|---|---|
| `frontend/Bundles.scala` | 1 (`FtqToIfuIO`) | 2-fetch 구조(fetch `req` 제거, `topdownInfo` 추가) 취함 + **`topdownRedirect` additive 유지**(구 IfuPerfAnalysis용) |
| `frontend/ftq/Ftq.scala` | 3 | ①pred-ready: 2-taken headroom 보존 + `ifuPtr`→`fetchPtr` ②`twoFetchInfoVec` 블록 제거(2-fetch가 `s3PerfQueue`로 대체) ③topdownStage/topdownInfo 유지, **`backendRedirectTopdown`/`backendRedirectOverride` 제거**(806ccd8c5 전용) |
| `frontend/ifu/PredChecker.scala` | 1 | HEAD 취함(`ParallelPriorityEncoder`+`Fill`) — incoming의 `prefixOr`는 미적용 `#5937` 의존, 기능 동일 |
| `frontend/ifu/Ifu.scala` | 17 | **전체 2-fetch 버전(theirs) 취함**(2-taken 미접촉, 보존할 내용 없음) + topdown 적응 |

## 추가 수동 정리 (orphan 제거 / 적응)
- **Ftq**: base 구 topdown 블록 `topdown_stage`→`io.toIfu.req.bits.topdownInfo`(req 제거로 orphan) 삭제. stale 주석 수정.
- **Ifu**: `import BackendRedirectTopdown` 제거, IO 입력 `backendRedirectTopdown` 제거,
  `perfAnalyzer.io.topdownIn.backendRedirectTopdown := io.backendRedirectTopdown`
  → `perfAnalyzer.io.topdownIn.topdownRedirect := io.fromFtq.topdownRedirect` (구 모델 환원).

## 정적 검증 (컴파일 전 스캔)
- ✅ 고아 `ifuPtr` 0 (2-fetch가 `fetchPtr`로 rename, 2-taken 신호 17개 보존)
- ✅ `realTwoFetchValid` ICache→Ftq 배선 정상, `fetchPtr` 2-block advance 정상
- ✅ FtqIO 구 모델(`bpuInfo`/`bpuTopDownInfo`) 유지 + 배선 일관 (Frontend→ibuffer/frontendInfo)
- ✅ `io.backendRedirectTopdown` 잔존 0 (Ftq/Ifu/Frontend 모두)
- ✅ Ifu↔IBuffer `topdownInfo`, Ifu↔ICache `req/topdown/perf` 인터페이스 일치
- ✅ Ifu IfuPerfAnalysis `topdownIn.topdownRedirect` 타입(`Valid[Redirect]`) 일치
- ✅ 충돌 마커 트리 전체 0

## 검증 (사용자 직접 빌드)
- [ ] `mill -i xiangshan.compile` — **미실행** (로컬 mill/java 없음)
- [ ] perf 측정 (마일스톤: 2-fetch 본체 적용 후)

## 잔여 리스크
- 2-fetch Ifu를 통째로 취했으므로, 미적용 갭 커밋(216개)이 도입한 **다른 헬퍼/필드 심볼**이 dangling일 가능성 잔존(현재까지 발견된 건 `backendRedirectTopdown` 하나, 적응 완료). 첫 컴파일에서 확인 필요.
- **topdown perf 카운터는 신뢰 불가**(Path B): MemVio/backend-redirect 분류 일부 손실. datapath 지표(IPC/MPKI)만 사용.

## perf 영향 의심점
- 이 커밋이 fetch 파이프라인을 2-fetch로 재구조화하는 본체. 이전 프로젝트 성능 하락의 **1순위 용의자**.
- 2-taken pair fire와 2-fetch의 `realTwoFetchValid` 2-block advance가 **FTQ 포인터에서 상호작용**(`fetchPtr += Mux(realTwoFetchValid, 2, 1)` vs pair enqueue headroom). 회귀 시 이 상호작용부터 점검.
