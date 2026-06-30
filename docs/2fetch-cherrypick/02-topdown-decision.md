# 결정 02 — topdown/perf-info 모델: 구 모델 유지 (806ccd8c5 스킵)

## 배경
2-fetch core(`7c29456a9`)는 **신(新) topdown 모델**을 전제로 한다:
`FrontendTopDownBundle.reasons` 기반 + `BackendRedirectTopdown` 번들 + `backendRedirectOverride()` 메서드 + `io.backendRedirectTopdown` IO.

우리 base는 **구(舊) 모델**: `BpuPerfInfo`, `BpuTopDownInfo`, `FrontendPerfInfo`, `io.toIfu.topdownRedirect`.

`806ccd8c5 fix(frontend): backend redirect topdown override`는 둘을 잇는 한 단계지만,
- `BpuTopDownInfo` → `BackendRedirectTopdown` **rename**(+필드)
- `FrontendTopDownBundle`을 파일 하단으로 **이동** + 메서드 추가
- `FtqToIfuIO.topdownRedirect` **제거** (2-fetch core가 또 건드리는 영역과 겹침)

→ 그 자체가 더 앞선 perf-info 리팩터 체인을 전제로 하여 우리 base에 단독으로 깨끗이 안 붙음(반쪽 상태).

## 측정
- 2-fetch core의 신-topdown 참조: **Ftq 12곳 + Ifu 4곳 = 16곳**
- 구 모델 소비자: **frontend 내부(Bundles + Ftq)에만** 존재 (backend/ctrlblock 소비자 없음)
- topdown은 **성능 관측용 카운터**이지 fetch 데이터패스가 아님 → IPC/성능 회귀 추적과 무관

## 결정 (사용자 승인: Path B)
- `806ccd8c5`를 **cherry-pick 하지 않는다**(minimal set에서 제외).
- 우리 base의 **구 topdown/perf 모델을 그대로 유지**한다.
- 2-fetch core cherry-pick 시, core의 신-topdown 참조 16곳을 **구 모델로 적응하거나 스터브**한다.

## 영향 / 주의
- fetch 데이터패스는 2-fetch 원본 그대로 적용 → 성능 회귀 추적에 적합.
- **이 브랜치의 topdown perf 카운터는 신뢰할 수 없음**(스터브/부분 배선). 성능 분석 시 IPC·MPKI 등 datapath 지표만 사용하고, top-down stall breakdown은 무시할 것.
- 추후 정식 upstream 정렬이 필요하면 별도로 perf-info 리팩터 체인 전체(Path A)를 적용해야 함.
