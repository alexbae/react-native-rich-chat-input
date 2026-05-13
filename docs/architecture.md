# Architecture

## Design rationale

### Rich content is rendered as a side-channel, not inline

React Native's stock `TextInput` cannot render images/GIFs inline because Fabric's `AttributedString` model has no first-class image span. Building that on top of `AttributedString` is high-effort and fragile across IMEs.

Instead, this library keeps the text input as plain text and emits rich content as a **separate event** (`onRichContent`) with a `file://` URI that the host app renders as a preview chip/thumbnail in its own UI (above/below the input, alongside the message, etc.). This mirrors what production chat apps (KakaoTalk, Slack) do.

Sequence:

```
[user picks GIF in keyboard / pastes image]
        ↓
[native intercepts content:// or pasteboard]
        ↓
[bytes copied to app cache → file:// URI]
        ↓
[onRichContent { uri, mimeType } fires on JS]
        ↓
[host app renders preview from file:// URI]
```

### Why `content://` must be copied **immediately**

On Android, the keyboard passes content via `OnReceiveContentListener` with a **temporary URI permission** scoped to the current input event. If you persist that URI and try to read it later (e.g. on send), it will fail with `SecurityException` or `FileNotFoundException`. The library copies to `context.cacheDir` synchronously on the main thread before the permission window closes, then dispatches the resulting `file://` URI to JS. See [`platform/android.md`](./platform/android.md#rich-content-permission-window) for the detailed contract.

iOS has the analogous problem with `UIPasteboard`: between paste detection and read, the user can clear the pasteboard. We read immediately in `paste:` override.

## Component model

```
RichChatInput (JS, src/RichChatInput.tsx)
  ├─ forwardRef → useImperativeHandle({ clear })
  ├─ unwraps NativeSyntheticEvent for each callback
  └─ renders NativeRichChatInputView
       │
       ▼
Codegen spec (src/RichChatInputViewNativeComponent.ts)
       │  (codegenNativeComponent + codegenNativeCommands)
       ▼
┌──────────────────────────┬──────────────────────────┐
│ Android                  │ iOS                      │
├──────────────────────────┼──────────────────────────┤
│ RichChatInputView.kt     │ RichChatInputView.mm     │
│  (AppCompatEditText)     │  (RCTViewComponentView)  │
│                          │   └ contentView:         │
│                          │     RichChatInputInternal│
│                          │     TextView (UITextView)│
│ RichChatInputViewManager │ Codegen-generated        │
│   .kt (extends Codegen   │  RichChatInputViewSpec   │
│   delegate)              │                          │
└──────────────────────────┴──────────────────────────┘
```

## Codegen flow

The TypeScript file `src/RichChatInputViewNativeComponent.ts` is the **single source of truth** for the native interface. Codegen reads it and produces:

- **iOS**: `Props.h`, `EventEmitters.h/.cpp`, `ShadowNodes.h/.cpp`, `ComponentDescriptors.h` under `build/generated/ios/react/renderer/components/RichChatInputViewSpec/`
- **Android**: a `RichChatInputViewManagerDelegate` Java class that the Kotlin `RichChatInputViewManager` extends, plus prop interfaces

Code that depends on Codegen output:
- iOS: `RichChatInputView.mm` casts the event emitter to `RichChatInputViewEventEmitter` and calls `emitter->onChangeText(...)`, etc.
- iOS: `RichChatInputMeasuringShadowNode.h` uses the codegen-emitted `RichChatInputViewComponentName` extern symbol.
- Android: `RichChatInputViewManager` extends `RichChatInputViewManagerInterface` and `RichChatInputViewManagerDelegate` (codegen-generated).

### Event registration

The Codegen Android delegate auto-maps prop callbacks (`onChangeText`, etc.) but the bubbling-event registry (`topChangeText` → `onChangeText`) is **also** declared explicitly in `RichChatInputViewManager.kt#getExportedCustomBubblingEventTypeConstants`. This redundancy is intentional defense — RN's old-architecture event registry path is still consulted in some hot-reload scenarios.

## Native height measurement (iOS only)

iOS uses a custom Yoga `ShadowNode` (`RichChatInputMeasuringShadowNode`) that stores the content height in Fabric state. The `UITextView` measures itself via `sizeThatFits:` on the main thread (deferred to the next runloop — see [`platform/ios.md`](./platform/ios.md#height-measurement-timing)) and writes the result back via `state->updateState()`. Yoga then re-lays out without a JS roundtrip.

However, **the `onInputSizeChange` event is still the authoritative auto-grow mechanism** — the shadow node is supplementary. The host app must wire `onInputSizeChange` → `height` state → `style={{ height }}`. See [`features/auto-grow-height.md`](./features/auto-grow-height.md).

Android does not use a custom shadow node; `onInputSizeChange` is the only path.

## Event dispatch direction

All events bubble (`BubblingEventHandler<...>`). The library does not declare any direct events — host apps can intercept them via parent `View` if needed. Imperative commands (`clear`) are the only direction-reversed channel (JS → native).

## Threading model

| Operation | Thread |
|---|---|
| Android `OnReceiveContent` callback | Main |
| Android `ContentResolver.readBytes` | **Main** (intentional, see [`platform/android.md`](./platform/android.md#rich-content-permission-window) and [`known-issues.md`](./known-issues.md#3-android-readanddispatchcontent-blocks-the-ui-thread)) |
| Android cache write | `Dispatchers.IO` via `coroutineScope` |
| iOS `paste:` override | Main |
| iOS data write to `NSTemporaryDirectory()` | `dispatch_async` global queue (`QOS_CLASS_USER_INITIATED`) |
| iOS `sizeThatFits` + dispatchInputSizeChange | Deferred to next main runloop via `dispatch_async(main)` |
