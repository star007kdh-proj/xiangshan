# Cherry-pick 06 — uncache/MMIO fetch 메커니즘 정렬 (#5874, #5787, #5959)

- **트리거**: 빌드 시 `IfuUncacheUnit` 인터페이스 불일치 — `uncacheUnit.io.resp.bits.needResend`, `io.emptyAfter` 등 미존재.
  (`import xiangshan.mem.LoadStage.s0`는 무관한 unused import였음 → 제거)

## 원인
2-fetch Ifu(core, 통째 적용)는 새 MMIO 메커니즘을 전제로 하나, 지원 파일들이 base 구버전에 머물러 **반쪽 상태**였음:
- **옛 방식**: `mmioCommitRead`(Ifu↔Ftq 핸드셰이크)
- **새 방식**: `emptyAfter`(backend/ibuffer drain 기반) — `#5787`이 교체

이를 진화시킨 3개 갭 커밋(모두 core 조상, frontend가 아닌/leaf 파일이라 누락):
| local | 원본 | 역할 |
|---|---|---|
| `90839b385` | `fdc671fc2` (#5874) | IfuUncacheUnit 예외 처리 |
| `90748e6e0` | `512397494` (#5787) | **MMIO 메커니즘 교체** + backend empty-state export |
| `d8c790a37` | `09d715b21` (#5959) | InstrUncache `needResend` |

## 적용 방식
3개 커밋의 frontend **Ifu.scala 변경은 2-fetch 이전 구조**라 cherry-pick 불가 → **Ifu는 항상 ours(core, 이미 net 반영)** 로 해결.
나머지 파일은 cherry-pick으로 적용:
- `IfuUncacheUnit.scala`: `mmioCommitRead`→`emptyAfter`, `needResend` resp 추가
- `instruncache/{Bundles,InstrUncacheEntry}.scala`: `needResend` 계산/필드 (#5959)
- `Bundles.scala`/`ftq/Ftq.scala`: `mmioCommitRead`/`MmioCommitRead` **제거** (#5787)
- `ibuffer/IBuffer.scala`: `io.empty` 출력 추가 (#5787)
- backend `Bundle.scala`(`FrontendToCtrlIO.backendEmpty`) + `CtrlBlock.scala`(`isEmptyDelay` from `rob.io.enq.isEmpty` → `io.frontend.backendEmpty`) (#5787)
- `Frontend.scala`: `ifu.io.ibufferEmpty := ibuffer.io.empty`, `ifu.io.backendEmpty := io.backend.backendEmpty`
- `a8e885e5c`: spurious `import xiangshan.mem.LoadStage.s0` 제거

## 최종 일관성 검증
- ✅ `mmioCommitRead` 트리 전체 0 (완전 교체)
- ✅ `uncacheUnit.io.emptyAfter := io.backendEmpty && io.ibufferEmpty`
- ✅ CtrlBlock `io.frontend.backendEmpty := RegNext(isEmptyDelay)` (`rob.io.enq.isEmpty` 존재)
- ✅ InstrUncacheEntry `io.resp.bits.needResend` 계산
- ✅ `crossFtqCommit`/`crossFtq` 고아 없음(uop 메커니즘은 정상 유지, #5787은 Rob 중복본만 제거)
- ✅ 충돌 마커 0

## 잔여 리스크 / 비고
- MMIO 명령어 fetch는 perf 벤치(coremark 등)에서 거의 미실행이나, **difftest 정확성(boot/uncacheable 코드)** 에는 `emptyAfter` 타이밍이 중요 — 시뮬레이션으로 확인 권장.
- `#5787`의 Rob commit ftqIdx에서 `+ crossFtqCommit` 제거가 **2-taken pair FTQ 인덱싱**과 상호작용할 수 있으니, MMIO/redirect 회귀 시 점검.
- 교훈: frontend Ifu를 통째로 가져오면 **그것이 의존하는 leaf 모듈(IfuUncacheUnit, IfuPerfAnalysis, instruncache)** 도 같은 레벨로 맞춰야 함 — 빌드가 이를 순차적으로 드러냄.
