# Error handling & observability

The native side detects a number of recoverable error conditions — paste failures, IME state corruption, layout race conditions — and surfaces them through a single `onError` event. The library does **not** bundle an error tracker; the host app forwards the event to whatever tool it uses (Sentry, Bugsnag, Datadog, Crashlytics, internal logging…).

## The event

```ts
interface RichChatInputError {
  code: RichChatInputErrorCode;
  message: string;
  nativeClass: string;    // "" when no underlying exception
  nativeMessage: string;  // "" when no underlying exception
  nativeStack: string;    // "" when no underlying exception
}
```

```tsx
<RichChatInput onError={(err) => { /* forward */ }} />
```

The event is bubbling, so a parent `View` with `onError` will receive it too — useful for centralized telemetry.

## Codes

| Code | Platforms | Severity | Triggered when |
|---|---|---|---|
| `RICH_CONTENT_MIME_UNKNOWN` | Android | warn | The IME passed a content URI but `ContentResolver.getType` returned null. |
| `RICH_CONTENT_OPEN_FAILED` | Android | warn | `ContentResolver.openInputStream` returned null for the URI. |
| `RICH_CONTENT_FILE_NOT_FOUND` | Android | warn | `FileNotFoundException` reading the URI (usually expired temp permission). |
| `RICH_CONTENT_PERMISSION_DENIED` | Android | error | `SecurityException` — the temp read grant expired before we finished reading. |
| `RICH_CONTENT_IO_ERROR` | Android | error | Other `IOException` during the byte read. |
| `RICH_CONTENT_TOO_LARGE` | Both | info | User pasted content > 20 MB. Expected for huge GIFs; benign. |
| `RICH_CONTENT_CACHE_WRITE_FAILED` | Both | error | Disk full / sandbox issue writing to cache. |
| `INPUT_SIZE_DISPATCH_FAILED` | Android | warn | After a width change, `EditText.getLayout()` was null when we tried to remeasure. The auto-grow height may be one frame stale. |
| `IME_MARKED_TEXT_PERSISTED` | iOS | warn | The primary IME-clear path (`replaceRange(markedRange, "")`) didn't drop the marked range — we fell back to `-unmarkText`. |
| `IME_STALE_TEXT_FLUSHED` | Both | error | The IME re-injected characters after `clear()`. **This is the Korean-stuck-character / Android send-corruption bug** — the library has fixes that should keep this from firing, but if it does, the next message may contain leftover composing text. |

When the `code` is `IME_STALE_TEXT_FLUSHED`, the `message` field includes a sample of the leftover characters (truncated to 32 chars) so you can see in Sentry what was stuck.

## Recommended Sentry wiring

```ts
import * as Sentry from '@sentry/react-native';
import type { RichChatInputError } from 'react-native-rich-chat-input';

export function reportRichChatInputError(err: RichChatInputError) {
  // Synthesize an Error so Sentry produces a clean issue title.
  const e = new Error(`[RichChatInput:${err.code}] ${err.message}`);

  Sentry.captureException(e, {
    // tags become filterable / groupable in Sentry's UI
    tags: {
      rich_chat_input_code: err.code,
      rich_chat_input_native_class: err.nativeClass || 'none',
    },
    // extra is attached to the event for debugging
    extra: {
      nativeMessage: err.nativeMessage,
      nativeStack: err.nativeStack,
    },
    // fingerprint forces grouping by code (independent of stack trace) so
    // iOS and Android occurrences of the same logical bug merge into one
    // Sentry issue
    fingerprint: ['rich-chat-input', err.code],
  });
}
```

Wire it in:

```tsx
<RichChatInput onError={reportRichChatInputError} />
```

## Severity guidance

If you want to tune what Sentry sees:

```ts
const SEVERITY_LEVEL: Record<string, 'info' | 'warning' | 'error'> = {
  RICH_CONTENT_TOO_LARGE: 'info',
  RICH_CONTENT_MIME_UNKNOWN: 'warning',
  RICH_CONTENT_OPEN_FAILED: 'warning',
  RICH_CONTENT_FILE_NOT_FOUND: 'warning',
  INPUT_SIZE_DISPATCH_FAILED: 'warning',
  IME_MARKED_TEXT_PERSISTED: 'warning',
  // everything else: 'error'
};

Sentry.captureException(e, {
  level: SEVERITY_LEVEL[err.code] ?? 'error',
  /* ... */
});
```

`RICH_CONTENT_TOO_LARGE` in particular will fire on every "I'm gonna paste this 30 MB GIF" interaction and is informational — don't let it page anyone.

## Why not bundle a Sentry dependency?

The library targets host apps that already have their own error tracker (and often a specific pinned version of `@sentry/react-native`). Bundling a peer dependency on Sentry would:

- Force host apps onto a specific `sentry-android` / `sentry-cocoa` major version.
- Risk runtime `NoSuchMethodError`s when the host's pinned Sentry SDK is older / newer.
- Lock out non-Sentry users (Bugsnag, Datadog, etc.).

A SDK-agnostic `onError` event gives full native context (`nativeClass` / `nativeStack`) without any version coupling.

## Native sources

For where each code is emitted in the native code:

- **Android**: `android/src/main/java/com/whosup/packages/richinput/RichChatInputView.kt` — search for `dispatchErrorEvent(`
- **iOS**: `ios/RichChatInputView.mm` — search for `dispatchError:`
