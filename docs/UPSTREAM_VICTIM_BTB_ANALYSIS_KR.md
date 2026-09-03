# Upstream `feat-victim-btb-rebase` Victim BTB 구현 분석

## 1. 개요

- 대상: OpenXiangShan/XiangShan `feat-victim-btb-rebase` (HEAD `cb41cec27`, 2026-08-28)
- 베이스: `12df8283f` (kunminghu-v3, #6314 직후). 로컬 `upstream/feat-victim-btb-rebase` 로 fetch 완료
- 커밋 3개, 총 +890/-71 (10 파일)

| 커밋 | 제목 | 내용 |
|---|---|---|
| `614cdc32e` (2026-05-13, Zhang Xingyu / Yuan Dongliang) | feat(mbtb): add victim btb | 본 구현 |
| `f72548d12` | fix(mbtb): fix some rebase error | Helpers/AlignBank 잔재 정리 |
| `cb41cec27` (2026-08-28) | feat(mbtb): add vbtb perf counter | perf 카운터 4종 |

- 관련 브랜치: `feat-victim-btb`, `feat-victim-btb-ideal`, `feat-mbtb-victim-cache`, `timing-victim-btb-rebase` (타이밍 수정판 별도 존재)
- 신규 파일: `mbtb/VictimBtb.scala` (144줄), `mbtb/VictimBtbReplacer.scala` (103줄)
- 수정 파일: `MainBtb.scala`, `MainBtbAlignBank.scala`, `MainBtbInternalBank.scala`, `Bundles.scala`, `Parameters.scala`, `Helpers.scala`, `WriteBuffer.scala`, `Bpu.scala`

## 2. 설계 요약 (한 줄)

- InternalBank 마다 소형 set-assoc VBTB(레지스터)를 두고, MainBtb miss-allocation 시 SRAM을 재읽기(snapshot)하여 쫓겨나는 엔트리를 VBTB에 삽입
- 예측 시 VBTB hit 이 나면 **다른 AlignBank 의 결과 슬롯을 빼앗아** VBTB 예측을 실어 보내고, 빼앗긴 bank 는 "lost" 처리 → fetch block 을 그 앞에서 잘라냄 (`s1_maxBankIdx`)
- 결과 슬롯 수를 늘리지 않는 대신, VBTB hit 시 fetch block 길이가 절반(32B)으로 줄어드는 trade-off

## 3. 파라미터 / 저장 구조

- `Parameters.scala:38` `NumVictimBtbEntries = 128`
- `NumVictimBtbSets = 128 / NumWay(4) / NumInternalBanks(4) / NumAlignBanks(2) = 4`, `VictimBtbSetIdxLen = 2`
- 인스턴스: AlignBank(2) × InternalBank(4) = 8개 VBTB, 각 4 set × 4 way = 16 entry. 메인 BTB 는 bank 당 256 set (`SetIdxLen = 8`)
- VBTB set 인덱스 = MainBtb `setIdx` 하위 2비트. 상위 setIdx 는 엔트리에 저장하여 비교

`Bundles.scala:70` `VictimBtbEntry`

| 필드 | 폭 | 용도 |
|---|---|---|
| `setIdx` | 8b | 원래 MainBtb set (VBTB set 절단분 보상) |
| `entry: MainBtbEntry` | tag 16b + attribute + position + target 20b + carry | MainBtb 엔트리 그대로 |
| `counter` | 2b | taken counter (엔트리 내장, SRAM 분리 없음) |

- valid 표현: 별도 valid 비트 없음, `entry.attribute === None` 이 invalid (`VictimBtb.scala:118,135,141`)
- 저장체: `Reg(Vec(4, Vec(4, VictimBtbEntry)))` (`VictimBtb.scala:84`), 읽기 포트 3개 모두 조합 읽기 (`read` 예측, `writeEntryRead` snapshot 삽입, `trainEntryRead` T1 학습)

## 4. VictimBtb 모듈 포트 (`VictimBtb.scala`)

| 포트 | 방향 | 동작 |
|---|---|---|
| `read` | S0 setIdx → entries | 예측용. AlignBank 가 S0 에 읽어 S1 레지스터에 보관 |
| `trainEntry` | T1 | `entryWayMask` 로 엔트리 재기록, `counterWayMask` 로 카운터 갱신. 둘 독립 (`:96-108`) |
| `writeEntry` | snapshot | evicted 엔트리 삽입 (`wayMask`), 동시에 `flushMask` 로 중복 incoming 제거. write 가 flush 보다 우선 (`:112-126`) |
| `flush` | S2 | MainBtb 와 같은 position 중복 시 attribute None 처리 (`:130-137`) |

- 삽입 시 카운터는 `WeakPositive` 로 초기화 (`:122`), 원래 MainBtb counter 는 승계하지 않음

## 5. 예측 경로

### 5.1 AlignBank (`MainBtbAlignBank.scala`)

- S0: `s0_setIdx.take(2)` 로 VBTB 읽고 internalBankIdx 로 1개 선택 (`:133-136`), `RegEnable(…, s0_fire)` 로 S1 전달
- S1: `s1_victimEntryRawHitMask` = valid && `setIdx` 일치 && `tag` 일치 (`:161`, "TODO: optimize this" 주석)
- S1: `s1_victimEntryHitMask` = raw && `position >= alignedInstOffset` && `!crossPage` (`:164`)
- S1 출력: `s1_victimHit` (OR), `s1_victimPositions` (`Cat(posHigherBits, position)`) (`:168-169`)
- S2: `victimPredictions` / `victimMetas` 를 MainBtb 예측과 동일 형식으로 생성, taken = `counter.isPositive` (`:218-234`)

### 5.2 MainBtb top S1 override 정책 (`MainBtb.scala:99-175`)

- 물리 bank 순서(rotated)를 논리 bank 순서(fetch block 내 앞/뒤)로 되돌린 뒤 정책 적용
- `hitCount(i)` = 논리 bank i 이전의 VBTB hit 수, `overrideIdx(i) = ~hitCount(i)` = 뒤에서부터 할당되는 출력 슬롯 (`:129-131`)
- `overrideMask(i)` = hit && `i < overrideIdx(i)` → bank i 의 VBTB 예측이 슬롯 `overrideIdx(i)` 를 차지 (`:132`)
- `invalidBankMask(i)` = hit && `i >= overrideIdx(i)` → 뒤쪽 슬롯 없음, bank i 예측 자체를 무효화 (`:135`)
- `lostBankMask` = invalid | overridden (`:145`), `s1_maxBankIdx = ~PopCount(lost)` (`:173`)
- `s1_positions` 는 override 반영된 `s1_finalPositions` 로 TAGE 에 전달 (`:177`)

NumAlignBanks = 2 일 때 실제 동작:

| 상황 | 결과 |
|---|---|
| 논리 bank0 VBTB hit | bank0 VBTB 4 entry → bank1 출력 슬롯. bank1 lost. `maxBankIdx = 0` (fetch block 32B 로 절단) |
| 논리 bank1 VBTB hit 만 | `overrideIdx(1)=1`, `1 < 1` 거짓 → bank1 invalid(lost). MainBtb bank1 예측도 지워짐. `maxBankIdx = 0` |
| 둘 다 hit | bank0 VBTB → bank1 슬롯, bank1 invalid. `maxBankIdx = 0` |
| hit 없음 | 원래 동작, `maxBankIdx = 1` |

- 즉 VBTB hit 은 항상 fetch block 후반부(bank1)를 희생. bank1 VBTB 엔트리는 다음 예측(다음 startPc 가 bank1 시작이 되면 논리 bank0 로 옮겨짐)에서야 사용됨
- S1 조합 경로: VBTB reg → tag/setIdx 비교 → PopCount → override mux → `s1_positions` (TAGE) 및 `s1_maxBankIdx` (BPU top S1 redirect). 별도 `timing-victim-btb-rebase` 브랜치가 존재하는 이유로 추정

### 5.3 S2 / S3 (`MainBtb.scala:183-300`)

- S2: S1 에서 레지스터로 넘긴 `overrideHitMatrix` 를 그대로 사용, 재계산 금지 (`:241-251`). 우선순위 overridden → victim, invalidate → 빈 예측, 나머지 원본
- `io.meta.lostBankMask` 신설 (`Bundles.scala:86`, `MainBtb.scala:265`) → T1 학습에서 lost bank 의 write 억제 (`:337`)
- S3 replacer touch 분리: MainBtb replacer 는 non-lost bank 만, VBTB replacer 는 override 한 bank 에 대해 "빼앗은 슬롯"의 takenMask 로 touch (`:288-300`, AlignBank `:252-255`)

### 5.4 BPU top S1 필터 (`Bpu.scala:311-386`)

- `s1_maxBankIdx` 기준으로 uBTB taken 무효화 (`:316`), aBTB 후보를 bank ≤ maxBankIdx 로 마스킹 후 first-taken 재선정 (`:352-378`, maxBankIdx 별로 미리 계산 후 최종 mux)
- fall-through 를 `(maxBankIdx+1)` 번째 aligned pc 로 재계산, cross-page 처리 포함 (`:322-351`, `Helpers.scala:47 getNthNextAlignedPc` 신설)
- `fallThrough.io.prediction` 대신 `s1_fallthroughPrediction` 을 S2/S3 로 전달 (`:454`)

## 6. 학습 경로 (T1, `MainBtbAlignBank.scala:275-395`)

- `t1_victimEntries` = `trainEntryRead` 로 T1 setIdx 의 VBTB set 조합 읽기 (`:289`)
- `t1_victimHit` = setIdx && tag && `Cat(posHigherBits, position) === mispredict.cfiPosition` (`:290-295`). meta 를 쓰지 않고 VBTB 를 재조회
- `t1_entryNeedWrite` 에 `!t1_victimHit` 추가 (`:299`) → VBTB hit 이면 MainBtb 로 되돌려 쓰지 않음 (ping-pong 방지)
- `t1_victimEntryNeedWrite` = hit && mispredict && (needIttage || attribute 변경) (`:359-362`), 대상 way = hit way
- VBTB 카운터: `t1_branches` 와 position 비교로 actualTaken 구해 `getUpdate`, entry 덮어쓰면 `WeakPositive` (`:376-390`)
- 학습 write 는 `posHigherBits` 를 T1 까지 전달해야 하므로 `Write.Req.posHigherBits` 포트 신설 (`:68`, `MainBtb.scala:312,341`)

## 7. Eviction 삽입 경로 (snapshot)

### 7.1 InternalBank snapshot FSM (`MainBtbInternalBank.scala:152-246`)

- `MainBtbEntrySramWriteReq.hit` 신설 (`Bundles.scala:58`): T1 `t1_hit` 을 write buffer 까지 전달. flush write 는 hit 로 취급 (`:267`)
- hit write (기존 way 갱신): 종전대로 write buffer → SRAM 직접 write
- miss write (victim way 할당): way 별 `snapshotValid` FSM

| 사이클 | 동작 |
|---|---|
| N | `pendingValid(i) && !read.req.valid` → 해당 way SRAM 에 `pendingSetIdx` 읽기 발행 (`:217`), `snapshotReq(i)` 에 요청 보관, write buffer 는 이 사이클에 dequeue (`bufRead.ready = !read.req.valid`, `:237-245`) |
| N+1 | `snapshotDataValid` (valid 상승 에지) 에 SRAM resp 를 `snapshotResp(i)` 에 캡처 (`:175,197`) |
| N+1.. | `pendingReady(i) && snapshotValidOH(i)` 이면 incoming 엔트리 SRAM write, `snapshotValid` 클리어 (`:189-191,234-236`). way 간 `PriorityEncoderOH` 로 직렬화 |
| 클리어+1 | `snapshotRespValid` (하강 에지) 에 `snapshot.resp` {setIdx, incoming, evicted} 1사이클 출력 (`:176,207-210`) |

- SRAM 읽기 포트 공유: 예측 읽기 우선, snapshot 읽기는 `read.req.valid` 가 없는 사이클에만 (`:217-218`)
- `WriteBuffer.scala:259` 변경: `io.read.bits` 를 ready 와 무관하게 항상 구동 (snapshot 로직이 `bits.hit/setIdx` 를 상시 참조)

### 7.2 AlignBank 삽입 로직 (`MainBtbAlignBank.scala:436-500`)

- `writeEntryRead` 로 snapshot setIdx 의 VBTB set 읽어 두 가지 검색
  - `evictedHitMask`: evicted 가 이미 VBTB 에 있음 → 그 way 갱신
  - `incomingHitMask`: incoming 이 VBTB 에 있음 → 중복이므로 flush
- `evictedHitIncoming` (`:466`): inflight miss 로 evicted == incoming 이면 삽입 생략
- way 선택 `PriorityMux(evictedHit → 그 way, incomingHit → 그 way 재사용, else replacer victim)` (`:473-477`)
- `writeEvicted = snapshot.valid && evicted.valid && !evictedHitIncoming`, `flushIncoming = snapshot.valid && incomingHit` (`:480-482`)
- VBTB replacer `trainTouch` = 삽입 way (`:492-494`)

### 7.3 S2 중복 제거 (`MainBtbAlignBank.scala:402-418`)

- VBTB hit 과 MainBtb hit 의 `position` 이 같으면 VBTB way flush (`:404-411`, `flush` 포트)
- MainBtb 가 직접 예측 가능해지면 victim 복사본은 stale 로 간주

## 8. Replacer (`VictimBtbReplacer.scala`)

- `ReplacerStateGen(Replacer="Lru", NumWay)` 2개 (predict: 다중 way access, train: 1 way) + `ReplacerState(4 set)` 공유 (`:49-51`)
- predict touch: S3 에서 VBTB 예측이 실린 슬롯의 takenMask (`MainBtbAlignBank.scala:252-255`)
- train touch: snapshot 삽입 way. 같은 set 동시 touch 시 predict next state 를 train 입력으로 forward (`:75-79`)
- victim = `trainStateGen.io.victim` (`:102`). **invalid-first 우선순위 없음** (flush 로 빈 way 가 생겨도 LRU 가 valid way 를 고를 수 있음)

## 9. perf 카운터

- `MainBtb.scala:371-390`: `pred_use_vbtb` (lost 발생), `vbtb_hit_counter_taken/not_taken`, `pred_hit/pred_miss`
- `MainBtbAlignBank.scala:519-520`: `vbtb_hit` (S1 raw hit), `vbtb_duplicate_flush`

## 10. 로컬 구현(`feat/p3_2taken_backend_targetmem`)과 비교

| 항목 | Upstream VBTB | 로컬 VC (`MainBtbVictimCache.scala`) |
|---|---|---|
| 구조 | InternalBank 당 4set×4way, 총 8개 | AlignBank 공유 fully-assoc 1개 (`VCSize`, 기본 0 = off) |
| 태그 | `setIdx`(8b) 저장 + entry.tag, set 은 setIdx 하위 2b | `VCTagWidth = tag + setIdx + internalBankIdx + alignBankIdx` 단일 태그 |
| 결과 슬롯 | 슬롯 추가 없음, 다른 bank 슬롯 override | `NumVCResultSlots = NumAlignBanks` 슬롯 추가 (top-2 hit) |
| fetch block 영향 | VBTB hit 시 후반 bank lost, block 32B 절단, uBTB/aBTB/fallthrough 재필터 | 없음 |
| TAGE position | override 된 bank 의 positions 전체를 VBTB position 으로 교체 | invalid SRAM way 슬롯 1개만 VC position 으로 교체 |
| evicted 획득 | write 시 SRAM 재읽기 (snapshot FSM, 읽기 포트 경합, way 직렬화) | 예측 시 meta 에 `sramValid/sramTag/target*` 실어 T1 에서 사용 (SRAM 재읽기 없음, predict~train 사이 변경 시 stale 가능) |
| 삽입 시점 | SRAM write 완료 후 1사이클 | T1 즉시 |
| 중복 제거 | S2 position 일치 flush + snapshot incoming flush | T1 Path A (SRAM hit → VC invalidate) |
| VC hit 학습 | VBTB in-place, MainBtb write 억제 | Path B 동일 (`vcSuppressWrite`) |
| replacer | LRU, invalid-first 없음 | PLRU + invalid-first |
| predecode 무효화 | 없음 | `pdInvalidate` (ghost entry 제거, 로컬 추가) |
| 컴파일 가드 | 항상 활성 (`require(NumVictimBtbEntries >= 32)`) | `HasVC` Option 가드, 0 이면 오버헤드 없음 |

## 11. 검토 포인트 / 잠재 이슈

- **S1 stall 중 snapshot 읽기 hazard (검증 필요)**: `Bpu.scala:268` `s1_fire = s1_valid && s2_ready && toFtq.prediction.ready` 로 S1 stall 가능. stall 시 `s0_fire = 0` → `read.req.valid = 0` → snapshot 읽기 허용 (`MainBtbInternalBank.scala:217`). SRAMTemplate `holdRead` 는 `HoldUnless(mem_rdata, RegNext(ren))` (`utility/.../SRAMTemplate.scala:485`) 이라 새 읽기가 hold 값을 덮어씀 → S1 이 아직 소비하지 않은 예측 데이터가 snapshot 데이터로 바뀔 수 있음. `s2_rawEntries = RegEnable(s1_rawEntries, s1_fire)` 가 오염된 값을 latch. 시뮬레이션으로 확인 필요
- **fetch block 절단 비용**: VBTB hit 마다 후반 32B 손실. `pred_use_vbtb` 카운터로 빈도 관찰 필요. 로컬 방식(슬롯 추가)은 이 비용이 없음
- **bank1 VBTB hit 은 즉시 활용 불가**: 해당 예측 사이클에서는 bank1 자체가 invalid 처리되어 MainBtb bank1 예측까지 버림. 다음 예측에서 재시도
- **S1 타이밍**: VBTB reg 읽기 → 16b tag + 8b setIdx + position 비교 → override → `s1_maxBankIdx` → aBTB/uBTB/fallthrough mux → S1 redirect. `timing-victim-btb-rebase` 브랜치 별도 확인 권장
- **write buffer 점유**: miss write 는 snapshot FSM 동안 해당 way 의 write buffer 읽기 포트를 `ready=false` 로 막음 (`:237-245`), 연속 miss 시 write buffer 포화 가능
- **counter 승계 없음**: evicted 엔트리의 MainBtb counter 를 VBTB 로 가져오지 않고 `WeakPositive` 로 초기화. 로컬 구현과 동일 여부 확인 필요
- **TODO 주석 3곳** (`MainBtbAlignBank.scala:162,292,383`): tag 비교 최적화 미완
