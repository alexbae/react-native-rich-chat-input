# react-native-rich-chat-input

React Native의 기본 `TextInput`이 지원하지 않는 **네이티브 키보드의 Rich Content(GIF, 스티커, 이미지 복사/붙여넣기)**를 수신하고 처리하는 고성능 입력 컴포넌트 라이브러리.

- **Android**: `AppCompatEditText` + `ViewCompat.setOnReceiveContentListener`
- **iOS**: `UITextView` + `paste(_:)` 오버라이드 및 `UIPasteboard` 감지
- **Architecture**: React Native New Architecture (Fabric View) 기반

---

## 목차

- [설계 원칙](#설계-원칙)
- [기술 스택](#기술-스택)
- [Installation](#installation)
- [로컬 개발 빌드 순서](#로컬-개발-빌드-순서)
- [Usage](#usage)
- [API](#api)
- [구현 로드맵](#구현-로드맵)
  - [Phase 1 — Android 핵심 구현](#phase-1--android-핵심-구현)
  - [Phase 2 — iOS 핵심 구현 및 호환성 수정](#phase-2--ios-핵심-구현-및-호환성-수정)
  - [Phase 3 — JS/TS Bridge 완성](#phase-3--jsts-bridge-완성)
  - [Phase 4 — 안정화 및 배포](#phase-4--안정화-및-배포)
- [프로젝트 구조](#프로젝트-구조)
- [Troubleshooting](#troubleshooting)
- [Contributing](#contributing)
- [License](#license)

---

## 설계 원칙

### UI 전략: 인라인 삽입 방식 채택 안 함

키보드로부터 수신한 GIF/스티커/이미지를 TextInput 인라인에 삽입하는 방식은 채택하지 않는다. 대신, **이미지를 별도 분리하여 JS 레이어에서 프리뷰로 보여주는 방식**을 사용한다.

```
[사용자가 키보드에서 GIF 선택]
        ↓
[네이티브 레이어에서 content:// URI 인터셉트]
        ↓
[앱 캐시 디렉토리로 즉시 복사 → file:// URI 획득]
        ↓
[onRichContent 이벤트로 JS에 { uri, mimeType } 전달]
        ↓
[JS에서 이미지 프리뷰 렌더링 (TextInput 위/아래)]
```

이 방식을 선택한 이유:
- `content://` URI는 키보드 프로세스의 임시 권한이므로, 지연 처리 시 권한 만료로 접근 불가
- 인라인 삽입은 Fabric 아키텍처에서 AttributedString 조작이 필요하여 복잡도가 매우 높음
- 프리뷰 방식이 실제 채팅 앱(KakaoTalk, Slack 등)의 UX 패턴과 일치

---

## 기술 스택

| 영역 | 기술 |
|---|---|
| 아키텍처 | React Native New Architecture (Fabric View) |
| Android | Kotlin, `AppCompatEditText`, `ViewCompat.setOnReceiveContentListener` |
| iOS | Swift/Objective-C++, `UITextView`, `UIPasteboard` |
| JS Bridge | `codegenNativeComponent` (Codegen 기반 타입 안전 브릿지) |
| 패키지명 (Android) | `com.whosup.packages.richinput` |
| 빌드 도구 | react-native-builder-bob, Turbo |

---

## Installation

```sh
npm install react-native-rich-chat-input
```

```sh
yarn add react-native-rich-chat-input
```

> **요구사항**
> - React Native 0.76 이상 (New Architecture 필수)
> - Android minSdkVersion 24 이상
> - iOS 15.1 이상

---

## 로컬 개발 빌드 순서

`file:` 경로로 연결된 로컬 개발 환경에서 **네이티브 코드 또는 Codegen 스펙을 수정한 경우** 아래 순서를 따른다.

### iOS

```sh
# 1. 앱 iOS 디렉토리에서 Codegen 재실행 + 네이티브 재컴파일
cd <your-app>/ios
pod install

# 2. 앱 실행
cd <your-app>
npm run ios
```

### Android

```sh
# 앱 실행 (Gradle이 Codegen 자동 실행)
cd <your-app>
npm run android
```

> **`yarn prepare`는 개발 중 불필요**
>
> Metro 번들러가 `package.json`의 `"source": "./src/index.tsx"` 필드를 우선 해석하도록
> 앱의 `metro.config.js`에 아래 설정이 필요하다. 이 설정이 없으면 Metro가 `lib/module/`(구버전 빌드)을 사용한다.
>
> ```js
> config.resolver.unstable_conditionsByPlatform = {
>   ios: ['source', 'react-native'],
>   android: ['source', 'react-native'],
> };
> ```
>
> 이 설정이 있으면 `lib/` 빌드(`yarn prepare`)는 **npm 배포 시에만** 필요하다.
>
> **`pod install`이 필요한 경우** (iOS)
> - Codegen 스펙에 prop/event 추가·삭제 시 → C++ 이벤트 에미터 재생성 필요
> - `.mm` / `.h` 파일 추가 시
> - `RichChatInput.podspec` 수정 시

---

## Usage

```tsx
import { RichChatInput } from 'react-native-rich-chat-input';
import type { RichContentResult } from 'react-native-rich-chat-input';
import { useState } from 'react';
import { Image, StyleSheet, View } from 'react-native';

export default function ChatScreen() {
  const [richPreview, setRichPreview] = useState<RichContentResult | null>(null);
  const [inputHeight, setInputHeight] = useState(44);

  return (
    <View>
      {richPreview && (
        <Image source={{ uri: richPreview.uri }} style={{ width: 200, height: 200 }} />
      )}
      <RichChatInput
        placeholder="Type a message..."
        placeholderTextColor="#999"
        multiline
        maxLength={2000}
        acceptedMimeTypes={['image/*']}
        style={[styles.input, { height: inputHeight }]}
        onChangeText={(text) => console.log(text)}
        onRichContent={(content) => setRichPreview(content)}
        onInputSizeChange={({ height }) => {
          // height = 텍스트 콘텐츠 높이 (padding 미포함)
          // paddingVertical 12 × 2 = 24 추가; minHeight/maxHeight는 style로 제약
          setInputHeight(height + 24);
        }}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  input: {
    minHeight: 44,
    maxHeight: 250,
    paddingVertical: 12,
    paddingHorizontal: 16,
  },
});
```

---

## API

### Props

| Prop | Type | Default | Description |
|---|---|---|---|
| `placeholder` | `string` | — | 입력 힌트 텍스트 |
| `placeholderTextColor` | `ColorValue` | — | 힌트 텍스트 색상 |
| `editable` | `boolean` | `true` | 입력 활성화 여부 |
| `multiline` | `boolean` | `false` | 여러 줄 입력 허용 |
| `maxLength` | `number` | — | 최대 입력 글자 수 |
| `acceptedMimeTypes` | `string[]` | `['image/*']` | 수신할 MIME 타입 목록. 키보드에게 지원 콘텐츠 타입을 알리는 역할도 함 |
| `onChangeText` | `(text: string) => void` | — | 텍스트 변경 이벤트 |
| `onRichContent` | `(content: RichContentResult) => void` | — | Rich Content 수신 이벤트 |
| `onInputSizeChange` | `(size: ContentSizeResult) => void` | — | 텍스트 내용 높이 변경 이벤트. **`multiline` 사용 시 auto-expand에 필수**. `height` state와 함께 사용 (아래 예시 참고) |
| `style` | `ViewStyle` | — | 컨테이너 스타일 (width, height, borderRadius 등 레이아웃 스타일) |

> **참고**: `color`, `fontSize`, `fontFamily` 등 텍스트 스타일 props는 v2에서 지원 예정입니다.

### Event: `onRichContent`

```ts
type RichContentEvent = {
  nativeEvent: {
    uri: string;      // file:// 로컬 캐시 경로
    mimeType: string; // e.g. "image/gif", "image/png", "image/webp", "video/mp4"
  };
};
```

### Event: `onInputSizeChange`

```ts
type ContentSizeResult = {
  width: number;  // dp (Android) / pt (iOS)
  height: number; // 텍스트 콘텐츠 높이 (padding 제외)
};
```

`multiline` 모드에서 텍스트가 바뀔 때마다 발생한다. `height` 값에 컴포넌트의 `paddingVertical * 2`를 더하면 컴포넌트 전체 높이가 된다.

```tsx
const [inputHeight, setInputHeight] = useState(44);

<RichChatInput
  multiline
  style={[styles.input, { height: inputHeight }]} // minHeight/maxHeight로 범위 제한 권장
  onInputSizeChange={({ height }) => {
    // height = 텍스트 콘텐츠 높이 (padding 미포함)
    // paddingVertical: 12 × 2 = 24 추가
    setInputHeight(Math.max(44, Math.min(250, height + 24)));
  }}
/>
```

### Event: `onChangeText`

```ts
type ChangeTextEvent = {
  nativeEvent: {
    text: string;
  };
};
```

### `acceptedMimeTypes` 예시

```tsx
// 모든 이미지 (기본값 권장)
acceptedMimeTypes={['image/*']}

// GIF만 수신
acceptedMimeTypes={['image/gif']}

// 이미지 + 영상
acceptedMimeTypes={['image/*', 'video/*']}
```

---

## 구현 로드맵

> 각 Phase는 독립적으로 브랜치를 나눠 작업한다.

---

### Phase 1 — Android 핵심 구현

**목표**: 키보드/클립보드 Rich Content를 인터셉트하고 JS로 전달한다.

#### ✅ 1-1. `RichChatInputViewNativeComponent.ts` 인터페이스 확정

```ts
import type { BubblingEventHandler, Float, Int32 } from 'react-native/Libraries/Types/CodegenTypes';

type RichContentEvent = Readonly<{ uri: string; mimeType: string }>;
type ChangeTextEvent = Readonly<{ text: string }>;
type ContentSizeChangeEvent = Readonly<{ width: Float; height: Float }>;

interface NativeProps extends ViewProps {
  placeholder?: string;
  placeholderTextColor?: ColorValue;
  editable?: boolean;
  multiline?: boolean;
  maxLength?: Int32;
  acceptedMimeTypes?: string[];
  onChangeText?: BubblingEventHandler<ChangeTextEvent>;
  onRichContent?: BubblingEventHandler<RichContentEvent>;
  onInputSizeChange?: BubblingEventHandler<ContentSizeChangeEvent>;
}
```

스캐폴딩 기본값인 `color` prop은 제거. `number` 대신 `Float` 타입을 사용해야 Codegen이 `TSNumberKeyword` 오류 없이 처리한다. **완료.**

#### ✅ 1-2. `RichChatInputView.kt` — `AppCompatEditText` 기반 재작성

- `View` → `AppCompatEditText` 상속 변경
- `ViewCompat.setOnReceiveContentListener` 등록
  - 허용 MIME 타입: `image/*`
  - `content://` URI를 앱 캐시 디렉토리(`context.cacheDir`)로 즉시 복사
  - 복사된 `file://` 경로와 `mimeType`을 이벤트로 발송
- `placeholder`, `placeholderTextColor` prop 처리

#### ✅ 1-3. `RichChatInputViewManager.kt` — 이벤트 및 Props 등록

- `getExportedCustomBubblingEventTypeConstants()` 오버라이드하여 `onRichContent` 등록
- `@ReactProp`으로 `placeholder`, `placeholderTextColor` 연결
- `color` prop 제거

`placeholder`, `placeholderTextColor`, `editable`, `multiline`, `maxLength`, `acceptedMimeTypes` props 연결 및 `onRichContent`/`onChangeText` 버블링 이벤트 등록 완료.

#### 주의사항

- `content://` URI 복사는 **메인 스레드에서 하면 안 됨** → `Coroutines (IO Dispatcher)` 또는 `ExecutorService` 사용
- Coroutines 사용 시 `build.gradle`에 `kotlinx-coroutines-android` 의존성 추가 필요

---

### Phase 2 — iOS 핵심 구현 및 호환성 수정

**목표**: iOS 키보드 및 클립보드 Rich Content를 인터셉트하고 JS로 전달한다.

#### ✅ 2-1. `RichChatInputView.mm` — `UITextView` 기반 재작성

Objective-C++ (`.mm`) 파일에 private 클래스 `RichChatInputInternalTextView : UITextView`를 도입하여 Fabric 래퍼(`RichChatInputView : RCTViewComponentView`)와 분리하는 구조로 구현.

- `UIView` → `RichChatInputInternalTextView` (`UITextView` 서브클래스)로 교체, `self.contentView`에 설정
- `paste:` 메서드 오버라이드:
  - GIF(`com.compuserve.gif`) → WebP → PNG → Generic Image → `super paste:` 순서로 인터셉트
  - GIF는 `NSData` 직접 사용 (UIImage 변환 시 애니메이션 프레임 손실 방지)
  - 이미지 데이터를 `NSTemporaryDirectory()`에 UUID 파일명으로 비동기 저장 (`dispatch_async`)
  - `file://` 경로와 `mimeType`을 `onRichContent` 이벤트로 발송
  - 이미지가 아닌 경우 `super paste:` 호출로 기본 텍스트 붙여넣기 동작 유지
- `pasteConfiguration` 오버라이드로 iOS 11+ GIF 키보드 등 써드파티 키보드 제안 연동
- `placeholder`, `placeholderTextColor` 구현 (`UILabel` 오버레이 방식, `UITextView`는 기본 미지원)
- `editable`, `multiline`, `maxLength`, `acceptedMimeTypes` props 처리
- `onChangeText` 이벤트: `NSTextStorageDidProcessEditingNotification`으로 발송
- Fabric 뷰 재사용(`prepareForRecycle`) 시 텍스트/placeholder 초기화

#### ✅ 2-2. `RichChatInputView.h` 업데이트

- `@class RichChatInputInternalTextView` 포워드 선언
- `dispatchChangeText:` / `dispatchRichContent:mimeType:` 메서드 선언

#### ✅ 2-3. `react-native-keyboard-controller` 호환성 수정

`react-native-keyboard-controller`는 포커스 시 `KCTextInputCompositeDelegate`를 UITextView의 delegate로 교체하고, 기존 delegate를 내부 forwarding target으로 저장한다.

**문제**: `UITextViewDelegate` 채택 + `self.delegate = self` 구조 시 아래 순서로 무한 재귀 → SIGSEGV crash 발생:

```
KCTextInputCompositeDelegate.forwardingTarget(for:)
  → activeDelegate (UITextView 자신)
    → UITextView 내부 forwarding → delegate (KCTextInputCompositeDelegate)
      → forwardingTarget(for:) 재호출 → ... (무한 재귀 → stack overflow)
```

**해결**:
- `<UITextViewDelegate>` 채택 제거, `self.delegate = self` 제거
- `shouldChangeTextInRange:` delegate 메서드 → `-insertText:` 오버라이드로 교체하여 `maxLength`, `multiline` 제약 처리
- `textViewDidChange:` delegate 이벤트 → `NSTextStorageDidProcessEditingNotification`으로 교체
  - `NSTextStorage` 레벨에서 발생하므로 delegate가 누구든 관계없이 항상 수신됨

#### ✅ 2-4. RTI(Remote Text Input) 단절 방지

iOS 소프트웨어 키보드는 RTI를 통해 XPC로 `insertText:`를 UITextView에 전달한다. first responder 상태에서 UITextView의 frame이 변경되면 RTI 세션이 끊겨 이후 키 입력이 전달되지 않는 문제가 있다.

**문제**: React re-render 시 `updateLayoutMetrics`가 호출되어 `_textView.frame`이 변경되면 RTI 연결이 끊김.

**해결**: `updateLayoutMetrics` 오버라이드에서 first responder 상태일 때, JS가 `height`를 명시적으로 변경한 경우(multiline auto-expand)는 허용하고, 그 외 frame 변경(width, x, y 등)만 되돌린다.

```objc
- (void)updateLayoutMetrics:... {
    CGRect frameBefore = _textView.frame;
    BOOL isFirstResponder = _textView.isFirstResponder;
    CGFloat oldLayoutHeight = RCTCGRectFromRect(oldLayoutMetrics.getContentFrame()).size.height;
    CGFloat newLayoutHeight = RCTCGRectFromRect(layoutMetrics.getContentFrame()).size.height;
    BOOL layoutHeightChanged = fabs(newLayoutHeight - oldLayoutHeight) > 0.5;
    [super updateLayoutMetrics:...];
    BOOL frameChanged = !CGRectEqualToRect(frameBefore, _textView.frame);
    if (frameChanged && isFirstResponder && !layoutHeightChanged) {
        _textView.frame = frameBefore;
    }
}
```

`layoutHeightChanged`가 `true`이면 JS가 의도적으로 높이를 바꾼 것이므로 frame 복원을 건너뛴다. `onInputSizeChange` → `inputHeight` state 갱신 흐름으로 자동 확장이 동작하는 원리.

#### ✅ 2-5. padding 영역 탭 전달

컴포넌트에 `padding` 스타일이 적용된 경우 padding 영역은 `_textView` 바깥이므로 탭이 전달되지 않는 문제를 `hitTest:withEvent:` 오버라이드로 해결. 컴포넌트 bounds 내부 탭은 항상 `_textView`로 라우팅한다.

#### ✅ 2-6. `onInputSizeChange` 높이 측정 타이밍 버그 수정

**증상**:
- `multiline` 모드에서 return을 눌러도 컴포넌트 높이가 변하지 않음
- 시뮬레이터에서 `[TextInputUI] Result accumulator timeout: 0.250000, exceeded` 반복 출력

**원인**: `_textStorageDidChange:`(`NSTextStorageDidProcessEditingNotification` 핸들러)가 `[super insertText:]` 호출 도중에 동기적으로 실행된다. 이 시점에 `sizeThatFits:`를 호출하면 내부적으로 `ensureLayoutForTextContainer:`가 트리거되는데, NSLayoutManager가 아직 편집 사이클 중이어서 **이전 레이아웃 결과(개행 전 높이)를 반환**한다. 결과적으로 `onInputSizeChange`가 변경 없는 높이로 발생하여 auto-expand가 동작하지 않는다. 동시에 `_state->updateState()` 호출이 Fabric의 main thread 작업을 유발해 RTI ack 전송이 250ms를 초과한다.

**해결**: `sizeThatFits:` 측정과 `dispatchInputSizeChange:` 호출을 `dispatch_async(dispatch_get_main_queue(), ...)` 로 다음 runloop으로 연기한다. 이렇게 하면 NSTextStorage 편집 사이클이 완전히 종료된 후 레이아웃이 재계산되므로 정확한 높이를 얻을 수 있고, RTI ack도 즉시 반환된다.

```objc
// Before (잘못된 코드 — 편집 사이클 중 동기 측정)
- (void)_textStorageDidChange:(NSNotification *)note {
    ...
    CGSize contentSize = [self sizeThatFits:...]; // 이전 높이 반환 가능
    [self.eventDelegate dispatchInputSizeChange:contentSize];
}

// After (수정된 코드 — 다음 runloop에서 측정)
- (void)_textStorageDidChange:(NSNotification *)note {
    ...
    dispatch_async(dispatch_get_main_queue(), ^{
        CGSize contentSize = [self sizeThatFits:...]; // 정확한 높이
        [self.eventDelegate dispatchInputSizeChange:contentSize];
    });
}
```

> **중요**: `onInputSizeChange`는 shadow node 자동 측정 메커니즘의 보조 수단이 아닌 **multiline auto-expand의 필수 구현 수단**이다. 사용 측에서 반드시 `inputHeight` state를 관리하고 `style={{ height: inputHeight }}`를 명시적으로 전달해야 한다. 핸들러를 생략하면 높이가 `minHeight`(기본 44)에 고정된다.

---

### Phase 3 — JS/TS Bridge 완성

**목표**: 네이티브 컴포넌트를 사용하기 좋은 TypeScript API로 래핑한다.

#### ✅ 3-1. `src/RichChatInput.tsx` — 사용성 래퍼 컴포넌트 작성

`codegenNativeComponent`를 직접 노출하는 대신, 사용자 친화적인 래퍼 컴포넌트를 작성한다.

- `onChangeText?: (text: string) => void` — `e.nativeEvent.text` 언래핑
- `onRichContent?: (content: RichContentResult) => void` — `e.nativeEvent` 언래핑
- `onInputSizeChange?: (size: ContentSizeResult) => void` — `e.nativeEvent` 언래핑. multiline 자동 확장용
- `RichChatInputProps`, `RichContentResult`, `ContentSizeResult` 타입 export
- `src/index.tsx`에서 `RichChatInput`을 primary export, `RichChatInputView`(네이티브 원본)는 고급 사용자용으로 유지
- `src/shims.d.ts` 추가 — `react-native/Libraries/Types/CodegenTypes` 모듈 타입 선언 누락 수정

```tsx
// 사용 예시
import { RichChatInput } from 'react-native-rich-chat-input';

<RichChatInput
  onChangeText={(text) => setText(text)}
  onRichContent={({ uri, mimeType }) => handleRich(uri, mimeType)}
/>
```

#### ✅ 3-2. 예제 앱 (`example/src/App.tsx`) 업데이트

- 실제 동작 확인용 UI 구성
- GIF/스티커 수신 → 프리뷰 이미지 표시
- `RichChatInput` 래퍼 컴포넌트로 교체하여 이벤트 핸들러 단순화 (`e.nativeEvent` 제거)

#### ✅ 3-3. `src/__tests__/index.test.tsx` — Jest 단위 테스트 작성

`react-test-renderer`를 사용하여 래퍼 컴포넌트의 이벤트 언래핑 로직과 prop 전달을 검증한다.

| 테스트 | 검증 내용 |
|---|---|
| `onChangeText` unwrap | `{ nativeEvent: { text } }` → `string` 변환 |
| `onChangeText` not provided | prop 미전달 시 네이티브 컴포넌트에 `undefined` 전달 |
| `onRichContent` unwrap | `{ nativeEvent: { uri, mimeType } }` → 객체 변환 |
| `onRichContent` not provided | prop 미전달 시 네이티브 컴포넌트에 `undefined` 전달 |
| prop pass-through | `placeholder`, `editable`, `multiline` 등 나머지 props 그대로 전달 |

```sh
yarn test
# Tests: 5 passed, 5 total
```

---

### Phase 4 — 안정화 및 배포

**목표**: 엣지 케이스 처리 및 npm 배포 준비.

- [x] 대용량 GIF 처리 시 메모리 사용량 최적화
- [x] 캐시 파일 정리 전략 수립 (앱 재시작 시 or LRU)
- [x] Android 권한 예외 처리 (FileNotFoundException 등)
- [x] iOS 시뮬레이터 대응 (클립보드 제한)
- [x] iOS `onInputSizeChange` 높이 측정 타이밍 버그 수정 (dispatch_async)
- [x] Jest 단위 테스트 작성
- [x] README Usage 섹션 실제 코드로 업데이트
- [x] npm publish 및 GitHub Release 자동화 검증

#### ✅ 4-1. 대용량 파일 처리 — 20 MB 크기 제한

Android와 iOS 모두 **20 MB** 초과 콘텐츠를 수신 즉시 거부한다.

- **Android** (`copyToCache`): `input.copyTo(output)` 무제한 복사 대신 수동 버퍼 루프로 교체. `totalBytes`가 한계를 초과하면 부분 파일을 삭제하고 `null` 반환.
- **iOS** (`_saveData:mimeType:extension:`): `data.length` 검사를 파일 쓰기 이전에 수행. 초과 시 `NSLog`로 기록하고 즉시 반환.

#### ✅ 4-2. 캐시 파일 정리 — 7일 경과 파일 자동 삭제

컴포넌트 초기화 시(`init` / `initWithFrame:`) 백그라운드에서 오래된 캐시 파일을 정리한다.

- **Android** (`cleanStaleCacheFiles`): `context.cacheDir`에서 `rich_content_` 접두사 파일 중 7일 이상 경과한 것을 `Dispatchers.IO`에서 삭제.
- **iOS** (`_cleanStaleTempFiles`): `NSTemporaryDirectory()`에서 동일 접두사 파일의 `NSFileModificationDate`를 확인해 `QOS_CLASS_BACKGROUND` 큐에서 삭제.

#### ✅ 4-3. Android 권한 예외 처리

기존 `catch (e: Exception) { null }` 단일 블록을 세분화했다.

| 예외 | 처리 |
|---|---|
| `FileNotFoundException` | `Log.e` — URI 만료 또는 파일 없음 |
| `SecurityException` | `Log.e` — 읽기 권한 없음 |
| `IOException` | `Log.e` — I/O 오류 |
| `Exception` | `Log.e` — 예상치 못한 오류 |

`openInputStream` 실패와 파일 복사 실패를 분리하여 원인을 정확히 추적할 수 있다.

#### ✅ 4-4. iOS 메모리 이중 할당 제거 및 시뮬레이터 대응

**메모리 최적화**: 기존 제네릭 이미지 처리에서 `pb.image → UIImagePNGRepresentation()` 방식(디코딩 + 재인코딩으로 메모리 2배 사용)을 `[pb dataForPasteboardType:]`으로 raw 바이트를 직접 읽는 방식으로 교체.

**시뮬레이터 대응**: `paste:` 및 `pasteConfiguration`을 `#if TARGET_OS_SIMULATOR` / `#else`로 분기.
- 시뮬레이터: `[super paste:sender]`로 폴스루 (키보드 앱이 없으므로 rich content 인터셉트 불필요)
- 실제 디바이스: 기존 UIPasteboard 인터셉트 로직 유지

#### ✅ 4-5. npm publish 및 GitHub Release 자동화

`.github/workflows/release.yml`을 추가했다. `v*.*.*` 형식의 태그를 푸시하면 자동으로 실행된다.

```
[로컬] yarn release  →  package.json 버전 bump + git tag 생성
         ↓
[CI] release.yml 트리거
         ↓
yarn prepare  →  yarn test  →  npm publish  →  GitHub Release 생성
```

> **배포 전 필수**: repository Settings → Secrets에 `NPM_TOKEN` (npm Granular Access Token) 등록 필요.

---

## 프로젝트 구조

```
react-native-rich-chat-input/
├── android/
│   └── src/main/java/com/whosup/packages/richinput/
│       ├── RichChatInputView.kt          # AppCompatEditText 서브클래스 (핵심 로직)
│       ├── RichChatInputViewManager.kt   # Fabric ViewManager
│       └── RichChatInputPackage.kt       # React Native 패키지 등록
├── ios/
│   ├── RichChatInputView.h               # 헤더
│   └── RichChatInputView.mm              # UITextView 기반 구현
├── src/
│   ├── index.tsx                         # 퍼블릭 API 진입점
│   ├── RichChatInput.tsx                 # 사용자 친화적 래퍼 컴포넌트
│   ├── RichChatInputViewNativeComponent.ts  # Codegen 명세 (네이티브 브릿지)
│   ├── shims.d.ts                        # CodegenTypes 모듈 타입 선언
│   └── __tests__/
│       └── index.test.tsx               # Jest 단위 테스트
├── example/                              # 예제 앱
├── RichChatInput.podspec                 # iOS CocoaPods 스펙
└── package.json
```

---

## Troubleshooting

### `No such file or directory` — ReactCodegen 헤더 누락 (iOS)

```
error: .../build/generated/ios/react/renderer/components/<library>/Props.h: No such file or directory
(in target 'ReactCodegen' from project 'Pods')
```

`build/generated`가 오래되거나 불완전한 상태일 때 발생한다. Codegen 출력 디렉토리를 삭제 후 `pod install`로 재생성한다.

```sh
cd <your-app>/ios
rm -rf build/generated
pod install
```

그래도 해결되지 않으면 Pods까지 완전히 초기화한다.

```sh
cd <your-app>/ios
rm -rf build/generated Pods Podfile.lock
pod install
```

---

### multiline 모드에서 높이가 변하지 않음 (iOS)

return 키를 눌러도 컴포넌트 높이가 `minHeight`에 고정되는 경우, `onInputSizeChange` 핸들러가 없거나 `height` state를 style에 전달하지 않은 것이 원인이다.

```tsx
// ❌ 잘못된 사용 — shadow node 자동 측정에만 의존 (동작하지 않음)
<RichChatInput multiline style={styles.input} />

// ✅ 올바른 사용 — onInputSizeChange로 height state 관리
const [inputHeight, setInputHeight] = useState(44);

<RichChatInput
  multiline
  style={[styles.input, { height: inputHeight }]}
  onInputSizeChange={({ height }) => {
    setInputHeight(height + paddingVertical * 2);
  }}
/>
```

---

### `[TextInputUI] Result accumulator timeout: 0.250000, exceeded` (iOS 시뮬레이터)

iOS 시뮬레이터 전용 경고로 실제 디바이스에서는 발생하지 않는다. 시뮬레이터의 소프트웨어 키보드가 Mac 키보드를 RTI(Remote Text Input)로 전달할 때, 핸들러에서 동기적으로 Fabric 작업을 수행하면 250ms 내에 RTI ack를 반환하지 못해 발생한다. v2.6 이후(Phase 2-6) `dispatch_async` 처리로 해소됐다. 이 경고가 보이면 라이브러리 버전을 확인한다.

---

### `yarn prepare` 실패 — `ERR_REQUIRE_ESM` (arktype)

```
Error [ERR_REQUIRE_ESM]: require() of ES Module .../arktype/out/index.js not supported.
```

`react-native-builder-bob` 0.40.18이 ESM-only인 `arktype` 2.x를 CJS `require()`로 로드하려 해서 발생하는 버그다. 개발 중에는 `yarn prepare`를 실행할 필요가 없다 ([로컬 개발 빌드 순서](#로컬-개발-빌드-순서) 참고). npm 배포가 필요한 경우 `react-native-builder-bob` 수정 릴리즈를 기다리거나 Node.js 22로 전환한다.

---

## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)

---

## License

MIT
