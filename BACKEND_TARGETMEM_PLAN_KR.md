# 방안 A — backend 전용 targetMem 구현 계획

브랜치(예정): `feat/p3_2taken_backend_targetmem` (base: cb1a6c850 "fix(Ftq): fix backendExceptionPtr")

- base를 Fix A(f08f1930c) 직전 커밋으로 잡음 → Fix A가 포함되지 않아 리버트 커밋 불필요.
- targetMem은 **EnableTwoTaken 무관하게 무조건 추가** — pcMem[ftqIdx+1] 역산 구조는 vanilla에도
  존재하는 결함이므로 2T 전용 가드 불필요. 2T 조건부는 pair용 두 번째 write 포트만.

## 1. 목적

- backend 분기 target 검증이 `pcMem[ftqIdx+1]`(후속 엔트리의 startPc)을 역산 참조하는 구조 제거.
- 예측 target을 **entry 자신의 enqueue 사이클에** backend로 전송하고 **ftqIdx로 직접 인덱싱** —
  후속 enqueue 지연·롤백에 의한 stale 값 마스킹 클래스를 근본 차단.
- 커버 범위: 모든 롤백 leg(s3Override·backend redirect·park) × direct/indirect(jal/br/jalr/ret) 전부.
  predecode 검증(브랜치 2)이 못 다루는 jalr/ret 포함.

## 2. 현재 구조와 결함 (근거)

| 항목 | 위치 |
|---|---|
| target 읽기: `raddr := ftqPtr + 1` | `backend/CtrlBlock.scala:245-252` ("bjuTarget" 포트) |
| pcMem 쓰기: 후속 엔트리 enqueue 시점 | `CtrlBlock.scala:749-756` ← `Ftq.scala:425-443` |
| 소비: `sinkData.predTarget := targetPCRdata` | `backend/datapath/DataPath.scala:703` |
| 비교: `targetWrong = fixedTaken && taken && (real =/= pred)` | `fu/wrapper/BranchUnit.scala:55` |
| 비교(jalr): `targetWrong = real =/= pred` → needRedirect/needTrain | `fu/wrapper/JumpUnit.scala:42-44` |

- 결함: entry N의 target 정보가 entry N+1의 enqueue라는 별개 시점·롤백 가능한 이벤트로 전달됨.
  롤백 후 재-enqueue가 지연되면(FTQ full park) 낡은 값이 잔류하고, 실제 target과 우연히 일치 시
  오예측이 마스킹됨 (확정된 difftest 버그의 근원).
- 방향(taken) 검증은 예측 비트가 uop에 동반되어 마스킹 불가 — target만 외부 상태 의존인 비대칭.

## 3. 설계

### 3.1 원칙

- entry N의 예측 target은 entry N의 enqueue와 **같은 사이클**에 backend로 전송.
- backend는 FtqSize 크기의 전용 메모리(targetMem)에 저장, 분기 resolve 시 **ftqIdx로 읽음** (+1 제거).
- 세대 일관성 논증: 살아있는 uop의 entry는 그 uop의 fetch 이후 재-enqueue된 적 없음
  (재-enqueue는 s3Override(해당 fetch 시작 전) 또는 redirect(해당 uop flush)를 동반).
  따라서 `targetMem[ftqIdx]`는 항상 uop 자신의 세대 값 — stale 읽기가 구조적으로 불가능.

### 3.2 쓰기 값 (FTQ 측 mux — FtqEntry.target 쓰기와 동일 규칙)

| 경우 | targetMem[predictionPtr] | targetMem[predictionPtr+1] (pair 포트) |
|---|---|---|
| 일반 enqueue | `prediction.bits.target` | — |
| pair enqueue | `p.secondStartPc` (first의 target = second 시작) | `p.secondTarget` |
| s3Override | `prediction.bits.target` (= 새 target T') | — (pair는 override와 배타, `Bpu.scala:480`) |

- wen 조건은 기존 pcMem 첫 포트와 동일: `(prediction.fire || bpuS3Redirect) && !redirect.valid`
  (`Ftq.scala:425`). override 시 predictionPtr = s3FtqPtr이므로 targetMem[N]이 T'로 같은 사이클 갱신.
- 예측 not-taken 엔트리의 target = fallthrough — 기존 `pcMem[N+1]`(후속 startPc) 의미와 동일하며,
  비교는 predicted-taken && taken일 때만 유효하므로 semantics 변화 없음.

### 3.3 읽기

- "bjuTarget" read 포트(BrhCnt개)를 pcMem에서 targetMem으로 이동, `raddr := fromDataPathFtqPtr(i).value`
  (+1 제거). 읽기 시점·파이프 단계·소비처(DataPath 이후) 전부 불변.
- pcMem은 "bjuTarget" 포트 수(BrhCnt)만큼 read 포트 감소 → read mux 축소 (타이밍 이득).

## 4. 변경 내용 (파일별)

### 4.1 `frontend/ftq/Bundles.scala` — FtqToCtrlIO 확장

- `target: PrunedAddr` 추가 (첫 포트용, 무조건) — 기존 `startPc`와 나란한 이름.
- `pairTarget: Option[PrunedAddr]` 추가 (pair 포트용, EnableTwoTaken 한정) — 기존 `pairStartPc`와
  나란한 이름.

### 4.2 `frontend/ftq/Ftq.scala`

- `io.toBackend.target := Mux(pairEnq, pair.secondStartPc, prediction.bits.target)`
  (base 커밋에는 Fix A가 없으므로 pair 포트는 원형 `pairWen := pairEnq` 그대로).
- `io.toBackend.pairTarget.get := pair.secondTarget`.
- 낡은 `pcMem[N+1]`을 읽는 소비자가 사라지므로 pcMem은 명령 PC 재구성(`pcMem[자기 ftqIdx]`)
  용도만 남고, 그 값은 해당 entry의 재-enqueue가 항상 갱신.

### 네이밍 규칙 (전체 적용)

- 주변 기존 신호와 대구를 이루게 명명: `pcMem` ↔ `targetMem`, `startPc`/`pairStartPc` ↔
  `target`/`pairTarget`, read 인덱스 이름은 기존 `"bjuTarget"` 유지.
- 파이프라인 단계가 있는 새 신호는 저장소 규약대로 `sN_lowerCamelCase` (예: `s3_override` 스타일).
- 축약어 금지 (`succ` 류), 한 줄 주석.

### 4.3 `backend/CtrlBlock.scala`

- `pcMemRdIndexes`에서 `"bjuTarget" -> params.BrhCnt` 제거 (`:82`).
- targetMem 신설:
  `SyncDataModuleTemplate(PrunedAddr(VAddrBits), FtqSize, numRead = params.BrhCnt, numWrite = numPcMemWrite, "BackendPredTarget")`.
- 쓰기: 기존 pcMem 쓰기(`:749-756`)와 동일한 RegNext 정렬로
  포트0 `(wen, ftqIdx, target)`, 포트1(2T) `(pairWen, pairFtqIdx, pairTarget)`.
- 읽기: 기존 bjuTarget 루프(`:245-252`)를 targetMem으로 교체, `raddr := fromDataPathFtqPtr(i).value`,
  `toDataPathTargetPC(i) := targetMem.io.rdata(i)`.
- DataPath 이후(BranchUnit/JumpUnit 포함)는 **무변경** — 값의 출처만 바뀜.

### 4.4 인터페이스 배선

- `FtqToCtrlIO`는 Frontend→XSCore→Backend로 기존 `<>` 연결 경유 (pairWen 추가 때와 동일 경로) —
  번들 필드 추가 외 배선 작업 없음.

## 5. 브랜치 2(predecode targetFault)와의 상호작용 — 병합 시 참고

- targetMem[N]은 predecode redirect로 fetch가 정정되어도 **틀린 예측값 T'를 유지** (의도된 동작:
  예측이 틀렸다는 사실의 기록). 따라서 해당 분기의 backend resolve가 `targetWrong=true`로 서고:
  - MBTB 재학습이 backend 경로에서 자연 발생 → **`ifuTargetFix` 사이드카(강제 mispredict) 불필요해짐**.
  - 대가: 이미 정정된 스트림에 대해 backend redirect 1회(flushAfter) 추가 발생 — 이벤트당
    ~수십 사이클 버블, 정확성 무해. predecode 자체가 희귀 이벤트이므로 수용.
- 병합 순서 권장: targetMem 단독 검증 통과 후, 브랜치 2에서 사이드카 제거 형태로 병합.

## 6. 비용

- 스토리지: FtqSize(64) × VAddrBits ≈ 3.1K 플롭 (SyncDataModuleTemplate, FPGA에서 LUTRAM 매핑 가능).
- 배선: Frontend→Backend target 버스 1~2개(VAddrBits) 추가.
- read 포트 총량 불변(bjuTarget 포트가 pcMem→targetMem으로 이동), pcMem mux는 축소.

## 7. 한계

- entry 단위 target 1개 저장 — 엔트리 내 다중 분기 중 **taken 예측된 마지막 분기**만 target 비교
  대상이라는 기존 semantics 그대로 (not-taken 예측 분기의 실제-taken은 방향 비교가 커버).
- 예측과 실제가 모두 같은 wrong 값인 경우(예측 적중으로 판정)는 오예측이 아니므로 해당 없음.
- 포인터 가드 방안(Fix C 변형)과 달리 오발 redirect가 전혀 없는 대신 스토리지 비용 존재.

## 8. 구현·검증 순서

1. 새 브랜치 생성: `git checkout -b feat/p3_2taken_backend_targetmem cb1a6c850` — targetMem 구현
   단일 커밋 (Fix A 미포함 base라 리버트 불필요).
2. `mill -i xiangshan.compile` (사용자 실행 — 본 환경 mill 부재).
3. difftest 재현 바이너리 실행 — 기존 실패 케이스 통과 확인 (본 방안 단독 방어 검증).
4. 회귀: coremark IPC (semantics 불변이므로 변화 없어야 정상), 기존 mispredict/redirect 카운터 비교.
5. FPGA STA: targetMem read 경로는 기존 bjuTarget과 동일 단계이므로 신규 크리티컬 패스 없음 예상,
   빌드로 확인.
6. 통과 시 브랜치 2와 병합 (§5의 사이드카 제거 포함) 여부 결정.
