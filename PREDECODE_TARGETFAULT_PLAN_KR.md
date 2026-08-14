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

- 예측 target은 원래 IFU에 전달되지 않았다: `FetchRequestBundle.target`은 미구동 필드이고,
  `FtqEntry`는 startPc + takenCfiOffset만 저장했다.
- **다음 엔트리의 startPc로 유추하는 방식은 기각**: 그 값은 다음 엔트리가 enqueue된 뒤에만
  읽을 수 있는데, s3Override는 `bpuPtr := s3FtqPtr + 1`로 되감아 (`Ftq.scala:218-220`) 해당
  블록을 최신 엔트리로 만들고, FTQ full이면 `prediction.ready`가 죽어 (`Ftq.scala:175-178`)
  그 상태가 커밋으로 자리가 날 때까지 유지된다. 즉 **확정된 버그 상황에서 정확히 읽을 수
  없는** 값이다.
- fault 처리(IBuffer 롤백, half-RVI 복구, prevIBufEnqPtr 복원)는 전부 IFU 내부
  `wbRedirect` 머신에 묶여 있어 (`Ifu.scala:746-765`), 검출은 반드시 **PredChecker 안**에서
  기존 fault와 같은 방식으로 일어나야 한다. FTQ에서 비교하는 설계는 IBuffer 롤백을
  재구현해야 해서 기각.

**결정**: `FtqEntry`에 `target` 필드를 추가해 엔트리가 자기 예측 target을 들고 있게 한다.
IFU가 s1에서 fetch block의 ftqIdx로 질의 → FTQ가 `entryQueue[ftqIdx].target`을 조합으로
응답 → s2에서 레지스터 후 PredChecker에 공급 → PredChecker가 디코드 target(`jumpTargets`)과
비교해 targetFault를 **기존 remask fault와 동일하게** 처리. enqueue 여부에 의존하지 않으므로
스킵 조건이 없다.

## 3. 변경 내용

### 3.1 검출 + redirect

**`frontend/ftq/Bundles.scala`**
- `FtqEntry`에 `target: PrunedAddr` 추가 (엔트리당 `VAddrBits - instOffsetBits` 비트 × FtqSize=64)

**`frontend/Bundles.scala`**
- `IfuToFtqIO`: `predTargetQuery: Vec(FetchPorts, FtqPtr)` 추가 (IFU→FTQ, s1 질의)
- `FtqToIfuIO`: `predTarget: Vec(FetchPorts, PrunedAddr)` 추가 (조합 응답)

**`frontend/ftq/Ftq.scala`**
- enqueue 시 `entryQueue(predictionPtr).target := prediction.bits.target` — 이 쓰기 블록은
  일반 enqueue와 s3Override를 모두 덮으므로 (`predictionPtr`이 override 시 s3FtqPtr) override된
  target이 자동 반영된다. in-flight 갱신 로직 불필요.
- pair: first의 target은 `p.secondStartPc`(= second의 시작 = first의 target)로, second의 target은
  `p.secondTarget`(`bpu/Bundles.scala:198`)으로 명시 지정 — 상위 `prediction.bits.target`은 pair
  전체를 기술하므로 first에 그대로 쓰면 안 된다.
- 질의 응답: `resp := entryQueue(query.value).target`
- **레이스 분석**: 엔트리 A가 IFU s1에 있을 때 `entryQueue[A].target`이 다른 스트림 값으로
  바뀌려면 bpuPtr이 A 이하로 롤백해야 한다. override 도달 범위는 `bpuToPfSafeDist =
  1 + bpuS2EnqNum + bpuS3EnqNum` (`Ftq.scala:334-335`)로 bpuPtr에서 최대 3엔트리인데, FTQ
  enqueue → ICache prefetch/mainpipe(3단) → IFU s0 까지 최소 4~5 cycle이 걸리므로 IFU s1의
  블록은 항상 override 사거리 밖이다 (기존 설계가 `flushFromBpu`를 s0에서만 검사하는 근거와
  동일, `Ifu.scala:139`). backend/IFU redirect는 IFU 파이프라인을 전부 flush
  (`Ifu.scala:114-117`). ⇒ s1 질의 결과는 wb까지 유효하다.

**`frontend/ifu/Ifu.scala`**
- s1: `predTargetQuery(i) := s1_fetchBlock(i).ftqIdx`
- s2: 응답을 `RegEnable(_, s1_fire)`로 latch → `checkerIn.bits.blockPredTarget`으로 전달.
  valid는 `s2_fetchBlock(i).valid && takenCfiOffset.valid`이며, ICache exception 블록과
  uncache는 해제 (garbage 디코드로 인한 오발 방지)

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

### 3.2 재학습 경로

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
  확인 필요 (TODO(timing)). 더 줄이려면 `FtqFetchReq`/`MainPipeToIfuReq`에 target을 실어
  ICache 파이프라인으로 흘리면 되지만, WayLookup 큐에 저장이 추가된다.

## 4. 한계 (문서화)

1. jalr/ret target은 predecode로 검증 불가 — backend pcMem 비교가 유일한 방어 (Fix A/C).
2. pair-second 엔트리의 train은 억제되므로 (`Ftq.scala:479`) pair-second에서 세운
   사이드카는 MBTB에 못 닿는다. s3Override는 pair-first만 덮으므로 확정 버그 경로는 커버.
3. 강제 mispredict는 TAGE/SC 등 다른 트레이너에도 mispredict로 보인다 — 의미상 참
   (BPU가 실제로 틀렸음)이므로 허용.

## 5. 검증

1. `mill -i xiangshan.compile` (사용자 실행)
2. difftest 재현 바이너리: Fix A를 끄고(리버트 빌드) 이 브랜치 단독으로도 통과하는지 —
   두 방어선의 독립 검증
3. perf: `predecodeTargetFault*` 카운터로 발화 빈도, `trainForceMispredictIfuFix`로
   재학습 동작, 반복 fetch에서 카운터가 1회성으로 수렴하는지 (재학습 성공의 지표)
4. coremark IPC 회귀 (오발 시 IPC 하락으로 드러남)
