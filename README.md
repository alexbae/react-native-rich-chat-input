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
- [Usage](#usage)
- [API](#api)
- [구현 로드맵](#구현-로드맵)
  - [Phase 1 — Android 핵심 구현](#phase-1--android-핵심-구현)
  - [Phase 2 — iOS 핵심 구현](#phase-2--ios-핵심-구현)
  - [Phase 3 — JS/TS Bridge 완성](#phase-3--jsts-bridge-완성)
  - [Phase 4 — 안정화 및 배포](#phase-4--안정화-및-배포)
- [프로젝트 구조](#프로젝트-구조)
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

## Usage

```tsx
import { RichChatInputView } from 'react-native-rich-chat-input';
import { useState } from 'react';
import { Image, View } from 'react-native';

export default function ChatScreen() {
  const [richPreview, setRichPreview] = useState<{ uri: string; mimeType: string } | null>(null);

  return (
    <View>
      {richPreview && (
        <Image source={{ uri: richPreview.uri }} style={{ width: 200, height: 200 }} />
      )}
      <RichChatInputView
        placeholder="Type a message..."
        placeholderTextColor="#999"
        multiline
        maxLength={2000}
        acceptedMimeTypes={['image/*']}
        onChangeText={(e) => console.log(e.nativeEvent.text)}
        onRichContent={(e) => setRichPreview(e.nativeEvent)}
      />
    </View>
  );
}
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
| `onChangeText` | `BubblingEventHandler` | — | 텍스트 변경 이벤트 |
| `onRichContent` | `BubblingEventHandler` | — | Rich Content 수신 이벤트 |
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
type RichContentEvent = Readonly<{ uri: string; mimeType: string }>;
type ChangeTextEvent = Readonly<{ text: string }>;

interface NativeProps extends ViewProps {
  placeholder?: string;
  placeholderTextColor?: ColorValue;
  editable?: boolean;
  multiline?: boolean;
  maxLength?: Int32;
  acceptedMimeTypes?: string[];
  onChangeText?: BubblingEventHandler<ChangeTextEvent>;
  onRichContent?: BubblingEventHandler<RichContentEvent>;
}
```

스캐폴딩 기본값인 `color` prop은 제거. **완료.**

#### ✅ 1-2. `RichChatInputView.kt` — `AppCompatEditText` 기반 재작성

- `View` → `AppCompatEditText` 상속 변경
- `ViewCompat.setOnReceiveContentListener` 등록
  - 허용 MIME 타입: `image/*`
  - `content://` URI를 앱 캐시 디렉토리(`context.cacheDir`)로 즉시 복사
  - 복사된 `file://` 경로와 `mimeType`을 이벤트로 발송
- `placeholder`, `placeholderTextColor` prop 처리

#### 1-3. `RichChatInputViewManager.kt` — 이벤트 및 Props 등록

- `getExportedCustomBubblingEventTypeConstants()` 오버라이드하여 `onRichContent` 등록
- `@ReactProp`으로 `placeholder`, `placeholderTextColor` 연결
- `color` prop 제거

#### 주의사항

- `content://` URI 복사는 **메인 스레드에서 하면 안 됨** → `Coroutines (IO Dispatcher)` 또는 `ExecutorService` 사용
- Coroutines 사용 시 `build.gradle`에 `kotlinx-coroutines-android` 의존성 추가 필요

---

### Phase 2 — iOS 핵심 구현

**목표**: iOS 키보드 및 클립보드 Rich Content를 인터셉트하고 JS로 전달한다.

#### 2-1. `RichChatInputView.mm` — `UITextView` 기반 재작성

현재 스캐폴딩이 Objective-C++ (`.mm`) 파일이므로 Objective-C로 구현한다.

- `UIView` → `UITextView` 서브클래싱으로 교체
- `paste:` 메서드 오버라이드:
  - `UIPasteboard.general`에서 이미지 타입 확인 (`image/gif`, `public.image` 등)
  - 이미지 데이터를 `NSTemporaryDirectory()`에 파일로 저장
  - `file://` 경로와 `mimeType`을 `onRichContent` 이벤트로 발송
  - 이미지가 아닌 경우 `super.paste(sender)` 호출로 기본 동작 유지
- `placeholder`, `placeholderTextColor` 구현 (UITextView는 기본 placeholder 미지원 → 직접 구현)

#### 2-2. `RichChatInputView.h` 업데이트

`UITextView` 기반으로 변경된 인터페이스 반영.

---

### Phase 3 — JS/TS Bridge 완성

**목표**: 네이티브 컴포넌트를 사용하기 좋은 TypeScript API로 래핑한다.

#### 3-1. `src/index.tsx` — 사용성 래퍼 컴포넌트 작성

`codegenNativeComponent`를 직접 노출하는 대신, 사용자 친화적인 래퍼 컴포넌트를 작성한다.

```tsx
// src/RichChatInput.tsx
export interface RichContentResult {
  uri: string;
  mimeType: string;
}

export interface RichChatInputProps {
  onRichContent?: (content: RichContentResult) => void;
  onChangeText?: (text: string) => void;
  placeholder?: string;
  // ... 기타 TextInput 호환 props
}
```

#### 3-2. 예제 앱 (`example/src/App.tsx`) 업데이트

- 실제 동작 확인용 UI 구성
- GIF/스티커 수신 → 프리뷰 이미지 표시

---

### Phase 4 — 안정화 및 배포

**목표**: 엣지 케이스 처리 및 npm 배포 준비.

- [ ] 대용량 GIF 처리 시 메모리 사용량 최적화
- [ ] 캐시 파일 정리 전략 수립 (앱 재시작 시 or LRU)
- [ ] Android 권한 예외 처리 (FileNotFoundException 등)
- [ ] iOS 시뮬레이터 대응 (클립보드 제한)
- [ ] Jest 단위 테스트 작성
- [ ] README Usage 섹션 실제 코드로 업데이트
- [ ] npm publish 및 GitHub Release 자동화 검증

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
│   └── RichChatInputViewNativeComponent.ts  # Codegen 명세 (네이티브 브릿지)
├── example/                              # 예제 앱
├── RichChatInput.podspec                 # iOS CocoaPods 스펙
└── package.json
```

---

## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)

---

## License

MIT
