# Cherry-pick 05 — `33995fbb9` fix(utils): fix one hot check condition (#5726)

- **적용 커밋(local)**: `0652aa894`
- **분류**: prereq (빌드 중 발견 — utils/ 변경이라 frontend 중심 분석에서 놓침)
- **트리거**: 첫 빌드 시 elaboration 에러
  `EnumUInt xiangshan.frontend.TwoPrefetchCase using one-hot has non one-hot value(0): Conflict`

## 원인
- 2-prefetch의 `TwoPrefetchCase.Value extends EnumUInt(5, useOneHot = true, allowZeroForOneHot = true)`,
  `Conflict = 0.U`.
- 우리 base의 `utils/EnumUInt.scala` check3.1(one-hot은 2의 거듭제곱)이 `allowZeroForOneHot`를 **고려하지 않아**
  `isPow2(0)=false`로 0을 거부 → 의존성이었음(2-prefetch의 조상 커밋이지만 utils/라 누락).

## fix (1줄)
```scala
-  !useOneHot || isPow2(litValue),
+  !useOneHot || (if (allowZeroForOneHot) litValue == 0 || isPow2(litValue) else isPow2(litValue)),
```
체크를 완화만 하므로(0 허용) 기존 통과 코드엔 무영향.

## 교훈
- frontend 중심 의존성 분석은 **utils/·utility/ 등 공유 인프라 갭 커밋을 놓칠 수 있음**.
- 이런 종류는 컴파일/elaboration에서만 드러나므로, 빌드 에러마다 갭 커밋을 역추적해 편입하는 루프가 필요.
- 잔여: 추가 빌드에서 또 다른 갭 심볼이 나올 수 있음(2-fetch Ifu 통째 적용분 + 216 갭 커밋).
