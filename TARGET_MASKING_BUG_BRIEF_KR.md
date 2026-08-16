# 예측 target 검증 마스킹 버그 — 요약과 두 수정 방향

## 1. 증상

difftest에서 **레지스터 값 불일치**로 실패. PC 스트림은 실패 시점까지 일치했고,
루프 카운터 계열 레지스터(a6, a3 등)가 정답보다 정확히 1씩 작았다 — 루프가 한 바퀴 덜 돈
결과. `EnableTwoTaken = false`로는 재현되지 않는다.

## 2. 원인

backend는 분기의 예측 target을 **`pcMem[ftqIdx+1]`을 읽어** 실제 계산 target과 비교한다
(`backend/CtrlBlock.scala:245-252` → `backend/datapath/DataPath.scala:703` →
`backend/fu/wrapper/BranchUnit.scala:55`). 이 읽기에는 "그 슬롯이 현재 스트림 것인가"를
확인하는 가드가 없다. 여기에 2-taken의 pair enqueue와 s3Override가 겹치면 낡은 값이
**우연히 정답처럼 보이는** 상황이 만들어진다.

엔트리 N = 블록 A, N+1 = 블록 B, A의 분기 실제 target = B 라고 할 때:

| 시점 | 사건 |
|---|---|
| t | pair enqueue: `pcMem[N+1] := B` (`ftq/Ftq.scala:415-419` → `CtrlBlock.scala:752-757`) |
| t+2 | s3Override가 A의 target을 틀린 T'로 변경. `bpuPtr := s3FtqPtr+1`로 롤백하지만 **pcMem 쓰기는 N에만** 나감 (`Ftq.scala:218-220`, `:409-411`) |
| ~ | A-B-A 루프로 FTQ가 차서 `prediction.ready`가 죽고 BPU park (`Ftq.scala:175-178`) → N+1이 재-enqueue되지 않아 `pcMem[N+1]`은 **B인 채로 방치** |
| t+10 | A의 분기 issue: 예측 target으로 낡은 B를 읽음. 실행 결과도 B → **방향 일치 + target 일치 → redirect 없음** |

fetch는 실제로 T'를 따라갔으므로 wrong-path가 그대로 커밋된다. T'가 정상 스트림과
재수렴하는 주소였기 때문에 PC 불일치 없이 **레지스터 값만 한 iteration 분 어긋난** 것이다.

핵심은 **"틀린 예측인데 검증이 통과했다"** 는 점이다. 방향 오예측은 uop이 들고 다니는
예측 비트와 비교하므로(`BranchUnit.scala:56`) 절대 마스킹되지 않지만, **target 비교만은
pcMem이라는 외부 상태에 의존**해서 마스킹될 수 있다.

## 3. 수정 방향 1 — s3Override 시 후속 pcMem 슬롯 즉시 갱신 (Fix A)

**브랜치**: `feat/p3_2taken_2fetch_fpga` @ `f08f1930c` (`frontend/ftq/Ftq.scala` 한 곳)

s3Override 사이클에 pair용 pcMem 쓰기 포트로 `pcMem[s3FtqPtr+1] := prediction.bits.target`
(= 새 target T')을 기록한다. `pairEnq`와 `s3Override`는 상호 배타이므로
(XSError로 보장, `Ftq.scala:195-198`) 그 포트는 override 사이클에 항상 비어 있다.

**왜 이 버그가 해결되는가**

- 마스킹의 직접 원인은 "N+1 슬롯이 옛 pair의 값 B를 들고 있는 것"이다. override 사이클에
  그 슬롯을 T'로 덮으면, backend의 비교는 **실제 target B vs 예측 T'** 가 되어 불일치가
  드러나고 정상적으로 redirect가 발생한다.
- 갱신 시점이 **override 사이클로 확정**되므로, 이후 BPU가 park되든 N+1 재-enqueue가
  얼마나 늦어지든 무관하다. 즉 이 버그가 성립하던 조건(park) 자체를 무력화한다.
- redirect는 backend가 **계산한 실제 target**으로 가므로 항상 안전하다. S3 override가
  맞았던 경우에는 다음 enqueue가 같은 값을 다시 쓰는 중복 쓰기라 무해하다.

**한계**: s3Override 경로만 닫는다. redirect(특히 load replay 같은 flush형)로 롤백된
슬롯이 낡은 채 남는 같은 계열의 구멍은 남아 있다. pair용 포트가 있는 2-taken 빌드 한정.

## 4. 수정 방향 2 — predecode에서 direct 분기 target 직접 검증

**브랜치**: `feat/p3_2taken_predecode_targetfault` @ `b71d9bbcb`
(`ftq/Bundles.scala`, `ftq/Ftq.scala`, `frontend/Bundles.scala`, `ifu/Ifu.scala`,
`ifu/PredChecker.scala`, `ifu/IfuPerfAnalysis.scala`)

1. `FtqEntry`에 `target` 필드를 추가해 엔트리가 자기 예측 target을 들고 있게 한다.
   쓰기는 기존 enqueue 블록 한 곳이며, 이 블록이 s3Override도 덮으므로 override된 target이
   자동 반영된다.
2. IFU가 s1에서 fetch block의 ftqIdx로 FTQ에 질의 → `entryQueue[ftqIdx].target` 응답 →
   s2에서 PredChecker로 전달.
3. PredChecker가 taken으로 예측된 **jal/br(디코드로 target을 계산할 수 있는 분기)** 에 대해
   디코드된 target과 비교하고, 불일치면 기존 remask fault와 동일하게 redirect한다.
4. 재학습: predecode가 경로를 고치면 backend resolve에 mispredict가 서지 않아 MainBtb가
   갱신되지 않으므로, `ifuTargetFix` 사이드카로 해당 엔트리의 train에서 taken 분기의
   `mispredict`를 강제한다. 학습 값 자체는 backend resolve(ground truth)를 그대로 쓴다.

**왜 이 버그가 해결되는가**

- 검증이 **pcMem 경로를 아예 쓰지 않는다.** 프론트엔드가 자기 예측(FTQ 엔트리)과 자기
  디코드 결과를 비교하므로, backend 비교가 어떤 이유로 마스킹되든 독립적으로 잡힌다.
- target 소스가 FTQ 엔트리 **자신**이라 BPU park·enqueue 진행 상태와 무관하다. (다음 엔트리의
  startPc에서 유추하는 방식은 override 롤백 + FTQ full일 때 정확히 읽을 수 없어 기각했다.)
- 위 시나리오에 대입하면: A는 override된 예측(target T')으로 인출되고, 엔트리 A의 `target`도
  T'다. predecode가 A의 분기를 디코드해 B를 얻고 T'와 비교 → 불일치 → **wrong-path 명령이
  IBuffer에 들어가기 전에** B로 redirect한다. backend까지 갈 필요가 없다.
- 오탐이 구조적으로 무해하다: 검사 대상이 direct 분기뿐이라 redirect target은 디코드된
  ground truth다. 잘못 걸려도 정확성이 아니라 IPC만 손해다.

**한계**: jalr/ret는 target을 immediate로 알 수 없어 검증 불가 — 이쪽은 backend 비교가
유일한 방어다.

## 5. 두 수정의 관계

독립적인 두 겹의 방어이며, 막는 지점이 다르다.

| | Fix A | predecode 검증 |
|---|---|---|
| 막는 위치 | backend 비교의 입력(pcMem)을 정정 | backend 비교에 의존하지 않고 프론트엔드에서 검출 |
| 검출 시점 | 분기 issue (~10+ cycle) | IFU s2 (~3 cycle) |
| s3Override 경로 | 닫힘 | 닫힘 |
| redirect 롤백 경로 | 열림 | 닫힘 (direct 분기 한정) |
| jalr / ret | 커버 | 커버 못 함 |

즉 **direct 분기는 predecode가, indirect 분기는 pcMem 정정이** 책임지는 구조가 된다.
남은 일반적 구멍(모든 롤백 경로 + indirect)을 근본적으로 닫으려면 backend에 후속 슬롯
유효성 가드를 넣고 불확실하면 mispredict를 강제하는 방안이 필요하다
(`PCMEM_STALE_TARGET_FIX_PLAN_KR.md`의 Fix B/C, 미구현).

## 6. 상태

세 커밋 모두 이 환경에 mill/java가 없어 **컴파일 미검증**이다. 빌드 머신에서
`mill -i xiangshan.compile`을 먼저 돌려야 한다. 상세 설계는
`PCMEM_STALE_TARGET_FIX_PLAN_KR.md`(수정 방향 전체 비교)와
`PREDECODE_TARGETFAULT_PLAN_KR.md`(방향 2의 설계·레이스 분석)에 있다.
