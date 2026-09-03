# Upstream VBTB 설계의 로컬 VC 반영 계획

## 1. 목적

- `docs/UPSTREAM_VICTIM_BTB_ANALYSIS_KR.md` 에서 정리한 upstream `feat-victim-btb-rebase` 설계 중, 로컬 VC(`MainBtbVictimCache`) 에 이득이 되는 부분만 선별 반영
- 로컬의 핵심 선택(결과 슬롯 추가, fetch block 미절단, invalid-first PLRU, predecode 무효화)은 유지
- 대상 브랜치: `feat/p3_2taken_backend_targetmem`. 현재 `VCSize` 기본값 0 (`mbtb/Parameters.scala:39`) 으로 VC 는 컴파일 제외 상태, 평가 시 config 로 활성화 필요

## 2. 로컬 VC 현황 (사실 정리)

| 항목 | 현재 로컬 | 근거 |
|---|---|---|
| 구조 | AlignBank 공유 fully-assoc `VCSize` entry 레지스터, 1개 인스턴스 | `MainBtb.scala:57`, `MainBtbVictimCache.scala:85` |
| 태그 | `vcTag = tag ‖ setIdx ‖ internalBankIdx ‖ alignBankIdx` (27b) | `Parameters.scala:68` |
| 예측 | S1 AlignBank 별 CAM, top-2 hit, 슬롯 2개 추가 (`NumBtbPredEntries = 10`) | `MainBtb.scala:123-169`, `bpu/Parameters.scala:91-93` |
| evicted 획득 | 예측 시 meta 에 `sramValid/sramTag/targetLowerBits/targetCarry` 저장, T1 에서 재구성 | `Bundles.scala:75-79`, `MainBtb.scala:361-379` |
| T1 VC hit 판정 | FTQ meta 의 `vc(s).hit/vcIdx` 로 엔트리 읽고 tag 재검증 | `MainBtb.scala:298-320` |
| VC 카운터 학습 | **mispredict 시에만** `getUpdate(mispredict.taken)` | `MainBtb.scala:345-356` |
| 중복 제거 | T1 mispredict && SRAM hit 시 CAM invalidate (Path A), insert 시 동일 엔트리 덮어쓰기 | `MainBtb.scala:328-333`, `MainBtbVictimCache.scala:142-149` |
| replacer | PLRU, invalid-first | `MainBtbVCReplacer.scala:58-62` |

## 3. 반영 후보 및 우선순위

| 순위 | 항목 | upstream 근거 | 기대 효과 | 비용 / 위험 |
|---|---|---|---|---|
| P1-a | VC 카운터를 매 resolve 마다 학습 | `MainBtbAlignBank.scala:376-390` | VC 예측 conditional 의 방향 정확도 개선, MainBtb 와 동일한 hysteresis | 낮음. 기존 `t1Read` 경로 재사용 |
| P1-b | T1 VC hit 을 meta 의존 없이 VC CAM 으로 판정 | `MainBtbAlignBank.scala:289-295` | 예측 후 삽입된 엔트리, top-2 밖 엔트리도 in-place 갱신, SRAM 중복 할당 방지. FTQ meta 에서 `vc(hit,vcIdx)` 제거 가능 | T1 에 CAM 1세트 추가 (VCSize × 27b + position) |
| P1-c | S2 에서 VC 슬롯과 SRAM hit 의 position 중복 시 VC 무효화 | `MainBtbAlignBank.scala:402-418` | 동일 position 이중 예측 제거 (현재는 mispredict 때만 Path A 로 제거) | 낮음. 인덱스 기반 무효화 포트 1개 |
| P1-d | perf 카운터 보강 | `MainBtb.scala:371-390` | VC taken/not-taken hit, 중복 flush 빈도 관찰 | 없음 |
| P2 | evicted 엔트리를 meta 대신 SRAM 재읽기(snapshot) 로 획득 | `MainBtbInternalBank.scala:152-246` | FTQ meta 약 300b 이상 절감, predict~train 사이 SRAM 변경으로 인한 stale eviction 제거 | 중간. InternalBank FSM, SRAM read 포트 공유, **S1 stall hazard 가드 필수** |
| P3 | VC 를 InternalBank 별 인스턴스로 분할 (인스턴스 내 FA 16) | `VictimBtb.scala`, `MainBtbAlignBank.scala:105-110` | S1 CAM 1/8 축소, 태그 3b 절감, 총 용량 128 확장 여지, T1 학습 경로 단순화 | 중간. VC 를 AlignBank 내부로 이동, 파티션 불균형 측정 필요 |
| 미채택 | bank override + fetch block 절단 (`s1_maxBankIdx`) | `MainBtb.scala:99-175`, `Bpu.scala:311-386` | — | 로컬은 슬롯 추가로 대체. hit 마다 32B 손실과 Bpu top S1 경로 부담이 큼 |
| 미채택 | LRU replacer (invalid-first 없음) | `VictimBtbReplacer.scala` | — | 로컬 invalid-first PLRU 가 flush 이후 빈 way 활용에 유리 |

## 4. 항목별 설계

### 4.1 P1-a. VC 카운터 상시 학습

- 위치: `MainBtb.scala` T1 VC 블록 (`:292-386`)
- 입력: `t1_train.branches` (`ResolveEntryBranchNumber` 개), VC 슬롯 meta `vcSlotMetas(s)` 의 position, `t1Read(s).entry`
- 로직 (슬롯 s 마다)
  - `hitMask(s)(b) = branches(b).valid && isConditional && vcSlotMetas(s).rawHit && branches(b).cfiPosition === vcSlotMetas(s).position`
  - `actualTaken(s) = Mux1H(hitMask(s), branches.map(_.taken))`
  - `counterNext(s) = t1Read(s).entry.counter.getUpdate(actualTaken(s))`
- 쓰기: 기존 `update` 포트를 슬롯 수만큼 확장하거나 별도 `counterUpdate: Vec(NumVCResultSlots, Valid(idx, counter))` 포트 추가. mispredict 경로의 entry update 와 같은 idx 충돌 시 entry update 우선 (upstream `entryOverridden` 과 동일, `MainBtbAlignBank.scala:388-390`)
- 태그 재검증: `t1Read(s).entry.vcTag === makeVCTag(해당 AlignBank startPc)` 를 유지하여 예측 후 교체된 엔트리 오학습 방지
- replacer: 카운터 갱신은 touch 하지 않음 (S3 predTouch 가 이미 taken 기준 touch)

### 4.2 P1-b. T1 VC CAM 판정

- 위치: `MainBtbVictimCache.scala` 에 `t1Lookup: {vcTag, position} → {hit, idx, entry}` 포트 추가
- `MainBtb.scala:308-320` 의 `t1_vcSlotHits` 를 `t1Lookup.hit` 으로 대체. `t1_expectedVcTag`(`:330`) 와 `t1_mispredictAlignedPos`(`:300`) 를 그대로 입력
- 효과
  - `t1_doUpdateVc` / `t1_doInsertVc` 가 예측 시점의 VC hit 여부와 무관해짐 → 예측 후 삽입된 엔트리 존재 시 SRAM 이중 할당 방지 (upstream `!t1_victimHit` 조건과 동등)
  - `MainBtbMeta.vc` (`Bundles.scala:119`) 제거 가능. S3 predTouch 는 파이프 레지스터 `s3_vcSlotInfos` (`MainBtb.scala:250`) 만 사용하므로 FTQ meta 불필요
- 타이밍: T1 은 `RegEnable(t0_train)` 직후 조합. CAM 결과 → `vcSuppressWrite` → AlignBank `t1_entryNeedWrite` → InternalBank write buffer 입력. 필요 시 CAM 을 T0 에서 미리 수행(`io.train` 은 T0 에 유효) 하고 결과를 T1 로 레지스터 전달

### 4.3 P1-c. S2 중복 무효화

- 위치: `MainBtb.scala` S2 (`:187-212`)
- 판정: 슬롯 s 마다 `dup(s) = info.hit && ∃k. s2_rawPredictions(k).valid && s2_rawPredictions(k).cfiPosition === Cat(info.posHigherBits, vcEntry.position)` (2 × 8 비교, CfiPositionWidth)
- 처리
  - `io.result(NumWay*NumAlignBanks+s).valid := info.hit && !dup(s)`, `vcSlotMetas(s).rawHit` 도 동일하게 마스킹
  - VC 에 `s2Invalidate(s): Valid(idx)` 포트 추가, `s2_fire && dup(s)` 에 `vcIdx` 로 무효화. CAM 불필요
- 우선순위: 같은 사이클 T1 `update`/`insert` 가 같은 idx 를 쓰면 T1 write 우선 (upstream `VictimBtb.scala:112-126` 와 동일 원칙)
- 기존 Path A (`:328-333`) 는 유지 (S2 판정을 놓친 경우의 보조)

### 4.4 P1-d. perf 카운터

- `vc_hit_taken`, `vc_hit_not_taken` (S2 슬롯 hit × taken), `vc_s2_dup_flush`, `vc_t1_cam_hit_without_pred_hit` (P1-b 효과 측정)

### 4.5 P2. snapshot 기반 eviction (선택)

- 채택 조건: FTQ meta 폭 절감이 필요하거나 stale eviction 이 관측될 때
- 변경 범위
  - `Bundles.scala`: `MainBtbEntrySramWriteReq.hit` 추가, `MainBtbMetaEntry` 의 `sramValid/sramTag/targetLowerBits/targetCarry` 제거
  - `MainBtbInternalBank.scala`: upstream FSM 이식 (`snapshotValid/Req/Resp`, `snapshot.resp` 포트). counter SRAM 도 같은 사이클에 읽어 evicted counter 승계 (upstream 은 `WeakPositive` 로 초기화, 로컬은 counter 유지가 원칙)
  - `WriteBuffer.scala:259`: `io.read.bits` 상시 구동
  - `MainBtbAlignBank.scala`: `snapshot.resp` 를 top 으로 전달, T1 `t1_entryWayMask` 는 그대로
  - `MainBtb.scala`: Path C insert 를 `snapshot.resp` 기반으로 교체 (`evictedHitIncoming` 스킵 포함), `t1_evictedMeta` 재구성 로직 삭제
- **hazard 가드 (upstream 미비, 반드시 추가)**
  - 원인: S1 stall (`Bpu.scala:268` `s1_fire = s1_valid && s2_ready && toFtq.prediction.ready`) 중 `read.req.valid = 0` 이면 snapshot 읽기가 발행되어 `holdRead` 데이터(`SRAMTemplate.scala:485`) 를 덮어씀
  - 가드: AlignBank 에 `s1_pending` 레지스터 (`s0_fire` 로 set, `s1_fire` 로 clear) 를 두고 InternalBank 에 전달. snapshot 읽기 허용 조건 `!read.req.valid && (!s1_pending || s1_fire)`
- 타이밍: FSM 은 write 경로이므로 S1 영향 없음. write buffer 정체는 `vc_wb_stall` 카운터로 관찰

### 4.6 P3. InternalBank 별 VC 분할 (권장)

- 근거
  - 로컬 `vcTag` 의 `internalBankIdx`, `alignBankIdx` 는 pc 의 함수. 물리 AlignBank i 는 `alignBankIdx == i` 주소만 처리 (`MainBtbAlignBank.scala` S0 assert) 하므로 공유 FA 의 절반은 어떤 lookup 과도 매칭 불가
  - 분할 시 잃는 것은 파티션 간 용량 유연성뿐. 얻는 것은 태그 3b 축소, lookup 당 비교 엔트리 1/8, 총 용량 확장 여지 (128 까지)
- 구조
  - 인스턴스: AlignBank(2) × InternalBank(4) = 8개, upstream 처럼 `MainBtbAlignBank` 내부에 배치
  - 인스턴스 내부: **1단계 FA 16 entry** (기존 lookup / top-2 인코더 / invalid-first PLRU 를 `VCSize=16` 으로 재사용). set 인덱싱(upstream 4set×4way) 은 타이밍 리포트 요구 시 2단계
  - 태그: `tag(16) ‖ setIdx(8)` = 24b
- 파이프라인
  - S0: `internalBankIdx` 만 레지스터. upstream 의 S0 엔트리 읽기(`MainBtbAlignBank.scala:133-136`) 는 npc 경로 부담이므로 채택하지 않음
  - S1: 인스턴스 선택(4:1) → 16 × 24b 비교 + position ≥ offset, crossPage → `s1_victimHit`, `s1_victimPositions` 출력. MainBtb top 은 기존 슬롯 배정 / redistribution 만 유지
  - T1: `t1_activeStartPc` 로 인스턴스 선택 후 16 entry CAM → P1-a 카운터 학습, P1-b hit 판정, Path A/B/C, pdInvalidate 모두 인스턴스 내부에서 처리
- 파라미터: `VCSize` 를 인스턴스당 크기로 재정의, `NumVCInstances = NumAlignBanks × NumInternalBanks`, `VCIdxLen = log2(VCSize)`
- 위험: 파티션 불균형. conflict miss 는 특정 set 에 집중되므로 인스턴스 내부를 setIdx 로 다시 나누면 같은 conflict 를 물려받음 → 1단계는 인스턴스 내 FA 유지
- 평가: 총 용량 동일 조건 비교 (공유 FA 32 vs 8×4, 추가로 8×16). 인스턴스별 occupancy / hit 카운터로 불균형 손실 측정

## 5. 진행 순서

1. VC 활성 config 확정 (`VCSize` 32 제안, upstream 총 128 entry 와 비교용) 및 baseline 성능 채집
2. P1-a ~ P1-d 구현 (VC 내부 + MainBtb top 한정, InternalBank 무변경) → 컴파일, coremark/SPEC 짧은 구간 비교
3. `vc_t1_cam_hit_without_pred_hit`, `vc_s2_dup_flush` 값으로 P1-b/c 효과 확인
4. P2 는 FTQ meta 폭 요구 또는 stale eviction 증거가 있을 때 별도 브랜치로 진행, hazard 가드 포함
5. P3 는 합성 타이밍 결과 후 결정

## 6. 구현 현황 (2026-09-03, P1 + P3 1단계, 컴파일 미검증: 환경에 mill/java 없음)

| 파일 | 변경 |
|---|---|
| `mbtb/Parameters.scala` | `VCSize` 를 인스턴스당 크기로 재정의, `VCTagWidth = TagWidth + SetIdxLen` |
| `mbtb/Helpers.scala` | `makeVCTag = tag ‖ setIdx`, `vcLookup()` (FA top-2 hit) 를 trait 로 이동 |
| `mbtb/Bundles.scala` | `VCLookupResp`, `PdVcInvalidateReq` 추가. `VCMetaEntry`, `VCAlignBankPredInfo`, `MainBtbMeta.vc` 삭제 (FTQ meta 축소) |
| `mbtb/MainBtbVictimCache.scala` | 저장체 + 쓰기 포트만 보유 (`entries` 출력, `insert`, `update`, `counterUpdate`, `invalidateMask`, `predTouch`). CAM 은 모두 AlignBank 에서 수행 |
| `mbtb/MainBtbAlignBank.scala` | InternalBank 당 VC 1개 (`vcs`). S1 lookup → `read.s1_vc`. T1: CAM 으로 hit 판정(P1-b), Path A/B/C, 모든 AlignBank 에서 카운터 상시 학습(P1-a). `s2_vcInvalidate` / `s3_vcPredTouch` / `pdVcInvalidate` 라우팅. `Write.Req.posHigherBits` 추가 |
| `mbtb/MainBtb.scala` | 공유 VC 제거. 슬롯 배정은 AlignBank `s1_vc` 기반으로 유지. S2 position 중복 시 슬롯 drop + 원천 AlignBank 로 invalidate(P1-c). S3 touch 를 `sourceAlignBank` 로 라우팅. perf: `vc_hit_taken/not_taken`, `vc_s2_dup_flush`, `vc_t1_hit`, `vc_t1_hit_no_pred_hit`(P1-d) |

- 인스턴스 내 쓰기 우선순위: counterUpdate < invalidate < update < insert
- VC 활성화: `MainBtbParameters(VCSize = 16)` (인스턴스당, 총 8 × 16 = 128). `VCSize` 는 2 의 거듭제곱, 4 이상
- 미반영: P2 snapshot eviction (meta 기반 evicted 재구성 유지), 인스턴스 내 set 인덱싱

## 7. 검증

- 기능: `make emu` 후 `ready-to-run/coremark-2-iteration.bin` difftest 통과, `pdInvalidate` 경로 회귀 없음
- 성능: `pred_use_vc`(S2 슬롯 hit), `vc_hit_taken/not_taken`, MPKI, IPC 를 P1 전/후 비교
- P2 도입 시: S1 stall 강제 시나리오(FTQ full) 에서 SRAM resp 오염 여부 waveform 확인 (`mbtb_sram_entry_*` `io.r.resp.data` vs `s2_rawEntries`)
