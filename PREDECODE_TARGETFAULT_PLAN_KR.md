# Predecode targetFault 검출 + 재학습 경로 설계

브랜치: `feat/p3_2taken_predecode_targetfault` (base: `feat/p3_2taken_2fetch_fpga` + Fix A f08f1930c)

## 1. 목적

S3 override가 taken CFI의 target을 틀리게 바꾼 경우(확정된 difftest 버그), backend의
pcMem 비교가 마스킹될 수 있다. Fix A(backend 방어)와 독립으로, **predecode 단계에서
직접 target을 검증**해 잘못된 fetch 경로를 조기에 끊는 두 번째 방어선을 만든다.
현 트리의 PredChecker는 방향성 fault(jal/jalr/ret 미-taken, notCFI, invalidTaken)만
검사하고 **target 검증이 없다** (`PreDecodeFaultType.TargetFault`는 enum에만 존재,
`ifu/Bundles.scala:47`; perf 카운터도 상수 false, `IfuPerfAnalysis.scala:129`).

## 2. 구조적 제약과 설계 결정

- 이 트리는 예측 target이 IFU에 전달되지 않는다: `FetchRequestBundle.target`은 미구동
  필드이고, `FtqEntry`는 startPc + takenCfiOffset만 저장 (`ftq/Bundles.scala:34-37`).
- 예측 target의 유일한 소스 = **successor FTQ 엔트리의 startPc** (다음 예측이 target에서
  시작하므로 구조적으로 동일).
- fault 처리(IBuffer 롤백, half-RVI 복구, prevIBufEnqPtr 복원)는 전부 IFU 내부
  `wbRedirect` 머신에 묶여 있어 (`Ifu.scala:746-765`), 검출은 반드시 **PredChecker 안**에서
  기존 fault와 같은 방식으로 일어나야 한다. FTQ에서 비교하는 설계는 IBuffer 롤백을
  재구현해야 해서 기각.

**결정**: IFU가 s1에서 각 fetch block의 ftqIdx를 FTQ에 질의 → FTQ가 조합논리로
`entryQueue[ftqIdx+1].startPc`(+ successor 존재 여부)를 응답 → s2에서 레지스터 후
PredChecker에 공급 → PredChecker가 디코드 target(`jumpTargets`)과 비교해 targetFault를
**기존 remask fault와 동일하게** 처리.

## 3. 변경 내용

### 3.1 검출 + redirect (commit 1)

**`frontend/Bundles.scala`**
- `IfuToFtqIO`: `nextEntryStartPcQuery: Vec(FetchPorts, Valid[FtqPtr])` 추가 (IFU→FTQ, s1 질의)
- `FtqToIfuIO`: `nextEntryStartPc: Vec(FetchPorts, Valid[PrunedAddr])` 추가 (조합 응답)

**`frontend/ftq/Ftq.scala`** — 질의 응답
- `resp.valid := query.valid && (query.bits + 1) < bpuPtr(0)` — 다음 엔트리가 아직 enqueue 안 됐으면
  invalid → 검사 스킵 (BPU park 직후 레이스 창; 이때는 Fix A가 커버)
- `resp.bits := entryQueue((q.bits + 1).value).startPc`
- **레이스 분석 (구현 후 검증)**: 블록 A가 IFU s1에 있을 때 `entryQueue[A+1].startPc`가
  다른 스트림 값으로 바뀔 수 있는가?
  1. s3Override가 A+1을 타겟하는 경우 — override는 그 엔트리의 takenCfiOffset/target만
     바꾸고 startPc는 s3_startPc(=s1이 넣은 값)로 그대로 다시 쓴다. 즉 startPc 불변.
  2. A+1의 startPc가 바뀌려면 bpuPtr이 A+1 이하로 롤백해야 하고, 이는 A 이하를 타겟하는
     override/redirect를 뜻한다. override 도달 범위는 `bpuToPfSafeDist = 1 + bpuS2EnqNum +
     bpuS3EnqNum` (`Ftq.scala:334-335`)로 bpuPtr에서 최대 3엔트리이며, FTQ enqueue → ICache
     prefetch/mainpipe(3단) → IFU s0 까지 최소 4~5 cycle이 걸리므로 IFU s1의 블록은 항상
     override 사거리 밖이다 (기존 설계가 `flushFromBpu`를 s0에서만 검사하는 근거와 동일,
     `Ifu.scala:139`). backend/IFU redirect는 IFU 파이프라인을 전부 flush (`Ifu.scala:114-117`).
  ⇒ s1 질의 결과는 wb까지 이 fetch 스트림에 대해 유효하다.

**`frontend/ifu/Ifu.scala`**
- s1: `nextEntryStartPcQuery(i) := {s1_valid && s1_fetchBlock(i).valid, s1_fetchBlock(i).ftqIdx}`
- s2: 응답을 `RegEnable(_, s1_fire)`로 latch, ICache exception 블록은 valid 강제 해제
  (garbage 디코드로 인한 오발 방지) 후 `checkerIn.bits.blockPredTarget`으로 전달

**`frontend/ifu/PredChecker.scala`**
- req에 `blockPredTarget: Vec(FetchPorts, Valid[PrunedAddr])` 추가
- `targetFaultVec(i) := instrValid && isPredTaken && !invalidTaken && (isJal || isBr) &&
  predTarget(blockSel(i)).valid && jumpTargets(i) =/= predTarget(blockSel(i)).bits`
  - jalr/ret은 target을 immediate로 알 수 없어 제외 (backend 몫으로 유지)
- `remaskFault`/`stage1Fault`에 편입 → 2-fetch에서 잘못된 target에서 온 block-1
  명령어들이 enqueue 단계에서 잘려나감; redirect target은 기존 `fixedIsJump` 경로가
  `jumpTargets`(디코드된 실제 target)를 선택 → 정확한 곳으로 refetch
- `faultType` MuxCase에 `PreDecodeFaultType.TargetFault` 추가

**`frontend/ifu/IfuPerfAnalysis.scala`** — `checkTargetFault` 실연결

### 3.2 재학습 경로 (commit 2)

문제: MBTB는 `train.branches`의 `mispredict=true`인 branch만 갱신한다
(`mbtb/MainBtb.scala:272`). predecode redirect가 fetch를 고쳐버리면 backend에서는
`targetWrong=false`(pcMem이 이미 정정됨)·방향 일치가 되어 resolve의 mispredict가 서지
않고 (`JumpUnit.scala:44`의 predTaken 메커니즘은 taken-방향 fault만 커버), MBTB의 틀린
target이 영원히 남아 **매 fetch마다 predecode redirect가 반복**된다.

**`frontend/ftq/Ftq.scala`**
- 사이드카 `ifuTargetFix: Vec(FtqSize, Bool)`:
  - set: ifuRedirect가 redirect mux에서 채택될 때 (`ifuRedirect.valid && !backendRedirect.valid`)
    + `taken && !attribute.isIndirect` (targetFault + jalFault 계열; jalFault는 predTaken
    경로와 중복이지만 무해)
  - clear: 해당 슬롯 enqueue 시(pair 두 슬롯 포함) + train fire 시
- train 출력 (`Ftq.scala:479-484`) 뒤에서: `ifuTargetFix(trainFtqIdx)`이면 taken branch의
  `mispredict := true.B` 강제 → MBTB가 backend가 계산한 실제 target으로 재학습
  (branches.target = backend resolve의 실제 target)
- BPU top의 first-mispredict 마스킹(`Bpu.scala:169-181`)과 일관: taken CFI는 엔트리의
  마지막 branch이므로 뒤 branch가 잘리는 부작용 없음

### 3.3 구현 노트

- 검출과 재학습은 한 커밋으로 넣는다: 검출만 있으면 MBTB가 안 고쳐져 같은 블록에서
  predecode redirect가 반복되는 열화 상태가 된다 (기능상 안전하지만 IPC 손실).
- 오탐의 안전성: 검사 대상이 direct CFI(jal/br)뿐이라 redirect target은 디코드된
  ground truth다. 즉 오탐은 **정확성이 아니라 IPC만** 해친다.
- 타이밍: s1의 ftqIdx(레지스터 출력) → FTQ entryQueue mux → IFU s2 레지스터 입력의
  모듈 간 조합 경로가 새로 생긴다. 기존 pfPtr/ifuPtr 읽기와 같은 급이지만 FPGA STA에서
  확인 필요 (TODO(timing)).

## 4. 한계 (문서화)

1. 다음 엔트리 미-enqueue 시(질의 시점에 BPU가 바로 뒤) 검사 스킵 — Fix A가 커버.
2. jalr/ret target은 predecode로 검증 불가 — backend pcMem 비교가 유일한 방어 (Fix A/C).
3. pair-second 엔트리의 train은 억제되므로 (`Ftq.scala:479`) pair-second에서 세운
   사이드카는 MBTB에 못 닿는다. s3Override는 pair-first만 덮으므로 확정 버그 경로는 커버.
4. 강제 mispredict는 TAGE/SC 등 다른 트레이너에도 mispredict로 보인다 — 의미상 참
   (BPU가 실제로 틀렸음)이므로 허용.

## 5. 검증

1. `mill -i xiangshan.compile` (사용자 실행)
2. difftest 재현 바이너리: Fix A를 끄고(리버트 빌드) 이 브랜치 단독으로도 통과하는지 —
   두 방어선의 독립 검증
3. perf: `predecodeTargetFault*` 카운터로 발화 빈도, `trainForceMispredictIfuFix`로
   재학습 동작, 반복 fetch에서 카운터가 1회성으로 수렴하는지 (재학습 성공의 지표)
4. coremark IPC 회귀 (오발 시 IPC 하락으로 드러남)
