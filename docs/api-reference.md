# API Reference

## Component

```ts
import { RichChatInput } from 'react-native-rich-chat-input';
```

`RichChatInput` is a `forwardRef` component. Its ref exposes the [imperative API](#imperative-api).

## Props

| Prop | Type | Default | Description |
|---|---|---|---|
| `placeholder` | `string` | — | Hint text shown when the input is empty. |
| `placeholderTextColor` | `ColorValue` | platform default | Color of the placeholder text. |
| `editable` | `boolean` | `true` | When `false`, disables text editing and IME connection. |
| `multiline` | `boolean` | `false` | Enables newline input. **Required for auto-grow** (see `onInputSizeChange`). |
| `maxLength` | `number` | — | Maximum input length in characters. Enforced natively. |
| `fontSize` | `number` | platform default | Text font size in `sp` (Android) / `pt` (iOS). |
| `acceptedMimeTypes` | `string[]` | `['image/*']` | MIME types the input advertises to the IME (`EditorInfoCompat.setContentMimeTypes` on Android, `pasteConfiguration` on iOS). Glob patterns like `image/*` are supported. |
| `style` | `StyleProp<ViewStyle>` | — | Standard `ViewStyle` (size, padding, border, background, etc.). |
| `onChangeText` | `(text: string) => void` | — | Called with the current text after each edit. |
| `onRichContent` | `(content: RichContentResult) => void` | — | Called when the IME/clipboard delivers an image/GIF/sticker. See [`features/rich-content.md`](./features/rich-content.md). |
| `onInputSizeChange` | `(size: ContentSizeResult) => void` | — | Called when the text content's height changes. **Required for multiline auto-grow**. See [`features/auto-grow-height.md`](./features/auto-grow-height.md). |
| `onError` | `(error: RichChatInputError) => void` | — | Called when the native side detects a recoverable error. Forward to your error tracker. See [`features/error-handling.md`](./features/error-handling.md). |

Text style props (`color`, `fontFamily`, etc.) other than `fontSize` are not yet supported.

## Imperative API

The ref is typed as `RichChatInputRef`:

```ts
import type { RichChatInputRef } from 'react-native-rich-chat-input';

interface RichChatInputRef {
  /**
   * Clears the input text. Also resets the IME composing state on both
   * platforms so the next message starts with a clean keyboard daemon
   * session — required after sending to prevent stuck-character bugs.
   */
  clear: () => void;
}
```

Usage:

```tsx
const ref = useRef<RichChatInputRef>(null);
// ...
ref.current?.clear();
```

## Event payload types

### `RichContentResult`

```ts
interface RichContentResult {
  uri: string;       // file:// path under the app cache directory
  mimeType: string;  // e.g. "image/gif", "image/png", "image/webp"
}
```

The file is owned by your app's cache directory (Android: `context.cacheDir`, iOS: `NSTemporaryDirectory()`) and will be deleted automatically after 7 days. See [`features/rich-content.md`](./features/rich-content.md#cache-lifecycle).

### `ContentSizeResult`

```ts
interface ContentSizeResult {
  width: number;   // dp (Android) / pt (iOS) — the input's own width
  height: number;  // text content height, **excluding** vertical padding
}
```

Add `style.paddingVertical * 2` (or your manual top + bottom) to `height` to get the total component height.

### `RichChatInputError`

```ts
interface RichChatInputError {
  code: RichChatInputErrorCode;
  message: string;
  /** Underlying exception class (e.g. "java.io.FileNotFoundException"), or "" when N/A. */
  nativeClass: string;
  /** Localized exception message, or "" when N/A. */
  nativeMessage: string;
  /** Native stack trace string, or "" when N/A. */
  nativeStack: string;
}
```

### `RichChatInputErrorCode`

| Code | Platform | Meaning |
|---|---|---|
| `RICH_CONTENT_MIME_UNKNOWN` | Android | `ContentResolver.getType` returned `null` for the pasted URI. |
| `RICH_CONTENT_OPEN_FAILED` | Android | `ContentResolver.openInputStream` returned `null`. |
| `RICH_CONTENT_FILE_NOT_FOUND` | Android | `FileNotFoundException` reading the content URI (usually expired permission). |
| `RICH_CONTENT_PERMISSION_DENIED` | Android | `SecurityException` reading the content URI. |
| `RICH_CONTENT_IO_ERROR` | Android | `IOException` reading the content URI. |
| `RICH_CONTENT_TOO_LARGE` | Android, iOS | Pasted content exceeds the 20 MB limit. |
| `RICH_CONTENT_CACHE_WRITE_FAILED` | Android, iOS | Failed to write the content bytes to the cache directory. |
| `INPUT_SIZE_DISPATCH_FAILED` | Android | After a width change, the EditText's layout was not available for remeasure (auto-grow may render stale). |
| `IME_MARKED_TEXT_PERSISTED` | iOS | `replaceRange(markedTextRange, "")` did not clear the IME's marked range — the `-unmarkText` fallback fired. |
| `IME_STALE_TEXT_FLUSHED` | Android, iOS | The IME re-injected characters after `clear()`. Subsequent sends may include leftover composing text. |

For Sentry / fingerprinting guidance, see [`features/error-handling.md`](./features/error-handling.md).

## Low-level escape hatch

```ts
import { RichChatInputView } from 'react-native-rich-chat-input';
```

This is the raw Codegen `HostComponent` — props arrive as `NativeSyntheticEvent` payloads (not unwrapped). Use it only when the wrapper's API doesn't fit your needs.
