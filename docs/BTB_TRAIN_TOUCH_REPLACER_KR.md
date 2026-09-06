# BTB replacer 학습 시점 touch 전환 (#6348 반영, VC 확장)

> 브랜치 `feat/ubtb-upstream-sync`. 작성: 2026-09-06.
> 대상 upstream 커밋: `6de780379` perf(btb): update btb replacer using training touch strategy instead of
> prediction touch strategy (#6348), 2026-08-18. 본 트리의 replacer 인터페이스가 upstream 과 달라 (#6008 미반영)
> cherry-pick 대신 동일 의미로 직접 구현.

---

## 1. upstream 변경 요지

| 구조 | 이전 | #6348 |
| ---- | ---- | ----- |
| mBTB `MainBtbReplacer` | S3 `predict.touch` (최종 takenMask 의 taken way 전부) + T1 `train.t1_touch` (할당 way) | predict 경로 삭제. T1 touch 하나로 통합: 할당이면 할당 way, 아니면 **실제 taken 된 첫 way** |
| ABTB `AheadBtbReplacer` | S2 `readValid` (hit way 전부, 예측 시점) + `writeValid` (할당, writeResp) | S2 touch 삭제. T2 에서 `t2_hit && t2_trainTaken` 인 hit way 를 `readValid` 로 touch |

- 동기: 예측 시점 touch 는 (a) 투기 경로 (override/redirect 로 버려지는 블록) 까지 MRU 로 올리고, (b) S3 takenMask 는
  TAGE/SC 예측이지 실제 outcome 이 아님. 학습 시점 (resolve) 의 actual taken 으로 touch 하면 실제로 쓰인 엔트리만 MRU.
- 부수 효과: replacer state bank 의 predict read/write 포트가 놀게 되어 S3 경로의 state 읽기·쓰기 제거 (전력·타이밍).

---

## 2. 본 트리 구현

### 2.1 mBTB SRAM (`MainBtbAlignBank`, `MainBtbReplacer`)

```
t1_actualTakenMask(i) = ∃ branch: valid && taken && meta(i).rawHit && meta(i).position === branch.cfiPosition
t1_actualTakenOH      = PriorityEncoderOH(t1_actualTakenMask)

trainTouch.valid   = t1_fire && (t1_entryNeedWrite || t1_actualTakenMask.orR)
trainTouch.wayMask = Mux(t1_entryNeedWrite, t1_entryWayMask, t1_actualTakenOH)     // 항상 one-hot
```

- S3 `predictTouch` 삭제 (`s3_replacerSetIdx` 레지스터 포함). `io.s3_takenMask` 포트는 upstream 과 같이 유지 (미사용).
- `MainBtbReplacer`: `predictStateGen` 삭제, `touch: Vec(2)` → `trainTouch` 단일. state bank 의 predict 포트는 0 고정.
  victim 은 이전과 동일하게 T1 setIdx 의 현재 state 에서 계산 (touch 반영 전).
- 모든 align bank 가 자기 meta 와 branches 로 독립 판정. position 이 full position (posHigherBits 포함) 이라 다른 align
  bank 의 분기와는 매치되지 않음.
- 우선순위: 할당 (`t1_entryNeedWrite`) > actual taken. 할당 cycle 에 다른 way 가 taken 이었다면 그 touch 는 소실 —
  upstream 과 동일한 단순화.

### 2.2 mBTB VC (`MainBtb`, `MainBtbVictimCache`, `MainBtbVCReplacer`) — upstream 에 없는 확장

- 이전: S3 에서 `s3_takenMask` 의 VC slot 부분으로 `predTouch(s)` (vcIdx 는 S1→S3 파이프).
- 변경: T1 에서 `takenTouch(s)` 로 교체.

```
t1_vcSlotLive(s)  = t1_vcMetas(s).hit && t1Read(s).entry.valid && entry.vcTag === expectedVcTag(entry 의 AB)
actualTaken(s)    = ∃ branch: valid && taken && branch.cfiPosition === vcSlotMetas(s).position
takenTouch(s)     = t1_fire && t1_vcSlotLive(s) && actualTaken(s), bits = t1_vcMetas(s).vcIdx
```

- `t1_vcSlotLive` 는 기존 mispredict 매치 (`t1_vcSlotHits`) 에서 tag 검사를 분리한 것. VC 는 레지스터 파일이라 예측 후
  T1 까지 같은 vcIdx 가 insert/update 로 교체될 수 있어, tag 재검사로 다른 엔트리를 touch 하는 것을 막음.
- `MainBtbVCReplacer` 의 2-phase 구조 (taken touch → train touch 체인) 는 그대로. 이름만 `predTouch` → `takenTouch`.
  두 touch 가 이제 같은 T1 cycle 에 발생하므로 체인 순서가 실제 순서와 일치.
- S3 의 `s3_vcSlotInfos` 파이프 삭제.

### 2.3 ABTB (`AheadBtb`)

- 본 트리는 T2 stage 가 없고 T1 에서 bank write 를 발행하므로 T1 에서 touch:

```
readValid(i)   = t1_fire && t1_trainTaken && t1_bankMask(i) && t1_hit
readSetIdx     = t1_setIdx
readWayMask    = t1_hitMask          // position + attribute 가 일치한 hit way
```

- 할당 touch 는 기존대로 `writeResp` (write buffer 통과 후) 경유. 같은 set 에 두 touch 가 겹치면 `ReplacerState` 가
  train (write) 쪽을 우선 — 기존 동작.
- `t1_fire` 는 이미 `branch.taken` 을 요구하므로 `t1_trainTaken` 은 중복이지만 upstream 식과의 대응을 위해 유지.

### 2.4 uBTB

- 변경 없음. uBTB 의 `predTouch` 는 S1 hit 시 touch 로, #6348 범위 밖 (upstream 도 유지).

---

## 3. 2-taken / VC 와의 상호작용

- pair-second 슬롯은 resolve train 이 억제되어 (`Ftq.scala` `isPairSecond`) block B 의 taken 분기는 mBTB/VC replacer 를
  touch 하지 못함. 이전 S3 touch 에서도 block B 는 S3 를 통과하지 않아 동일하게 미touch 였으므로 변화 없음.
- fastTrain 은 mBTB 를 학습하지 않으므로 mBTB touch 소스는 resolve 뿐. #6237 (uBTB resolve+fast) 과 무관.
- VC Path C (SRAM 할당 시 evict → VC insert) 의 victim 은 `replacer.io.victim` — touch 정책 변경으로 victim 선택이
  "실제 taken 이력" 기준으로 바뀌므로 VC 로 밀려나는 엔트리 분포가 달라짐. `vc_train_insert`, `vc_lookup_hit` 로 관찰.

---

## 4. 확인 항목

1. `mill -i xiangshan.compile` (toolchain 없어 미검증).
2. `replacerTakenTouch` (신규) > 0, `allocate` 와의 비율.
3. mBTB `pred_hit` / `pred_miss`, ABTB `predict_hit`, VC `vc_lookup_hit` 의 baseline 대비 변화 — upstream #6348 의
   기대 방향은 hit 율 상승.
4. `MainBtbReplacer` assert (`train touch wayMask should be at-most-one-hot`) 무발화.

*문서 끝.*
