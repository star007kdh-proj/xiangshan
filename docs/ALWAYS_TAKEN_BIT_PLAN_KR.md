# mBTB / uBTB always-taken 비트 도입 계획 (2-taken cond-B 게이트)

> 대상 브랜치: `feat/vc-per-internal-bank` (mBTB VC 포함 트리). 작성: 2026-09-06.
> 참조 gem5: `GEM5_2taken_v4` (`src/cpu/pred/btb/mbtb.cc`, `btb_ubtb.cc`,
> `docs/ubtb_pair_gem5_to_chisel.md`). 선행 문서: `docs/ubtb_pair_gem5_sync_plan.md` §4.2 (D3, proxy 대체 결정),
> `docs/ubtb_pair_counters.md` §P1 (도입 트리거 조건).

---

## 0. 배경과 목적

- gem5 `BTBEntry.alwaysTaken` 1b: cond 분기 신규 할당 시 1, 실제 not-taken 1회 관측 시 영구 0.
  gem5 2-taken 은 uBTB slot B 의 cond 를 이 비트로 게이트 (G6/C6). Chisel 은 하드웨어 비용을 이유로
  미도입하고 **confidence 포화 proxy** (`PairCondConfThreshold = 4`, 3b conf) 로 대체.
- proxy 의 한계 (`TWO_TAKEN_REVIEW.md` F2/F6): cond-B 는 4회 연속 clean pass 를 요구해 거의 발화하지 않고,
  발화해도 S3 보정 없이 backend redirect 로만 회수. cond-B 비중이 큰 loop-backedge 체인에서 2-taken 이득이 제한됨.
- 목적: mBTB 에 always-taken 비트를 저장·학습하고 uBTB slot2 로 복사해, **alwaysTaken cond-B 는 direct-jmp 와
  같은 threshold 로 emit** (gem5 G6 정합). 부수 효과로 gem5 와 동일하게 S3 taken 결정과 TAGE/SC 학습에도 반영.

---

## 1. gem5 의미론 (정합 기준)

| 항목 | gem5 동작 | 근거 |
| ---- | -------- | ---- |
| 초기값 | cond 신규 엔트리: `alwaysTaken = true`, `ctr = 0` (weak taken). 단 해당 학습에서 실제 not-taken 이면 false | `mbtb.cc:441-447`, `:702-707` |
| 해제 | `!this_cond_taken → alwaysTaken = false` (영구, 재설정 없음) | `mbtb.cc:570-576` |
| ctr 동결 | `if (!alwaysTaken) updateCtr(...)` — 비트가 1 인 동안 ctr 미갱신, 해제된 그 학습부터 갱신 | `mbtb.cc:577-579` |
| 예측 (mBTB) | `condTaken = alwaysTaken \|\| ctr >= 0` | `mbtb.cc:265` |
| 예측 (TAGE) | `taken = tagePred \|\| alwaysTaken` — TAGE/SC 결과 무시하고 taken | `btb_tage.cc:309`, `btb_mgsc.cc:483` |
| 최종 선택 | `entry.isDirect \|\| isIndirect \|\| ctr >= 0 \|\| alwaysTaken` | `decoupled_bpred.cc:244,255` |
| TAGE 학습 | `isCond && !alwaysTaken` 인 분기만 TAGE update 대상 (alwaysTaken 분기는 TAGE 할당/갱신 제외) | `btb_tage.cc:409-413` |
| uBTB emit | G6 `pairBranch.isCond && !alwaysTaken → no pair`; threshold 는 타입 공통 단일값 | `btb_ubtb.cc:248`, sync doc §5.1 |
| uBTB 학습 | C6 `secondTaken.isCond && !alwaysTaken → 즉시 invalidate`; 적격 시 M/H1/H2 | `btb_ubtb.cc:445`, sync doc §6.2 |
| 비트 출처 | uBTB `pairBranch.alwaysTaken` 은 S3 mBTB BTBEntry 의 비트를 학습 시 그대로 복사 | sync doc §5.1 주석 |

---

## 2. 현 Chisel 구조 요약 (변경 지점 식별)

| 구성 | 현재 | 파일:라인 |
| ---- | ---- | -------- |
| mBTB entry SRAM | `MainBtbEntry` 47b (valid1 + tag16 + attr4 + pos4 + carry2 + tgt20), way 당 SRAM 1개. **mispredict 시에만 write** | `mbtb/Bundles.scala:35-55`, `MainBtbAlignBank.scala:252-283` |
| mBTB counter SRAM | `TakenCounter` 2b × 4 way, 별도 SRAM. **resolve 된 모든 cond 분기마다 write** (entry write 와 독립) | `MainBtbInternalBank.scala:107-119`, `MainBtbAlignBank.scala:290-314` |
| mBTB meta | `MainBtbMetaEntry{rawHit, position, attribute, counter, (VC snapshot)}`. T1 은 SRAM 재읽기 없이 meta 만으로 갱신 | `mbtb/Bundles.scala:69-82` |
| mBTB 예측 출력 | `pred.bits.taken := c.isPositive` (S2), `Prediction{cfiPosition,target,attribute,taken}` | `MainBtbAlignBank.scala:190-212`, `bpu/Bundles.scala:389-394` |
| VC | `VCEntry.counter` 포함, T1 counter 학습 경로 존재 | `mbtb/Bundles.scala:85-93`, `MainBtbAlignBank.scala:370-379` |
| S3 taken 결정 | `isConditional && MuxCase(base=mbtb.taken, sc, tage provider, tage alt)` | `Bpu.scala:377-395` |
| fastTrain | `BpuFastTrain{startPc, finalPrediction, hasOverride, abtbMeta, utageMeta}` — alwaysTaken 운반 없음 | `bpu/Bundles.scala:278-284`, `Bpu.scala:184-190` |
| uBTB slot2 | `{position, attribute, target, valid, taken(=1 고정), confidence 3b}` + `isPair` | `ubtb/Bundles.scala:56-63` |
| uBTB 학습 게이트 | `t0_pairEligible`: B taken && !pop && !push && !indirect && (isDirect \|\| isConditional) | `MicroBtb.scala:343-349` |
| uBTB emit 게이트 | `s1_usePair`: conf >= Mux(B.isConditional, 4, 2) 외 타입 게이트 | `Bpu.scala:330-349` |
| TAGE 학습 | mbtb meta hit 인 cond 분기 전부 학습 (`basePred = meta.counter.isPositive`) | `Tage.scala:192-198` |

---

## 3. 설계 결정

### D1. 저장 위치: entry SRAM 이 아닌 **counter SRAM** (2b → 3b)

- 해제 이벤트 (첫 not-taken) 는 mispredict 가 아닐 수 있음 — 단, §3 D3 채택 시 비트가 1 인 동안 S3 는 항상 taken 을
  내므로 첫 not-taken 은 반드시 mispredict. 그럼에도 entry SRAM 경로는 (a) `t1_entryNeedWrite` 조건이 miss/needIttage/
  attribute 변경으로 제한되어 hit 시 write 가 안 일어나고, (b) entry write 는 tag/target 전체를 다시 쓰므로 비트 하나를 위해
  47b write 를 여는 것은 전력상 불리.
- counter SRAM 은 이미 "resolve 된 cond 마다 way-mask write" 구조 (`t1_counterWayMask`) 이므로 비트 갱신이 자연스럽게 얹힘.
  gem5 의 "ctr 동결 / 해제 시 ctr 갱신" 규칙도 같은 write 에서 계산 가능.
- 구현: `TakenCounter` 폭은 유지하고 counter SRAM 원소를 새 bundle 로 교체.

```scala
// mbtb/Bundles.scala
class MainBtbDirectionEntry(implicit p: Parameters) extends MainBtbBundle {
  val alwaysTaken: Bool            = Bool()
  val counter:     SaturateCounter = TakenCounter()
}
```

### D2. 운반 경로: `Prediction` 확장이 아닌 **mBTB 별도 출력 + fastTrain 필드**

- `Prediction` 은 uBTB/aBTB/fallthrough/s1·s3 prediction 이 공유하는 bundle. 1b 추가 시 무관한 파이프 레지스터와
  FTQ 저장 폭까지 증가. 대신 `MainBtb.io.alwaysTaken: Vec(NumBtbPredEntries, Bool)` 를 `io.result` 와 같은 S2 타이밍으로
  출력하고, BPU top 이 S3 로 파이프.
- uBTB 로는 `BpuFastTrain.alwaysTaken: Bool` (S3 first-taken 분기의 비트) 로 전달. Option 이 아닌 상시 필드 (1b, EnableTwoTaken
  와 무관하게 D3 에 쓰이므로).
- TAGE `altOrBasePred` (`Tage.scala:165`) 는 `branch.bits.taken` (순수 ctr) 을 그대로 사용 — useAltOnNa 학습 의미 보존.

### D3. S3 taken 결정에 OR 반영 (gem5 parity, 기본 on)

- `s3_takenMask(i) := valid && (isDirect || isIndirect || isConditional && (alwaysTaken || MuxCase(...)))`.
- 채택 이유: (1) gem5 이득이 이 규칙 하에서 측정됨. (2) uBTB 학습 (C4 PairKill: "B 에 taken 없음") 은 S3 fastTrain 의
  taken 을 보므로, 비트가 1 인데 TAGE 가 not-taken 을 내면 alwaysTaken pair 가 학습에서 죽는 비정합 발생. OR 로 S3 와 비트를
  일치시킴. (3) 비용: 첫 not-taken 1회 mispredict (비트가 곧 해제되므로 반복 없음).
- 파라미터 `AlwaysTakenOverridesS3: Boolean = true` 로 ablation 가능.

### D4. TAGE/SC 학습 제외 (gem5 parity, 기본 on, 별도 phase)

- gem5 는 alwaysTaken cond 를 TAGE update 대상에서 제외 (`btb_tage.cc:409-413`) → TAGE 용량 절약.
- Chisel: TAGE `t0` 에서 branch 별 `mbtbMeta.alwaysTaken` (hit 한 way 의 meta) 이 1 이면 해당 branch 학습 skip.
  SC 도 동일 (`t1_branchesScIdxHitVec` 에 AND). 예측 시점 비트 기준이므로 첫 not-taken 이벤트도 skip 됨 (gem5 동일).
- 파라미터 `AlwaysTakenSkipTageTrain: Boolean = true`. 정확도 회귀 시 off.

### D5. uBTB 저장: **slot2 에만** `alwaysTaken` 1b 추가, slot1 은 미추가

- slot2: emit 게이트 G6 와 threshold 선택에 사용. 학습 시 fastTrain 의 비트를 복사 (gem5 §5.1 과 동일).
- slot1 미추가 이유: uBTB slot1 은 hit ⇒ taken 예측이라 방향 정보가 불필요하고, slot A cond 는 gem5 에서도
  alwaysTaken 무관하게 허용 (G1~G3 는 타입 게이트만).
- `slot2.taken` (항상 1) 은 그대로 둠 — 의미가 다름 (pair 가 (taken, taken) 이라는 불변식).

### D6. cond-B 규칙: `PairCondSlotRequiresAlwaysTaken`

| 값 | 학습 (t0) | emit (s1) | 용도 |
| -- | -------- | --------- | ---- |
| `true` (**기본**) | cond-B && !alwaysTaken → **fastTrainKill** (gem5 C6) | cond-B 는 `slot2.alwaysTaken` 필수, threshold = `PairDirectConfThreshold` | gem5 정합 |
| `false` | 현행 유지 (cond-B 는 항상 적격) | alwaysTaken cond-B: direct threshold / 비트 없는 cond-B: `PairCondConfThreshold` | 회귀 비교, confidence 와 비트 병행 |

- `false` 는 현 코드 동작의 상위집합 (alwaysTaken 이면 문턱만 낮아짐). A/B 측정 후 하나로 정리.

### D7. VC 반영

- `VCEntry` 에 `alwaysTaken` 추가. VC counter 학습 (`t1_vcCounterUpdate`) 과 Path B/C (`t1_vcUpdateEntry`,
  `t1_evictedEntry`) 모두 비트 동반. VC slot meta (`vcSlotMetas`) 도 비트 포함. VC 미사용 빌드 (`VCSize = 0`) 는 무영향.

---

## 4. 추가 storage

| 구조 | 현재 | 추가 | 비고 |
| ---- | ---- | ---- | ---- |
| mBTB counter SRAM | 2b × 8192 = 16 Kb | +1b × 8192 = **+8 Kb** (SRAM 폭 8b→12b/set, 32 인스턴스) | entry SRAM (47b × 8192) 무변경 |
| mBTB VC (VCSize=N 일 때) | (47+2)b × N × 8 | +1b × N × 8 | 기본 VCSize=0 |
| mBTB meta (FTQ metaQueueResolve) | 10 entries × (1+5+4+2)b | +1b × 10 = **+10b / FTQ entry** | VC 시 12 entries |
| BPU S2→S3 파이프 | — | +10 flops (`s3_alwaysTakenMask`) | |
| fastTrain | — | +1b | |
| uBTB entry (EnableTwoTaken) | 93b × 32 | +1b × 32 = **+32 flops** | |
| **합계** | | **≈ 8.4 Kb SRAM + ~50 flops + FTQ meta 10b/entry** | entry SRAM 대비 2.1 % |

---

## 5. 학습 경로 (mBTB T1)

### 5.1 갱신 규칙 (way i, `MainBtbAlignBank.scala:294-304` 확장)

```
hitMask(i)     = ∃ branch: valid && isConditional && meta(i).position === branch.cfiPosition
actualTaken    = Mux1H(hitMask, branches.taken)
entryOverridden = t1_entryNeedWrite && t1_entryWayMask(i)

allocAT        = t1_mispredictInfo.attribute.isConditional && t1_mispredictInfo.taken   // gem5: 신규 cond 는 AT=1, 단 not-taken 학습이면 0
nextAT         = Mux(entryOverridden, allocAT, meta(i).alwaysTaken && actualTaken)         // 영구 해제
nextCtr        = Mux(entryOverridden, WeakPositive,
                     Mux(nextAT, meta(i).counter, meta(i).counter.getUpdate(actualTaken)))  // AT 동안 동결, 해제 학습부터 갱신

t1_counterWayMask(i) = entryOverridden || hitMask.orR      // 기존과 동일
t1_newDir(i)         = {alwaysTaken = nextAT, counter = nextCtr}
```

- `entryOverridden` 이면서 non-cond (direct/indirect) 할당: `allocAT = 0`, ctr WeakPositive (기존 동일).
- hit 이 아닌 way (`hitMask` 없음, `entryOverridden` 아님) 는 write mask 0 → 무변경.
- 같은 way 에 `entryOverridden` 과 `hitMask` 가 동시에 참인 경우 (attribute 변경 재할당): `entryOverridden` 우선 (기존 Mux 순서 유지).

### 5.2 VC 경로 (`MainBtbAlignBank.scala:341-379`)

- Path B (`t1_vcUpdateEntry`): `needReset` 이면 `{allocAT, WeakPositive}`, 아니면 §5.1 규칙을 `t1_vcHitEntry` 기준으로 적용.
- Path C (`t1_evictedEntry`): `t1_evictedMeta.alwaysTaken` 복사.
- direction 학습 (`directionUpdate(k)`): §5.1 규칙을 VC entry k 기준으로 적용 (`entryOverridden` 없음). 같은 cycle 의
  Path B `update` 가 같은 엔트리를 쓰면 update 가 이김 (Path B 도 같은 규칙으로 계산하므로 결과 동일).

### 5.3 pair-second 슬롯의 비트 미갱신 (알려진 한계)

- pair-second 슬롯은 resolve train 이 억제됨 (`Ftq.scala:500`, meta 없음). 따라서 pair 로 발화된 BR2 가 not-taken 이면
  mBTB 비트는 그 시점에 해제되지 않음.
- 자기치유 순서: BR2 not-taken → backend redirect → `mispKill` → block B 가 single 경로로 재예측 (비트 1 → S3 taken)
  → 두 번째 mispredict → 이번엔 single 슬롯이라 resolve train 도달 → 비트 해제 → 이후 fastTrain 의 alwaysTaken=0
  → C6 로 재학습 차단. 추가 mispredict 1회가 상한.
- 대응: perf counter `alwaysTakenClearAfterPairMisp` (redirect.isPairSecond 이후 같은 startPc 의 첫 clear) 로 빈도 측정.
  빈도가 유의하면 후속으로 FTQ 가 pair-second redirect 의 (cfiPc, not-taken) 을 mBTB 에 별도 clear 요청으로 보내는 경로 검토
  (meta 없이 tag+position CAM 필요 → pdInvalidate 와 유사한 r1 파이프, 본 계획 범위 외).

---

## 6. 예측 경로

### 6.1 mBTB S2 출력 (`MainBtbAlignBank.scala:190-212`, `MainBtb.scala:177-236`)

```
pred.bits.taken     := c.counter.isPositive           // 변경 없음 (순수 ctr, TAGE altOrBasePred 용)
io.alwaysTaken(i)   := hit && e.attribute.isConditional && c.alwaysTaken   // 신규 Vec 출력
meta.alwaysTaken    := c.alwaysTaken                  // T1 갱신용
```

- VC slot (`MainBtb.scala:189-214`): `io.alwaysTaken(NumWay*NumAlignBanks + s) := vcSlotValid(s) && vcEntry.alwaysTaken`.
- `s1_positions` 등 S1 타이밍 경로 무변경.

### 6.2 BPU S3 (`Bpu.scala:372-426`)

```
s3_alwaysTakenMask = RegEnable(mbtb.io.alwaysTaken, s2_fire)
s3_takenMask(i)    = valid && (isDirect || isIndirect ||
                     isConditional && (AlwaysTakenOverridesS3.B && s3_alwaysTakenMask(i) || MuxCase(base, sc, tage)))
s3_firstTakenAT    = Mux1H(s3_firstTakenBranchOH, s3_alwaysTakenMask)
fastTrain.bits.alwaysTaken := s3_taken && s3_firstTakenBranch.bits.attribute.isConditional && s3_firstTakenAT
```

- `s3_override` 는 `s3_prediction === s3_s1Prediction` 비교라 변경 없음.
- perf meta: `BpuPerfMeta` 에 `alwaysTakenUsed` (S3 taken 이 OR 항으로만 결정된 경우) 추가 → 정확도 집계.

### 6.3 uBTB slot2 (`MicroBtb.scala:103-134`, `ubtb/Bundles.scala:56-63`)

```
Slot2 += alwaysTaken: Option[Bool]  (EnableTwoTaken)
pairOut.bits.secondAlwaysTaken := s1_hitEntry.slot2.alwaysTaken.get   // MicroBtbPairOut 에 1b 추가
```

### 6.4 emit 게이트 (`Bpu.scala:330-349`)

```
s1_pairSecondCondAllowed = !second.isConditional || secondAlwaysTaken      // PairCondSlotRequiresAlwaysTaken = true
                         = true.B                                          // PairCondSlotRequiresAlwaysTaken = false
s1_pairConfThreshold = Mux(second.isConditional && !secondAlwaysTaken, PairCondConfThreshold, PairDirectConfThreshold)
s1_usePair = (기존 게이트) && s1_pairSecondCondAllowed && (confidence >= s1_pairConfThreshold)
```

- `PairCondSlotRequiresAlwaysTaken = true` 에서는 `PairCondConfThreshold` 미사용 (모든 B 가 direct threshold).
- ABTB agreement / fetch-hungry / S3 override 게이트 무변경.

---

## 7. uBTB 학습 경로 (`MicroBtb.scala:281-454`)

```
cur_at = io.fastTrain.bits.alwaysTaken

t0_pairSecondCondNotAlwaysTaken = PairCondSlotRequiresAlwaysTaken.B && cur_pred.taken && cur_attr.isConditional && !cur_at
t0_fastTrainKill += t0_pairSecondCondNotAlwaysTaken                                                             // C6
t0_pairEligible  : 비트 필수 (true) → cond-B 는 cur_at 필수 / false → 기존 유지
t1_promoteSlot2AT = RegEnable(cur_at, t0_pairEligible)

PairAlloc   : slot2.alwaysTaken := t1_promoteSlot2AT
PairConfirm : sameBR2 비교에 alwaysTaken 포함하지 않음 (내용 비교는 pos/attr/target). 대신
              slot2.alwaysTaken := t1_promoteSlot2AT 로 갱신 (비트가 0 으로 바뀐 경우 즉시 반영 → 다음 emit 차단).
              비트 필수 설정에선 !cur_at cond-B 가 C6 kill 로 먼저 잡히므로 Confirm 에 도달하는 cond-B 는 항상 1.
PairKill / fastTrainKill / mispKill / firstKill : 기존 동일 (slot2 전체 무효화)
```

- `initEntryIfNotUseful` 의 slot2 초기화에 `alwaysTaken := false` 추가.
- 기존 `t1_promoteConflict` 규칙 유지.

---

## 8. TAGE / SC 학습 제외 (D4, Phase 4) — **미채택 (2026-09-08 사용자 결정: Phase 3 까지만 구현)**

- TAGE (`Tage.scala:190-200` t0): branch 별 `mbtbMeta` hit way 의 `alwaysTaken` 을 `Mux1H(hitMaskOH, mbtbMeta.map(_.alwaysTaken))`
  로 추출, `AlwaysTakenSkipTageTrain.B && at` 이면 해당 branch 의 update/allocate 마스크 0.
- SC (`Sc.scala:402-410`): `t1_branchesScIdxHitVec(i) &= !at`.
- 첫 not-taken 이벤트는 예측 시점 비트가 1 이라 skip (gem5 동일). 그 다음 관측부터 TAGE/SC 정상 학습.
- 부작용 관측: TAGE `allocate` 감소량, SC 학습 수, cond mispredict 율. 회귀 시 파라미터 off.

---

## 9. 파라미터

| 파라미터 | 위치 | 기본 | 설명 |
| -------- | ---- | ---- | ---- |
| `AlwaysTakenOverridesS3` | `BpuParameters` | `true` | S3 taken 에 비트 OR (D3) |
| `AlwaysTakenSkipTageTrain` | `BpuParameters` | `true` | alwaysTaken cond 의 TAGE/SC 학습 제외 (D4) |
| `PairCondSlotRequiresAlwaysTaken` | `BpuParameters` | `true` | cond-B 는 always-taken 비트 필수 (D6) |
| `PairCondConfThreshold` | 기존 | 4 | 비트 없는 cond-B 전용 (`PairCondSlotRequiresAlwaysTaken = false` 일 때만 사용) |
| `PairDirectConfThreshold` | 기존 | 2 | direct-B 및 always-taken cond-B 공통 |

- mBTB 비트 자체는 compile guard 없이 상시 존재 (counter SRAM 폭 +1). 비트 소비 (D3/D4/pair) 만 파라미터로 차단.
  이유: SRAM 폭을 조건부로 바꾸면 VC/meta/Option 분기가 mBTB 전역에 퍼지고, 이득 검증 후 제거 가능성이 낮음.
  baseline 회귀 확인은 `AlwaysTakenOverridesS3 = false && AlwaysTakenSkipTageTrain = false && EnableTwoTaken = false`
  로 기능 동일성 (Verilog bit-diff 는 SRAM 폭 차이로 불가 → difftest + perf 동일성으로 대체).

---

## 10. 변경 파일과 단계

### Phase 1 — mBTB 비트 저장·학습·출력 (단독 컴파일/difftest 가능) — **구현 완료 (2026-09-06, 컴파일 미검증)**

> 구현 메모: 브랜치 `feat/ubtb-upstream-sync` (base `6e9bf46ab`, VC 는 MainBtb 단일 인스턴스 구조). counter SRAM 원소를
> `MainBtbDirectionEntry{alwaysTaken, counter}` 로 교체, `MainBtbDirectionEntry.init(allocAT)` 로 할당 초기화. S2 출력은
> `MainBtbAlignBank.io.read.resp.alwaysTaken` → `MainBtb.io.alwaysTaken` (Phase 2 에서 BPU 가 소비, 현재 미연결).
> VC Path B 는 `needReset` 이면 `{isCond && taken, WeakPositive}`, 아니면 SRAM 과 같은 규칙; Path C 는 meta 의 비트 복사.
> 이 base 에는 VC per-resolve counter 학습 (`a97cd6584`) 이 없었으므로 같은 의미를 단일 VC 구조에 맞춰 추가
> (2026-09-09): `MainBtbVictimCache.io.entries` 로 전 엔트리를 내보내고 `directionUpdate` 포트로 T1 에 비트+counter 를
> 갱신. MainBtb T1 이 엔트리의 align bank 별 tag (`makeVCTag(t1_startPcVec)`) 와 block-relative 반쪽 인덱스로 full
> position 을 재구성해 resolve 된 모든 cond 분기와 CAM. 우선순위 direction < invalidate < update(Path B) < insert.
> perf: `alwaysTakenSet/Clear`, `predAlwaysTakenHit`, `vc_direction_update`, `vc_alwaysTaken_clear`.

| 파일 | 변경 |
| ---- | ---- |
| `bpu/mbtb/Bundles.scala` | `MainBtbDirectionEntry` 신설, `MainBtbCounterSramWriteReq.counters` 타입 교체, `MainBtbMetaEntry.alwaysTaken`, `VCEntry.alwaysTaken` |
| `bpu/mbtb/MainBtbInternalBank.scala` | counter SRAM 원소 타입 `MainBtbDirectionEntry`, `read.resp.counters` 타입 |
| `bpu/mbtb/MainBtbAlignBank.scala` | §5.1 갱신 규칙, S2 `io.read.alwaysTaken` 출력, meta 채움, §5.2 VC 3경로 |
| `bpu/mbtb/MainBtbVictimCache.scala` | `counterUpdate` 포트 타입을 `MainBtbDirectionEntry` 로 |
| `bpu/mbtb/MainBtb.scala` | `io.alwaysTaken: Vec(NumBtbPredEntries, Bool)` 조립 (SRAM + VC slot), VC slot meta |
| perf | `mbtb.alwaysTakenSet / alwaysTakenClear / predAlwaysTakenHit` |

### Phase 2 — BPU S3 반영 + fastTrain 운반 — **구현 완료 (2026-09-08, 컴파일 미검증)**

> 구현 메모: `s3_predTakenMask` (SC > TAGE provider > alt > base) 와 `s3_alwaysTakenForce` (bit && `AlwaysTakenOverridesS3`)
> 를 분리해 `s3_takenMask = valid && (jump || cond && (force || pred))` 로 조립. fastTrain 은 #6237 이후 `branch: BranchInfo`
> 이므로 `BpuFastTrain.alwaysTaken` 을 그 옆에 두고, 조기 선언한 `s3_firstTakenAlwaysTaken` Wire 로 S3 first-taken 의
> 비트 (cond 한정) 를 운반. `BpuPerfMeta` 는 이미 `mbtbMeta` (alwaysTaken 포함) 를 실으므로 별도 필드 미추가 —
> 커밋 기준 blame 분리 (`BlameBpuSource` 에 ALWAYS_TAKEN 항목) 는 후속. perf: `s3_alwaysTaken_override` (bit 가 predictor 의
> not-taken 을 뒤집은 슬롯 수), `s3_alwaysTaken_agree`, `s3_firstTaken_alwaysTaken`, mBTB `alwaysTakenClearOnMispredict`.
> `AlwaysTakenSkipTageTrain` 파라미터는 Phase 4 미채택으로 추가하지 않음.

| 파일 | 변경 |
| ---- | ---- |
| `bpu/Parameters.scala` | `AlwaysTakenOverridesS3`, `PairCondSlotRequiresAlwaysTaken` |
| `bpu/Bundles.scala` | `BpuFastTrain.alwaysTaken`, `BpuPerfMeta.alwaysTakenUsed`, `MicroBtbPairOut.secondAlwaysTaken` (ubtb/Bundles) |
| `bpu/Bpu.scala` | `s3_alwaysTakenMask`, §6.2 takenMask, fastTrain 채움, perf |
| perf | `s3AlwaysTakenOverride` (OR 항으로만 taken 된 횟수), `alwaysTakenFirstNotTakenMisp` |

### Phase 3 — uBTB slot2 비트 + 게이트 (EnableTwoTaken 빌드) — **구현 완료 (2026-09-08, 컴파일 미검증)**

> 구현 메모: `PairCondSlotRequiresAlwaysTaken: Boolean = true`.
> 학습: `t0_pairSecondCondNotAlwaysTaken` (비트 필수 && taken && cond && !fastTrain.alwaysTaken) 을 `t0_fastTrainKill`
> 에 OR (C6), `t0_pairEligible` 에서 제외. `t1_promoteSlot2AlwaysTaken` 을 Alloc/Confirm 양쪽에서 기록 (Confirm 시
> 갱신이라 mBTB 비트가 0 으로 바뀌면 다음 emit 부터 차단). lookup: `MicroBtbPairOut.secondAlwaysTaken`. emit:
> `s1_pairSecondCondAllowed` + threshold 는 `cond && !secondAlwaysTaken` 일 때만 `PairCondConfThreshold`. perf:
> `pairKillCondNotAlwaysTaken`, `pairEligibleCondAlwaysTaken/CondNotAlwaysTaken`, `pairLookupHitCondAlwaysTaken`,
> `pairFireCondAlwaysTaken/CondNotAlwaysTaken/Jump`, `pairBlockedCondNotAlwaysTaken`,
> `pairSecondMispCondAlwaysTaken/CondNotAlwaysTaken/Jump`. pair-second perf meta (I-5) 는 미착수.

| 파일 | 변경 |
| ---- | ---- |
| `bpu/ubtb/Bundles.scala` | `Slot2.alwaysTaken: Option[Bool]`, `MicroBtbPairOut.secondAlwaysTaken` |
| `bpu/ubtb/MicroBtb.scala` | §7 학습 (C6 kill, alloc/confirm 복사), lookup 출력, init 초기화 |
| `bpu/Bpu.scala` | §6.4 emit 게이트, threshold 선택 |
| perf | `pairKillCondNotAlwaysTaken` (C6), `pairFireCondAlwaysTaken / CondNotAlwaysTaken / Jump`, `pairSecondMispCondAlwaysTaken / CondNotAlwaysTaken / Jump`, `pairBlockedCondNotAlwaysTaken` |

### Phase 4 — TAGE/SC 학습 제외 (D4) — **미채택 (2026-09-08)**

| 파일 | 변경 |
| ---- | ---- |
| `bpu/tage/Tage.scala` | t0 branch 별 skip 마스크 |
| `bpu/sc/Sc.scala` | `t1_branchesScIdxHitVec` AND |
| perf | `tageTrainSkipAlwaysTaken`, `scTrainSkipAlwaysTaken` |

### 순서와 근거

```
Phase 1 → Phase 2 → Phase 3   (Phase 4 미채택)
```

- Phase 1/2 는 2-taken 과 무관하게 단독 측정 가능 (gem5 의 S3 OR 효과). Phase 3 이 본 목적.
- Phase 4 는 정확도 방향이 워크로드 의존적이라 마지막에 별도 A/B.

---

## 11. 타이밍 고려

- S2: counter SRAM 읽기 폭 +1b, `io.alwaysTaken` 은 `hit && isConditional && bit` 의 AND 2단 — `pred.taken` 과 동급.
- S3: `s3_takenMask` 의 MuxCase 앞에 OR 1단 추가. `s3_firstTakenBranchOH` (CompareMatrix) 입력이 되므로 critical path 에
  OR 1단이 얹힘 — 허용 범위로 예상, STA 로 확인.
- S1 (`s1_usePair`): `secondAlwaysTaken` 은 uBTB 레지스터 직접 출력, `s1_pairConfThreshold` Mux 의 select 에 AND 1단 추가.
  기존 TODO(timing) (`Bpu.scala:317`) 경로와 동일 cone — 별도 악화 없음.
- T1: `nextAT`/`nextCtr` 는 기존 `getUpdate` Mux 뒤에 Mux 1단. counter write buffer (`Queue`) 앞이라 여유 있음.

---

## 12. 검증 계획

1. **컴파일**: 각 Phase 마다 `mill -i xiangshan.compile`. Phase 3 은 `EnableTwoTaken = true` 빌드도.
2. **기능 회귀 (Phase 1)**: 소비 파라미터 모두 off 상태에서 coremark/microbench difftest 통과 + `pred_hit`/`updateCounter`
   등 기존 mBTB perf 값 baseline 과 동일.
3. **비트 동역학 (Phase 1)**: `alwaysTakenSet` ≈ cond 할당 수, `alwaysTakenClear` ≤ set, 루프 워크로드에서 backedge 의
   비트가 루프 종료 시 1회만 clear 되는지 waveform 확인 (신호: `MainBtbAlignBank.t1_newDir(i).alwaysTaken`,
   `MainBtbAlignBank.scala` §5.1 위치).
4. **S3 OR (Phase 2)**: `s3AlwaysTakenOverride` > 0, `alwaysTakenFirstNotTakenMisp` 가 clear 수와 근사 (해제당 mispredict 1회 상한).
   cond mispredict 율이 baseline 대비 악화되지 않을 것.
5. **pair (Phase 3)**:
   - `pairFireCondAlwaysTaken` 이 confidence 만 쓰던 때보다 증가, `pairSecondMispCondAlwaysTaken / pairFireCondAlwaysTaken`
     이 `pairSecondMispJump / pairFireJump` 와 동급.
   - C6: 비트 없는 cond-B 체인에서 `pairKillCondNotAlwaysTaken` 발생, `pairFireCondNotAlwaysTaken == 0`
     (`PairCondSlotRequiresAlwaysTaken = true`).
   - §5.3 시나리오: pair-second not-taken → redirect → 재예측 mispredict → clear 가 1회로 끝나는지 (`alwaysTakenClearAfterPairMisp`).
   - history 정합: 기존 `predictFHist_diff_*` 카운터 0 유지.
6. ~~TAGE/SC skip (Phase 4)~~: 미채택.
7. **gem5 cross-check**: 동일 워크로드에서 gem5 `pairSkipSecondCond` ↔ `pairKillCondNotAlwaysTaken`,
   gem5 `alwaysTaken` unset 횟수 ↔ `alwaysTakenClear` 비교.

---

## 13. 조사 항목 (구현 중 확인)

| # | 항목 | Phase |
| - | ---- | ----- |
| I-1 | counter SRAM `SRAMTemplate` 의 way-mask write 가 bundle 원소 (3b) 단위로 정상 동작하는지 (현재 `TakenCounter()` bundle 이므로 동일 패턴) | 1 |
| I-2 | `entryOverridden && hitMask` 동시 참 케이스에서 `allocAT` 가 `t1_mispredictInfo.taken` 을 봐야 하는지, `actualTaken` 을 봐야 하는지 (같은 분기이면 동일값) | 1 |
| I-3 | VC Path B `needReset` 조건 (`attrChanged || needIttage`) 시 AT 초기값 — cond 로 바뀐 경우만 `allocAT` | 1 |
| I-4 | `s3_firstTakenBranchOH` 가 VC slot 을 가리킬 때 `s3_alwaysTakenMask` 인덱스 정합 (NumBtbPredEntries 기준 flat) | 2 |
| I-5 | pair-second perf meta (`s3_secondPerfMeta`) 에 `secondAlwaysTaken` 을 실어 commit 기준 cond-AT 정확도 집계 가능한지 | 3 |
| I-6 | TAGE skip 시 `useAltOnNa` 카운터 학습도 함께 skip 해야 하는지 (gem5 는 update 전체 skip) | 4 |

---

## 14. 미채택 / 범위 외

| 항목 | 사유 |
| ---- | ---- |
| `Prediction` bundle 에 alwaysTaken 추가 | uBTB/aBTB/FTQ 폭 증가, D2 로 대체 |
| entry SRAM 에 비트 저장 | mispredict-only write 경로, D1 로 대체 |
| uBTB slot1 alwaysTaken | slot A 게이트에 불필요 (D5) |
| S2 early prediction (gem5 `always_taken_early_pred.md`, alwaysTaken 이면 TAGE 대기 없이 S2 확정) | 별도 override latency 최적화. 비트 인프라 공유 가능하므로 후속 문서로 분리 |
| pair-second redirect 시 mBTB 비트 즉시 clear | meta 없는 CAM 경로 필요 (§5.3). 측정 후 결정 |
| 비트 재설정 (해제 후 다시 1) | gem5 도 영구 해제. 엔트리 재할당 시에만 초기화 |

---

## 15. upstream 동향 (2026-09-06 조사, `upstream/kunminghu-v3` @ `50cdcfc2c` 2026-09-03)

- 현 브랜치의 merge-base 는 `87d03b2cc` (2026-03-24). 이후 upstream frontend 커밋 107건, 그중 uBTB 6건.
  uBTB 교체 정책 (`MicroBtbReplacer.scala`, usefulCnt 상태기계) 은 **변경 없음**. 학습 소스와 hit 판정이 바뀜.

| 커밋 | 날짜 | 내용 | 본 계획 영향 |
| ---- | ---- | ---- | ------------ |
| `53a957667` #6237 | 08-21 | **uBTB 학습을 resolve + fastTrain 병용**. `UseFastTrain` 파라미터 삭제, `t0_useResolve = t0_fire && mispredictBranch.valid` 가 fastTrain 보다 우선 (`Mux(t0_useResolve, io.train.mispredictBranch, io.fastTrain.branch)`). `FastTrain.finalPrediction: Prediction` → `branch: BranchInfo` (mispredict = hasOverride) | uBTB alwaysTaken 은 fastTrain 소스에만 존재. resolve 소스가 선택된 cycle 은 slot1 학습만 하고 pair 체인 (`pairPrev`) 은 fastTrain 기준 유지. rebase 시 `BpuFastTrain.alwaysTaken` → `FastTrain.alwaysTaken` 로 위치 이동, `branch.taken` 참조로 치환 |
| `db9e6b885` #6009 | 06-05 | s0 에서 tag 비교, `s1_hitT1Victim` (t1 이 victim 교체 중인 entry 를 s1 hit 으로 보지 않음), `s1_hitEntry = Mux1H(s1_hitOH, entries)` | 현 트리 미반영 (s1 에서 tag 비교). pair lookup 이 `s1_hitEntry` 를 쓰므로 rebase 시 `pairOut.valid` 에 `!s1_hitT1Victim` 동반 필요 |
| `70348f452` | 04-09 | uBTB/ABTB tag 비교를 s0 로 이동 (timing) | 위와 동일 계열, 현 트리 미반영 |
| `6de780379` #6348 | 08-18 | mBTB/ABTB replacer: S3 predictTouch 제거, T1 에서 actual-taken way 를 touch | 본 계획의 S3 경로와 무관. VC `s3_vcPredTouch` 는 rebase 시 별도 정리 |
| `17e07d632` #6350 | 08-13 | mBTB entry `valid` 제거, attribute 로 valid 표현 | §4 storage 표의 entry 폭 47b → 46b. counter SRAM 설계 (D1) 무영향 |
| `57563d5b7` #6324 | 08-05 | `s3_takenMask` 를 3:1 mux 로 축소 (`s3_jumpTakenVec(i) \|\| s3_isBrVec(i) && MuxCase(base, sc, tage.takenVec)`) | D3 삽입 위치는 `s3_isBrVec(i) && (AT \|\| MuxCase(...))` 로 동일. TAGE provider/alt 구분이 `takenVec` 로 합쳐져 있음 |
| `5d5e6cab0` #6356, `cb9ff1d8d` #6328 | 08-21 | TAGE basePred 사용 시 재읽기 생략, UseAltOnNa 최적화 | Phase 4 (TAGE 학습 skip) 는 rebase 후 upstream TAGE 코드 기준으로 재작성 |
| `24ed326a5` #6112 | 07-02 | IFU → BPU resolve feedback 학습 경로 추가 | mBTB T1 학습 소스가 하나 더 생김. §5.1 규칙은 branch 단위라 동일 적용 가능하나 meta 유무 확인 필요 |

- 결론: 본 계획은 현 브랜치 기준으로 구현 가능하나, **uBTB 학습 소스 (#6237) 와 hit 판정 (#6009) 은 upstream 과 이미
  갈라져 있음**. 2-taken 코드가 `UseFastTrain`/`finalPrediction` 에 의존하므로 upstream 동기화 시 §7 학습 경로와
  `pairPrev` 스냅샷 (fastTrain 전용) 을 재검토해야 함. 구현 순서 권고: (a) #6009/#70348f452 (s0 tag 비교 + hitT1Victim) 를
  먼저 현 트리에 반영, (b) #6237 은 pair 학습과의 상호작용 (resolve 소스 cycle 에 `t1_promoteConflict` 증가, pair
  content 갱신 누락) 을 별도 문서로 정리한 뒤 반영, (c) 그 위에 본 계획 Phase 1~4.

*문서 끝.*
