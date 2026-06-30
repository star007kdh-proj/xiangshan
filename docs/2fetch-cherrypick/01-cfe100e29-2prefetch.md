# Cherry-pick 01 — `cfe100e29` feat(ICache): 2-prefetch (#5775)

- **적용 커밋(local)**: `55d58856f`
- **분류**: prereq (2-fetch 본체의 강한 선행 의존)
- **변경 규모**: 13 files, +557/−170, 신규 파일 `frontend/TwoFetch.scala` 생성

## 충돌

| 파일 | 유형 | 원인 | 해결 |
|---|---|---|---|
| `frontend/ftq/Bundles.scala` | content (import 1줄) | HEAD(2-taken)가 `import xiangshan.XSCoreParamsKey` 추가, incoming이 같은 위치에 `import xiangshan.frontend.FtqFetchRequest` 추가 | 양쪽 import 모두 보존(둘 다 파일 내 사용됨), 알파벳 순 병합 |

그 외 파일(Bundles.scala, Frontend.scala, ftq/Ftq.scala 등)은 auto-merge 성공.

## backend/mem 편입

없음 — frontend 내부에서 자족적으로 적용됨.

## 검증

- [ ] `mill -i xiangshan.compile` — **미실행** (로컬에 mill/java 없음, 외부 빌드 필요)
- [ ] perf 측정 (마일스톤)

## perf 영향 의심점

- 2-prefetch 자체가 ICache prefetch 동작을 바꾸므로, 이 커밋 단독으로도 perf 변화가 있을 수 있음.
  2-fetch 본체 적용 전, 이 지점에서 한 번 측정해두면 회귀 원인을 prefetch vs fetch로 분리 가능.

## 비고

- 선행 후보 6개(e3b045a1c 등)는 이번에 편입 불필요 — 실제 충돌은 import 1건뿐이었음.
