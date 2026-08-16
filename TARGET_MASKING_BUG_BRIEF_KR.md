# 예측 target 검증 마스킹 버그 — 원인 및 수정 방향

## 1. 증상

- difftest 레지스터 값 불일치. 실패 시점까지 PC 스트림은 일치.
- 루프 카운터 계열 레지스터(a6, a3 등)가 기준 모델 대비 정확히 1씩 작음 — 루프 1회 누락.
- `EnableTwoTaken = false`에서는 미재현.

## 2. 원인

backend의 분기 target 검증은 `pcMem[ftqIdx+1]` 읽기 값을 실제 계산 target과 비교하는
구조 (`backend/CtrlBlock.scala:245-252` → `backend/datapath/DataPath.scala:703` →
`backend/fu/wrapper/BranchUnit.scala:55`). 해당 읽기에 슬롯의 최신성(현재 fetch 스트림
소속 여부)을 확인하는 가드가 부재. 2-taken pair enqueue와 s3Override가 결합하면 낡은
값이 실제 target과 일치하는 상황이 성립함.

엔트리 N = 블록 A, N+1 = 블록 B, A의 분기 실제 target = B일 때:

| 시점 | 사건 |
|---|---|
| t | pair enqueue: `pcMem[N+1] := B` (`ftq/Ftq.scala:415-419`, `CtrlBlock.scala:752-757`) |
| t+2 | s3Override가 A의 target을 오류 값 T'로 변경. `bpuPtr := s3FtqPtr+1` 롤백하나 pcMem 쓰기는 N에만 발생 (`Ftq.scala:218-220`, `:409-411`) |
| ~ | A-B-A 루프로 FTQ 만충 → `prediction.ready` 비활성 (`Ftq.scala:175-178`) → BPU park → N+1 재-enqueue 지연, `pcMem[N+1] = B` 방치 |
| t+10 | A의 분기 issue. 예측 target으로 낡은 B를 latch. 실행 결과 target도 B → 방향 일치 + target 일치 → redirect 미발생 |

실제 fetch는 T'를 따라갔으므로 wrong-path 명령이 커밋됨. T'가 정상 스트림과 재수렴하는
주소였기 때문에 PC 불일치 없이 레지스터 값만 1 iteration 분 어긋난 형태로 관측됨.

요지: 방향 오예측은 uop에 동반되는 예측 비트와 비교하므로 마스킹 불가
(`BranchUnit.scala:56`). target 비교만 pcMem이라는 외부 상태에 의존하여 마스킹 가능.

## 3. 수정 방향 1 — s3Override 시 후속 pcMem 슬롯 즉시 갱신 (Fix A)

**브랜치**: `feat/p3_2taken_2fetch_fpga` @ `f08f1930c` (`frontend/ftq/Ftq.scala` 단일 지점)

s3Override 사이클에 pair용 pcMem 쓰기 포트로 `pcMem[s3FtqPtr+1] := prediction.bits.target`
(= 신규 target T') 기록. `pairEnq`와 `s3Override`는 상호 배타(XSError 보장,
`Ftq.scala:195-198`)이므로 해당 포트는 override 사이클에 항상 유휴.

**해결 근거**

- 마스킹의 직접 원인은 N+1 슬롯에 잔류한 구 pair 값 B. override 사이클에 T'로 갱신하면
  backend 비교가 실제 target B vs 예측 T'가 되어 불일치 검출, 정상 redirect 발생.
- 갱신 시점이 override 사이클로 고정되므로 이후 BPU park·재-enqueue 지연과 무관.
  버그 성립 조건(park) 자체를 무력화.
- redirect target은 backend가 계산한 실제 값이므로 항상 안전. override가 옳았던 경우는
  후속 enqueue와 동일 값의 중복 쓰기로 무해.

**한계**: s3Override 경로만 차단. flush형 redirect(load replay 등)로 롤백된 슬롯의 잔류
문제는 미해결. pair 포트가 존재하는 2-taken 빌드 한정.

## 4. 수정 방향 2 — predecode 단계 direct 분기 target 검증

**브랜치**: `feat/p3_2taken_predecode_targetfault` @ `61030dceb`
(`ftq/Bundles.scala`, `ftq/Ftq.scala`, `frontend/Bundles.scala`, `ifu/Ifu.scala`,
`ifu/PredChecker.scala`, `ifu/IfuPerfAnalysis.scala`)

1. `FtqEntry`에 `target` 필드 추가. 쓰기는 기존 enqueue 블록 단일 지점이며 s3Override도
   동일 블록을 경유하므로 override된 target이 자동 반영됨.
2. IFU s1에서 fetch block의 ftqIdx로 FTQ 질의 → `entryQueue[ftqIdx].target` 조합 응답 →
   s2에서 PredChecker로 전달.
3. PredChecker가 taken 예측된 jal/br(immediate로 target 계산 가능한 분기)에 대해 디코드
   target과 비교, 불일치 시 기존 remask fault와 동일 경로로 redirect.
4. 재학습: predecode가 경로를 정정하면 backend resolve에 mispredict가 서지 않아 MainBtb가
   갱신되지 않음. `ifuTargetFix` 사이드카로 해당 엔트리 train 시 taken 분기의 `mispredict`를
   강제. 학습 값은 backend resolve(ground truth) 그대로 사용.

**해결 근거**

- 검증 경로가 pcMem과 완전 분리. 프론트엔드가 자기 예측(FTQ 엔트리)과 자기 디코드 결과를
  비교하므로 backend 비교의 마스킹 여부와 무관하게 검출.
- target 소스가 FTQ 엔트리 자신이므로 BPU park·enqueue 진행 상태와 무관. (다음 엔트리
  startPc로부터의 유추 방식은 override 롤백 + FTQ 만충 시 판독 불가하여 기각.)
- 본 시나리오 적용 시: A는 override된 예측(T')으로 인출, 엔트리 A의 `target` = T'.
  predecode가 A의 분기를 디코드하여 B 획득, T'와 비교 → 불일치 → wrong-path 명령의
  IBuffer 진입 이전에 B로 redirect. backend 도달 이전에 차단.
- 오탐 무해성: 검사 대상이 direct 분기뿐이므로 redirect target은 디코드된 ground truth.
  오발 시 손실은 정확성이 아닌 IPC.

**한계**: jalr/ret는 immediate로 target 산출 불가 — backend 비교가 유일한 방어.

## 5. 두 수정의 관계

독립적 이중 방어이며 차단 지점이 상이함.

| | Fix A | predecode 검증 |
|---|---|---|
| 차단 위치 | backend 비교 입력(pcMem) 정정 | 프론트엔드 자체 검출 |
| 검출 시점 | 분기 issue (~10+ cycle) | IFU s2 (~3 cycle) |
| s3Override 경로 | 차단 | 차단 |
| redirect 롤백 경로 | 미차단 | 차단 (direct 분기 한정) |
| jalr / ret | 커버 | 미커버 |

direct 분기는 predecode 검증이, indirect 분기는 pcMem 정정이 담당하는 구조. 잔여 구멍
(전체 롤백 경로 × indirect)의 근본 차단은 backend 후속 슬롯 유효성 가드 + 불확실 시
mispredict 강제 방안이 필요 (`PCMEM_STALE_TARGET_FIX_PLAN_KR.md` Fix B/C, 미구현).

## 6. 상태

- 전 커밋 컴파일 미검증 (본 환경에 mill/java 부재). `mill -i xiangshan.compile` 선행 필요.
- 상세 설계: `PCMEM_STALE_TARGET_FIX_PLAN_KR.md`(수정 방향 전체 비교),
  `PREDECODE_TARGETFAULT_PLAN_KR.md`(방향 2 설계·레이스 분석).
