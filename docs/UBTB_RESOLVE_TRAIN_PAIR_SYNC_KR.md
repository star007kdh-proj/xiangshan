# uBTB resolve+fastTrain 병용 (#6237) 과 2-taken pair 학습의 상호작용

> 브랜치 `feat/ubtb-upstream-sync` (base `6e9bf46ab` + #70348f452 + #6009). 작성: 2026-09-06.
> 대상 upstream 커밋: `53a957667` perf(ubtb): use resolve+fast train (#6237), 2026-08-21.

---

## 1. #6237 의 변경 요지

- 기존: `UseFastTrain` 컴파일 파라미터로 uBTB 학습 소스를 fastTrain (S3 최종 예측) 또는 resolve (backend
  mispredict) 중 **하나만** 선택. 기본 fastTrain.
- 변경: 두 소스 병용. 같은 cycle 에 둘 다 valid 이면 **resolve 우선**, fastTrain 은 그 cycle 에 버려짐.

```
t0_useFast    = fastTrain.valid
t0_useResolve = stageCtrl.t0_fire && train.mispredictBranch.valid
t0_fire       = (t0_useFast || t0_useResolve) && enable
t0_startPc    = Mux(t0_useResolve, train.startPc,              fastTrain.startPc)
t0_branch     = Mux(t0_useResolve, train.mispredictBranch.bits, fastTrain.branch)
```

- 동기: `s1 miss → s3 miss → resolve → redirect → s1 miss(재발) → s3 hit → s1 train` 흐름에서 redirect 직후의
  s1 miss 1회를 없앰 (resolve 시점에 s1 도 같이 학습).
- 번들: `BpuFastTrain.finalPrediction: Prediction` + `hasOverride` → `branch: BranchInfo` (`mispredict := hasOverride`).
  upstream 은 이름도 `FastTrain` 으로 바꿨으나 본 트리는 Pc 리팩터 (#325f8605f) 미반영이라 `BpuFastTrain` 이름과
  `PrunedAddr` 타입 유지.

---

## 2. 현 트리의 pair 학습 구조 (변경 전 기준)

| 요소 | 소스 | 비고 |
| ---- | ---- | ---- |
| slot1 학습 (alloc / useful ± / firstKill) | t0 generic 경로 (`t0_fire`, `t0_branch`) | #6237 로 소스가 Mux 됨 |
| pair 체인 스냅샷 `pairPrev_ft` | `io.fastTrain` 직접 | redirect 시 clear |
| 체인 판정 `t0_pairSeq` / B 분류 `t0_pairEligible` / `t0_fastTrainKill` | `io.fastTrain` 직접 | generic 경로와 독립 |
| slot2 write-back (Alloc / Confirm / Kill) | t1, `t1_promoteIdx` | `t1_promoteConflict` (generic write 와 같은 idx) 이면 skip |
| mispKill | `io.mispKill` (FTQ, pair-second redirect) | last-connect 로 generic write 보다 우선 |

---

## 3. 상호작용 분석과 결정

| # | 상황 | 분석 | 결정 |
| - | ---- | ---- | ---- |
| S1 | pair 체인에 resolve 소스를 쓸 것인가 | 체인은 "연속한 두 S3 예측 (A→B)" 이 필요. resolve 는 mispredict 블록만, 순서 무관하게 도착 → 인접성 판정 불가 | **fastTrain 전용 유지**. `pairPrev_ft`, `t0_pairSeq`, `t0_pairEligible`, `t0_fastTrainKill` 은 `io.fastTrain` 을 그대로 읽음 (Mux 된 `t0_branch` 를 쓰지 않음) |
| S2 | resolve 와 fastTrain 이 같은 cycle 에 valid | generic slot1 학습은 resolve 로, 그 cycle 의 fastTrain slot1 학습은 소실. pair 학습은 fastTrain 기준으로 계속 진행 | 허용. slot1 소실은 upstream 과 동일 동작. pair promote 가 resolve 의 generic write 와 같은 idx 를 노리면 기존 `t1_promoteConflict` 가 skip → 무결성 유지. 빈도는 `trainFastDroppedByResolve` 로 측정 |
| S3 | resolve 소스로 block A 의 BR1 이 not-taken mispredict | t1 "hit && !actualTaken" → usefulCnt 감소 + slot2 무효화 (firstKill) | 의도된 강화. 기존엔 S3 예측 기준으로만 firstKill 이 났으나, 이제 실제 outcome 으로도 pair 가 죽음 (gem5 의 real-outcome firstKill 과 근접) |
| S4 | resolve 소스로 block A 의 다른 분기 (BR1 보다 앞) 가 taken mispredict | position mismatch → not-useful 이면 slot1 재초기화 (slot2 clear), 아니면 감소 + slot2 clear | 기존 규칙 그대로. 새 BR1 이 앞에 생겼으므로 pair 무효가 맞음 |
| S5 | pair-second 슬롯 (block B) 의 resolve | FTQ 가 `isPairSecond` 슬롯의 `toBpu.train.valid` 를 억제 → resolve 소스가 block B 를 보지 않음 | 변경 없음. B 측 교정은 `mispKill` 이 유일 (기존과 동일). #6237 의 "redirect 직후 s1 miss 제거" 이득은 pair-second 에는 적용되지 않음 (측정 항목) |
| S6 | `t0_entryConsistent` 가 live `entries` 를 읽는 동안 이전 cycle resolve write 가 아직 반영 전 | 1-cycle stale 가능. aliasing 가드는 best-effort 이므로 오판 시 promote 가 skip 되거나 (보수적), 드물게 stale slot1 과 비교 | 기존 hazard 와 동일 (fastTrain 연속 학습에서도 존재). 변경 없음 |
| S7 | `fastTrain.branch.mispredict (= hasOverride)` 활용 | S3 override 가 난 pass 에서 PairKill 을 억제하는 후속 옵션 (`TWO_TAKEN_REVIEW` 의 "override-free pass 게이트") | 이번 미채택. 필드는 운반되므로 후속에서 게이트만 추가하면 됨 |
| S8 | `mispKill` / `fastTrainKill` 과 resolve generic write 의 순서 | 둘 다 generic write 뒤 last-connect | 변경 없음 |

---

## 4. 코드 변경 (cherry-pick 충돌 해결 포함)

| 파일 | 내용 |
| ---- | ---- |
| `bpu/Bundles.scala` | `BpuFastTrain{startPc: PrunedAddr, branch: BranchInfo, abtbMeta, utageMeta}`; `BranchInfo.fromPrediction` 추가 (`.unGuard` 제거) |
| `bpu/Bpu.scala` | `fastTrain.bits.branch.fromPrediction(s3_prediction, s3_override)`; perf 의 `finalPrediction.taken` → `branch.taken` |
| `bpu/abtb/AheadBtb.scala` | `t0/t1_train.finalPrediction.*` → `branch.*` |
| `bpu/utage/MicroTage.scala` | 충돌은 HEAD 유지 — 본 트리의 uTAGE 는 resolve (`io.train`) 로 학습하며 fastTrain 을 쓰지 않음 (upstream 의 fastTrain 학습은 별도 커밋 #5517 계열, 미반영) |
| `bpu/ubtb/Parameters.scala` | `UseFastTrain` 삭제 (자동 병합) |
| `bpu/ubtb/MicroBtb.scala` | t0 소스 Mux (upstream 그대로); pair 코드의 `finalPrediction` → `branch`, `UseFastTrain && EnableTwoTaken` → `EnableTwoTaken`; perf `trainFromResolve`, `trainFastDroppedByResolve` 추가 |

---

## 5. 확인 항목

1. `mill -i xiangshan.compile` — `EnableTwoTaken` false / true 양쪽 (이 환경엔 toolchain 없음, 미검증).
2. `trainFromResolve` > 0, `trainFastDroppedByResolve` / `trainFromResolve` 비율 (S2 소실 빈도).
3. `pairFirstKillNotTaken` 증가 여부 (S3: resolve 소스로 firstKill 이 추가 발생).
4. `pairPromoteConflict` 증가 여부 (S2 의 idx 충돌).
5. redirect 직후 uBTB `predMiss` 감소 (#6237 본래 목적), pair-second 슬롯은 제외.

*문서 끝.*
