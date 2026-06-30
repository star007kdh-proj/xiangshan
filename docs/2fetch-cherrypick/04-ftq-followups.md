# Cherry-pick 04 — ftq follow-up fixes (4개)

2-fetch 관련 ftq redirect/train 경로 보정 커밋 4개. 시간순으로 적용.
3개(`7be11a171`, `a28cd38ff`, `3a49b46e0`)는 core의 조상이라 core diff에 없던 부분을 net으로 채우고,
`637f62a88`은 core 이후 fix.

| 순서 | commit | local | 설명 | 충돌 |
|---|--------|-------|------|------|
| 1 | 7be11a171 | 47df34d34 | fix(ftq): flush train cache with redirect | Ftq 1 (수동 합성) |
| 2 | a28cd38ff | 3d2767184 | fix(ftq): remove bypass from redirect to prefetch | 없음(auto) |
| 3 | 3a49b46e0 | 99618d92a | feat(ftq): read queue w/ redirect FTQ idx 1cyc ahead | Ftq 1 |
| 4 | 637f62a88 | 337f6ec52 | fix(ftq): fix train cache flush condition | 없음(auto) |

## 1. `7be11a171` — train cache + 2-taken pair-second 합성 (핵심)
- incoming: BPU train 경로에 레지스터 단(`trainCache`) + redirect flush 도입.
- HEAD(2-taken): 직접 경로에 **pair-second train 억제**(`isPairSecond` slot은 stale meta라 학습 안 함).
- **합성**: pair-second 엔트리를 cache에서 "소비된 것처럼 drop"(train 미생성, resolveQueue는 정상 dequeue).
  - `cachedIsPairSecond = if (EnableTwoTaken) isPairSecond(trainIndexCache.value) else false.B`
  - `ready := !trainCache.valid || io.toBpu.train.fire || cachedIsPairSecond`
  - clear 조건에 `|| cachedIsPairSecond` 추가, output `valid := ... && !cachedIsPairSecond`
- 추가: `import xiangshan.frontend.bpu.BpuTrain` (우리 base에 없던 import — 갭 커밋이 도입했던 것).

## 2. `a28cd38ff` — remove bypass from redirect to prefetch
- `redirectNext = RegNext(redirect)` 및 prefetch로의 bypass 제거. auto-merge 깨끗, 고아 `redirectNext` 0.

## 3. `3a49b46e0` — redirect FTQ index 1-cycle-ahead
- `FtqIdxInAdvance` 메커니즘(receiver 시그니처 변경 포함, backend/CtrlBlock·Receiver는 auto-merge).
- 충돌: `redirectNext` 한 줄. **HEAD 취함**(core/feat-2fetch tip은 redirectNext=0 — core가 이미 제거).
  InAdvance 메커니즘은 core가 이미 가져와 auto-merge로 존재(중복 없음 확인).
- 4파일만 정확히 커밋(처음 `git add -A`로 untracked 오염 → reset 후 재구성하여 정리).

## 4. `637f62a88` — train cache flush condition fix
- flush 조건을 `backendRedirect.valid && (trainCache 또는 resolveQueue 인덱스 > redirect idx)`로 확장.
- 1.의 합성 위에 auto-merge. `flushTrain`(좁은 출력 게이트) vs `when`(넓은 상태 갱신) 구분이 feat-2fetch와 일치 확인.

## 검증 (사용자 직접 빌드)
- [ ] `mill -i xiangshan.compile`
- [ ] perf 측정 (마일스톤: 전체 2-fetch+follow-up 적용 후)

## 잔여 리스크
- pair-second × trainCache 합성은 **수동 하드웨어 로직**이므로, 시뮬레이션에서 BPU train 흐름(데드락/누락 학습) 동작 검증 권장.
- 나머지는 [[03-7c29456a9-implement-2fetch]]의 잔여 리스크(미적용 갭 심볼) 동일.
