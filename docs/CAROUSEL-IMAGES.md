# 줍줍각 캐러셀 배너

기존 3종을 참고해 내장 image_gen 도구로 새로 생성했다. 원본은 유지하고 다음 프로젝트 파일에 저장했다.

- `frontend/public/brand/carousel-search-wide.png`
- `frontend/public/brand/carousel-compare-wide.png`
- `frontend/public/brand/carousel-pick-wide.png`

`frontend/src/site2Design.ts`에서 각 슬라이드에 연결한다. 이미지 자체에 문구를 넣지 않고 기존 HTML 문구를 유지한다. 표시 시 contain으로 원본 전체를 보존하고 모바일은 하단에 배치한다. 캐러셀 전환 시계와 버튼 로직은 변경하지 않았다.

## 생성 프롬프트

### 상품 검색·비교

Edit this reference banner for the same Korean shopping comparison website. Preserve the exact blue-purple Joopjoop mascot identity, dark navy outlines, huge friendly eyes, mint props and polished illustrated style. Generate ONE ultra-wide horizontal desktop carousel banner, aspect ratio 3:1, target 1536x512. Background pale mint and ivory gradient. LEFT 55 percent must be completely empty pale negative space for HTML text; no letters, numbers, logos or watermarks anywhere. RIGHT 45 percent: a SMALL fully visible mascot holding a magnifying glass, comparing two small floating shopping product cards (headphones and sneaker) beside a mint parcel. ALL of the mascot including top tuft, both hands and feet must fit inside the middle 70 percent of canvas height, with at least 15 percent padding above and below, and at least 7 percent right-edge padding. Do not enlarge the character to fill canvas height. This is a shallow 310px-tall web banner; full character visibility is critical. Friendly precise composition, no crop, no UI text. Redesign the wide composition, not just crop the input.

### 가격 관측·흐름

Edit this existing banner into ONE ultra-wide 3:1 website carousel illustration, matching the original blue-purple Joopjoop mascot with thick navy outlines, big expressive eyes and mint tablet. Target 1536x512 aspect 3:1, landscape shallow banner for 310px tall desktop carousel. Preserve character identity and soft polished illustration. LEFT 55% totally empty light lavender/ivory gradient for HTML copy, no text or numbers anywhere. RIGHT 45%: small complete mascot holding mint tablet and pointing to two floating shopping-price cards, one with a checkmark and another with a simple downward price-trend line, no written symbols or letters. Character including tuft, both feet and hands FULLY INSIDE middle 65-70% of height; generous 15% top/bottom margin, 7% right margin. Mint and pale lavender circles behind mascot, light clean background. Do not crop or enlarge character. Recompose for wide banner; no watermarks or logos.

### 구매 전 확인

Recompose reference as ONE ultra-wide shallow 3:1 carousel banner for same shopping comparison website, target 1536x512. Preserve the exact blue-purple Joopjoop mascot face, navy outline, big eyes, mint parcel, cute polished illustration. LEFT 55% empty ivory and soft peach negative space for HTML copy, absolutely no letters numbers text logos. RIGHT 45% a SMALL FULL-BODY mascot happily holding a mint gift parcel and giving thumbs-up, tiny mint/lavender confetti and soft shadow underneath. Entire tuft, ears, arms, parcel and feet visible, subject centered vertically within middle 65-70% of canvas height; minimum 15% empty padding above/below and 7% at right edge. Background pastel peach on left to pale mint on right with subtle soft circles. Avoid any cropped character or huge zoom. Final designed specifically for wide 310px-tall web hero, NOT a crop of the original. No text/watermark.
