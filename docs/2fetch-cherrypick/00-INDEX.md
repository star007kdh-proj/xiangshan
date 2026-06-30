# 2-fetch Cherry-pick 추적 인덱스

`origin/feat-2fetch`의 2-fetch(2T2F의 2F) 기능을 현재 `feat/2T2F` 브랜치(2-taken)로
**frontend 위주 + 필요한 backend/mem 최소 포함**으로 한 커밋씩 cherry-pick 하며,
이전 프로젝트에서 발생한 성능 하락 지점을 추적/문서화한다.

## 브랜치 구조

| 항목 | 값 |
|---|---|
| 작업 베이스 | `feat/2T2F` = 로컬 `kunminghu-v3`(업스트림 #5717 직후 + victim cache) + 2-taken 7커밋 |
| 작업 브랜치 | `feat/2T2F-2fetch` (feat/2T2F에서 분기) |
| 2-fetch 소스 | `origin/feat-2fetch` (kunminghu-v3 대비 217 커밋 ahead) |
| 공통 조상 | `87d03b2cc misc(CODEOWNERS) (#5717)` |

## 의존성 분석 결과 (요지)

- **2-fetch 본체**: `7c29456a9 feat(Frontend): implement 2-fetch` — 16개 frontend 파일,
  +1445/−1117. (Bundles, Frontend, ftq/{Bundles,Ftq}, icache/{Bundles,DataArray,DataBank,Imp,MainPipe,PrefetchPipe,WayLookup}, ifu/{Bundles,Helpers,Ifu,InstrBoundary,PredChecker})
- **강한 선행 의존**: `cfe100e29 feat(ICache): 2-prefetch (#5775)` — 본체 16파일 중 9파일 공유.
  2-fetch는 2-prefetch 위에 빌드됨.
- **무관(스킵 가능)**: `46fdc552b refactor: remove TileLink (#5992)` — 2-fetch frontend 파일과 겹침 없음.
- **2-prefetch 선행 후보**: #5775 이전, 동일 frontend 파일을 건드린 6개 커밋
  (e3b045a1c, dc962e6d5, 806ccd8c5, a04368be5, 9c4359917, c78f1ce8d) — 파일 단위 겹침이며,
  실제 텍스트/시맨틱 충돌 여부는 cherry-pick 시 경험적으로 확정.

## 후보 최소 집합 (가설, 의존성 순)

| # | commit | local | 설명 | 분류 | 상태 |
|---|--------|-------|------|------|------|
| 1 | cfe100e29 | 55d58856f | feat(ICache): 2-prefetch (#5775) | prereq | ✅적용·빌드검증대기 → [doc](01-cfe100e29-2prefetch.md) |
| 2 | 7c29456a9 | — | feat(Frontend): implement 2-fetch | **core** | 대기 |
| 3 | 3a49b46e0 | — | feat(ftq): read queue w/ redirect FTQ idx 1cyc ahead | follow-up | 대기 |
| 4 | a28cd38ff | — | fix(ftq): remove bypass from redirect to prefetch | follow-up | 대기 |
| 5 | 7be11a171 | — | fix(ftq): flush train cache with redirect | follow-up | 대기 |
| 6 | 637f62a88 | — | fix(ftq): fix train cache flush condition | follow-up | 대기 |

> 선행 후보 6개(e3b045a1c 등)는 #1에서 편입 불필요로 확인됨(충돌 import 1건뿐).

## 빌드/검증 방식

mill·java가 로컬(Git Bash/MSYS)에 없어 **사용자가 직접 빌드/perf 측정**한다.
각 마일스톤에서 작업을 멈추고 사용자 검증 결과를 받아 다음 커밋으로 진행한다.

> 집합은 cherry-pick 진행 중 충돌/컴파일 결과에 따라 prereq를 추가하며 갱신한다.

## 워크플로 (커밋당 반복)

1. `git cherry-pick <hash>` 시도.
2. 충돌 시: frontend 위주로 해결, 컴파일/동작에 필요한 backend/mem hunk만 최소 편입.
3. `mill -i xiangshan.compile` 로 빌드 검증.
4. `NN-<hash>.md` 에 기록: 충돌 파일/원인/해결, 편입한 backend/mem 범위, 의심 perf 영향.
5. 마일스톤(2-prefetch 후 / 2-fetch 후 / fix 후)에서 perf 측정 → 회귀 국소화.

## 커밋별 문서

- (생성되는 대로 여기에 링크)
