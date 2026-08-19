# aBTB/uBTB 잔존 ghost 엔트리 무효화 설계

> 상태: 방법 A 구현은 7절의 한계(utage 우회) 발견으로 롤백함 (커밋/브랜치 제거, 2026-08-19).
> 방법 B2(8절) 구현 완료 (2026-08-19, Bundles/Bpu/AheadBtb). 컴파일/시뮬 검증 대기.
> 8.2의 mBTB 결과-무효 사이클 리스크는 해소 확인 — read가 write에 무조건 우선
> (`MainBtbInternalBank.scala:144,157`: write buffer는 read 없는 사이클에만 drain) → 게이팅 불필요.

## 1. 배경

- 커밋 `6e9bf46ab`(pdFlushVC 적용)으로 predecode NotCfiTaken/InvalidTaken 리다이렉트 시 mBTB SRAM way + VC 엔트리 무효화 완료.
- 잔존 문제: 동일 ghost가 uBTB/aBTB에 남아 있는 경우 처리 경로 부재.
  - mBTB 무효화 후 S3 최종 예측이 not-taken으로 교정됨 → predecode 리다이렉트 자체가 소멸 → pdInvalidate 재발사 불가.
  - 이후 해당 블록 fetch마다: S1 ghost taken 예측 → S3 불일치 → `s3_override`(`Bpu.scala:437`) 버블 반복.

## 2. 현재 동작 분석

### 2.1 uBTB — 자가 치유 가능

- fastTrain은 taken 여부 무관하게 매 `s3_valid` 발사 (`Bpu.scala:185`).
- hit && `!t1_actualTaken` 시 usefulCnt 감소, notUseful 도달 시 엔트리 재초기화 (`MicroBtb.scala:248,256`).
- 결론: 해당 블록 수 회 통과 후 ghost 자연 소멸. 즉시성만 부족.

### 2.2 aBTB — 자연 소멸 경로 없음

- 학습 게이트: `t0_fire = fastTrain.valid && finalPrediction.taken && abtbMeta.valid` (`AheadBtb.scala:226`).
  → **not-taken final로는 학습 자체가 발동하지 않음.** counter 감소·무효화 모두 불가.
- 예측 시 direct/indirect attribute way는 counter 무관 무조건 taken 취급 (`Bpu.scala:294-295`).
  conditional만 utage direction으로 억제 가능 (`Bpu.scala:296`).
- 결론: direct/indirect ghost는 set 충돌로 replace될 때까지 무기한 잔존, s3_override 반복 유발.

### 2.3 구조적 제약

- aBTB는 **ahead 인덱싱**: set/bank는 직전 블록 startPc(`AheadBtb.scala:110-113`), tag는 현재 startPc(`AheadBtb.scala:169`).
  → cfiPc 단독으로는 엔트리 위치 특정 불가. 무효화에는 예측 시점 `AheadBtbMeta`(setIdx/bankMask/way별 hit·position) 필요.
- `AheadBtbMeta`는 BPU 내부 fastTrain 경로에만 존재 (`Bpu.scala:188`). `BpuResolveMeta`(FTQ metaQueue)에는 미포함
  (`bpu/Bundles.scala:309-319` — mbtb/tage/sc/ittage/phr/commonHR/utage만).
  → pdInvalidate를 aBTB로 확장하려면 FTQ 저장 폭 증가 필수.

## 3. 방법 후보

### 방법 A — aBTB fastTrain 확장 (not-taken 학습 + ghost way 무효화)

- 변경 1: t0 게이트 완화 — `finalPrediction.taken` 조건 제거 (`AheadBtb.scala:226`).
  - 기존 t1 counter 경로의 `needDecrease`는 이미 `!t1_trainTaken` 처리 (`AheadBtb.scala:264`)
    → 게이트 완화만으로 conditional ghost는 counter 감소로 억제.
- 변경 2: ghost way 무효화 로직 신설 (t1).
  - `t1_ghostMask = meta.entries.map(e => e.hit && (e.attribute.isDirect || e.attribute.isIndirect) && (!t1_trainTaken || e.position < t1_trainPosition))`
  - 매치 way에 zero-entry write (multi-hit 무효화 경로 `AheadBtb.scala:314-319`와 동일 방식, `needResetCtr = true`).
- 변경 3 (완화의 부수 수정, 필수):
  - `t1_needWriteNewEntry := !t1_hit && t1_trainTaken` — not-taken final에서의 오할당 방지 (`AheadBtb.scala:279`).
  - `t1_needCorrectTarget`에 `t1_trainTaken` 조건 추가 — not-taken indirect의 무의미한 target 교정 방지 (`AheadBtb.scala:286`).
- write 포트 경합: bank write mux(alloc > targetCorrect > multiHit)에 4번째 arm 추가, 최후순위.
  - not-taken final 사이클엔 alloc/targetCorrect 비활성 → 포트 자유. taken+posBefore ghost만 경합 가능하며,
    ghost는 반복 관측되므로 다음 학습 기회에 처리됨 — 유실 무해.
- 정당성: aBTB hit인데 S3 final이 not-taken(또는 더 앞 position taken)인 direct/indirect way는
  mBTB 미백업 상태 확정 → 존재 자체가 매번 override 유발 → 제거가 항상 이득. 오삭제 부작용 없음.
- 커버리지: predecode ghost뿐 아니라 mBTB capacity eviction 등 **모든 mBTB-aBTB 불일치**를 일반적으로 해소.
- 비용: AheadBtb 내부 국소 변경. FTQ 저장 증가 0. 신규 포트 0.

### 방법 B — pdInvalidate를 aBTB까지 배선 (정밀 1회 제거)

- 변경: `BpuResolveMeta`에 `abtb: AheadBtbMeta` 추가 → `PdInvalidateReq`에 abtbMeta 동봉 →
  AheadBtb에 pdKill 포트(setIdx/bankMask/wayMask) 신설, write mux arm 추가.
- 장점: 최초 predecode 리다이렉트 시점에 mBTB/VC/aBTB 동시 제거 — override 잔존 구간 0.
- 단점:
  - FTQ 저장 비용: FtqSize × AheadBtbMeta(setIdx + bankMask + NumWays×(hit+attribute+position+targetLowerBits)) — way당 수십 비트.
  - 커버리지 한계: predecode 리다이렉트가 뜨는 최초 1회에만 유효. capacity eviction발 불일치는 못 다룸.
  - 방법 A 채택 시 이득이 "override 수 회 절약"에 그침 — 저장 비용 대비 미미.

### 방법 C — uBTB 즉시 kill (pdInvalidate CAM 제거)

- 변경: pdInvalidate에 블록 startPc 동봉(Ftq `entryQueue(ftqIdx).startPc` 기존 보유) →
  MicroBtb에 tag-match CAM kill 추가 (엔트리가 레지스터라 조합 매치 가능, 기존 `mispKill` 경로 유사).
- 장점: uBTB ghost 즉시 제거 (자가 치유 대기 수 회 fetch 절약).
- 단점: 2.1의 자가 치유가 이미 존재 → 이득 소폭. slot2/pair 상호작용 검토 필요.

### 방법 D — 현상 유지 (baseline)

- ghost 잔존 허용, s3_override 버블 감수. aBTB는 set 충돌 replace 대기.
- predecode 리다이렉트(IFU 플러시)보다는 저비용이나, hot loop에서 상시 2~3사이클 손실.

## 4. 우선순위

| 순위 | 방법 | 근거 |
|------|------|------|
| 1 | **A. aBTB fastTrain 확장** | 유일한 무기한 잔존 경로(aBTB direct/indirect ghost)를 일반적으로 해소. FTQ 비용 0, 국소 변경, predecode 외 원인까지 커버 |
| 2 | **C. uBTB 즉시 kill** | 소규모·저위험 보강. A와 독립적으로 적용 가능. 이득 소폭이므로 선택 사항 |
| 3 | **B. pdInvalidate→aBTB 배선** | A 채택 시 잔여 이득이 override 수 회 절약뿐. FTQ 저장 비용 대비 열위 — A의 효과 측정 후 부족 시에만 재검토 |
| - | D. 현상 유지 | 비교 기준 |

- 권고: **A 단독 우선 구현** → perf 측정 → 필요 시 C 추가. B는 보류.

## 5. 검증 계획

- perf counter 신설: `abtb_ghost_invalidate` (t1 ghost 무효화 발생), 기존 `s3Override`(`Bpu.scala:756`) 전후 비교.
- 회귀 항목: `predict_hit_entry_num`/`predict_miss`(`AheadBtb.scala:339-340`) — 오삭제로 인한 hit율 하락 여부.
- 시뮬레이션: coremark + gcc(FPGA hang 재현 워크로드) — IPC/override 수 비교, difftest 통과 확인.
- 엣지 케이스: 2-taken 상호작용은 6절 분석으로 종결 — 시뮬레이션에서는 pair 발사율(`s1_usePair` 관련 카운터) 회복 여부만 확인.

## 6. 2-taken 상호작용 분석 (결론: 충돌 없음)

- 전제 조건 (필수): 완화된 t0_fire에 `abtbMeta.valid` 게이트 유지.
  - pair fire 직후 사이클은 ahead 체인 어긋남 → `s3_abtbMetaSkip`이 meta.valid를 강제 false(`Bpu.scala:506-510`)
    → 학습/무효화 모두 차단됨. 이 게이트까지 제거하면 어긋난 meta로 오무효화하는 실제 버그 발생.
- pair fire 블록의 S3 사이클: final taken이므로 기존과 동일 동작. 변화 없음.
- pair 거부(`s3_usePair && s3_override`, final not-taken) 사이클: 완화로 학습이 새로 발동하나,
  "final not-taken ⇒ mBTB 미백업" 논리가 pair 여부와 무관하게 성립 → 무효화 정당.
- pair-second 블록: aBTB 미관여 (pair-second는 uBTB slot2 출처, B 블록은 자기 startPc로 S1 미통과, `Bpu.scala:558`).
- uBTB pair 체인(pairPrev/promotion/fastTrainKill): AheadBtb 외부라 영향 없음.
- 부수 이득: aBTB ghost가 `s1_usePair`의 `(!s1_abtbValidEffective || abtbUbtbAgree)` 조건(`Bpu.scala:336`)으로
  pair 발사를 차단하던 것이 해소 → 2-taken 커버리지 회복.

## 7. 방법 A의 한계 (롤백 사유)

- S1 taken 판정: `Mux(s1_utageHitMask(i), s1_utageTakenMask(i), pred.bits.taken)` (`Bpu.scala:296`)
  → utage hit 시 aBTB counter 무시. counter 감소로는 conditional ghost 억제 불가.
- utage는 fastTrain 미사용 — slow path(`BpuTrain`, backend resolve)로만 학습 (`MicroTage.scala:208,234-236`).
  ghost position에는 resolve될 실제 분기가 없음 → utage가 not-taken을 학습할 기회 자체가 없음.
- 결론: utage 태그 aliasing으로 taken이 나오는 conditional ghost는 방법 A로 무기한 잔존.
  direct/indirect와 동급의 구멍 → posBefore 휴리스틱 대신 일반 규칙 필요.

## 8. 방법 B2 — mBTB 백업 검사 (채택안)

- 원리: aBTB는 mBTB taken 분기의 캐시. 일관성 규칙 "mBTB에 없으면 aBTB에도 없어야 한다"를 학습 시점에 강제.
- stale 판정: aBTB hit way인데 S3 mBTB 결과에 같은 position+attribute 엔트리가 없음 → attribute 불문 무효화.
  - `s3_mbtbResult`(`Bpu.scala:372`)는 VC 히트까지 머지된 결과 → VC 백업 엔트리는 보존됨.
  - legit not-taken conditional은 mBTB에 있으므로 보존. utage 우회 문제는 hit 자체가 사라져 해소.

### 8.1 변경 지점

1. `bpu/Bundles.scala` — `BpuFastTrain`에 mBTB 결과 요약 추가:
   `mbtbValidMask: Vec[NumBtbResultEntries, Bool]`, `mbtbPositions: Vec[..., UInt(CfiPositionWidth.W)]`,
   `mbtbAttributes: Vec[..., BranchAttribute]`. 배선만, 저장 없음 (fastTrain은 S3 조합 신호).
2. `Bpu.scala` — `fastTrain.bits.mbtb* := s3_mbtbResult`에서 할당 (기존 `s3_commonHRMeta` 할당(446-448)과 동일 패턴).
3. `AheadBtb.scala`:
   - t0 게이트 완화: `finalPrediction.taken` 제거, `abtbMeta.valid` 유지 (2-taken 보호, 6절 전제).
   - 가드: `t1_needWriteNewEntry`/`t1_needCorrectTarget`에 `t1_trainTaken` 추가 (방법 A와 동일, 필수).
   - `t1_backedMask(w) = ∃i: mbtbValidMask(i) && mbtbPositions(i) === meta.entries(w).position && mbtbAttributes(i) === meta.entries(w).attribute`
   - `t1_staleMask(w) = meta.entries(w).hit && !t1_backedMask(w)` → PriorityEncoder로 사이클당 1 way,
     write mux 최후순위 arm으로 zero-entry + needResetCtr 쓰기 (multi-hit 경로와 동일).
   - counter 감소(needDecrease)는 게이트 완화로 함께 활성화 — utage-miss fallback 정확도용으로 유지.
   - perf: `train_invalidate_unbacked` 신설.

### 8.2 리스크 / 검증 포인트

- mBTB S3 결과 신뢰성: mBTB lookup이 억제된 사이클(SRAM read 충돌 등)에 s3_mbtbResult가 전부 invalid로 보이면
  legit aBTB 엔트리를 오삭제 → mBTB 파이프라인에 결과-무효 사이클이 존재하는지 확인, 존재 시 유효 조건 게이팅 추가.
- position 인코딩: mbtb `cfiPosition`과 aBTB meta `position` 동일 폭(CfiPositionWidth) — 직접 비교 가능, 인코딩 일치 확인.
- attribute 비교 엄격성: 완전 일치 대신 position만 비교하는 완화안도 가능 — attribute-mismatch 엔트리도
  override 유발원이므로 완전 일치(엄격) 채택, 시뮬로 오삭제율 확인.
- 과도 삭제: mBTB capacity eviction 시 aBTB 동반 삭제 → 재학습 시 재할당 (방법 A와 동일한 과도 상태, 무해).
- 2-taken: 6절 분석 그대로 유효 (abtbMeta.valid 게이트가 pair 직후 어긋난 체인 차단).
- uBTB: 신규 리스크 없음 (검사 완료).
  - fastTrain 번들 확장은 uBTB가 소비하지 않는 필드 — 동작 무영향. uBTB t0는 원래 taken 게이트 없음 (`MicroBtb.scala:154`).
  - aBTB stale 제거 → S1이 uBTB 폴백(`Bpu.scala:354`): uBTB 동일 ghost는 자가 치유 경로가 병행 학습 중이라 잔존 시간 소폭.
  - pair 게이트 해제(`s1_usePair`): 오염된 uBTB pair가 발사될 수 있으나 confidence 임계치 + pair demotion/fastTrainKill/mispKill이 정리 — 과도 상태.
  - (별건, pre-existing) uBTB hit && not-taken final에서 usefulCnt 소진 시 `initEntryIfNotUseful`이 not-taken final의
    position/attribute/target(fallthrough성 정보)로 엔트리를 재초기화 (`MicroBtb.scala:238-240`) — B2로 노출 증가 없음, 추후 개선 후보.
- 측정: `train_invalidate_unbacked`, `s3Override` 전후, `predict_hit_entry_num` 회귀, pair 발사율 회복.
