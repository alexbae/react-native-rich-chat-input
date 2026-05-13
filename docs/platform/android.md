# Android implementation notes

Source files (Kotlin):
- `android/src/main/java/com/whosup/packages/richinput/RichChatInputView.kt` — the `AppCompatEditText` subclass with all the behavior.
- `android/src/main/java/com/whosup/packages/richinput/RichChatInputViewManager.kt` — Fabric ViewManager.
- `android/src/main/java/com/whosup/packages/richinput/RichChatInputPackage.kt` — RN package registration.

Package namespace: `com.whosup.packages.richinput`. Min SDK 24, compile SDK 36, Kotlin 2.0.21.

## Base class: `AppCompatEditText`

We extend `AppCompatEditText` (not `EditText`) so:
- Material Design styling applies.
- `ViewCompat.setOnReceiveContentListener` works on every API level from 24 up (the platform-native `setOnReceiveContentListener` only exists on API 31+; AndroidX backports it).

## Rich content (paste, keyboard image)

### `OnReceiveContentListener`

Registered once in `init`:

```kotlin
ViewCompat.setOnReceiveContentListener(this, currentMimeTypes) { _, contentInfo ->
    // ... readAndDispatchContent(...) ...
}
```

This single listener covers both the keyboard "send image" gesture (GBoard / Samsung / Naver / ...) and the system clipboard paste gesture (Ctrl+V from a Bluetooth keyboard, long-press paste menu).

### Permission window

The `content://` URI delivered by the listener has a **temporary read grant** that expires when the listener returns. To stay safe, we read the entire byte stream synchronously on the main thread, then dispatch the cache-write to `Dispatchers.IO`:

```kotlin
val bytes = inputStream.use { it.readBytes() }   // sync, on main thread

coroutineScope.launch {
    val fileUri = withContext(Dispatchers.IO) {
        writeBytesToCache(bytes, mimeType)
    }
    if (fileUri != null) dispatchRichContentEvent(fileUri, mimeType)
}
```

This intentionally blocks the UI thread for the duration of the read — see [`known-issues.md`](../known-issues.md#3-android-readanddispatchcontent-blocks-the-ui-thread).

### Advertising MIME types to the keyboard

`acceptedMimeTypes` is passed to **both**:

1. The `OnReceiveContentListener` registration (filters what content the listener accepts).
2. `EditorInfoCompat.setContentMimeTypes(outAttrs, currentMimeTypes)` in `onCreateInputConnection` (tells the IME which types to enable — e.g. GBoard's GIF button is grayed out if the field doesn't advertise `image/gif`).

When the prop changes after mount, we call `InputMethodManager.restartInput(this)` so the keyboard re-reads `EditorInfo` and updates its UI.

### Error paths

Each failure path emits `onError` with a specific code (see [`features/error-handling.md`](../features/error-handling.md)):

| Path | Code |
|---|---|
| `ContentResolver.getType` returns null | `RICH_CONTENT_MIME_UNKNOWN` |
| `openInputStream` returns null | `RICH_CONTENT_OPEN_FAILED` |
| `FileNotFoundException` | `RICH_CONTENT_FILE_NOT_FOUND` |
| `SecurityException` | `RICH_CONTENT_PERMISSION_DENIED` |
| `IOException` during read | `RICH_CONTENT_IO_ERROR` |
| `bytes.size > 20 MB` | `RICH_CONTENT_TOO_LARGE` |
| `IOException` writing to cache | `RICH_CONTENT_CACHE_WRITE_FAILED` |

## `clear()` resets IME composing state

The public `clear()` command does more than `editableText.clear()`. The full sequence:

```kotlin
fun clearTextSafely() {
    val editable = editableText ?: return
    BaseInputConnection.removeComposingSpans(editable)
    editable.clear()
    InputMethodManager.restartInput(this)

    post {
        if (editableText?.isNotEmpty() == true) {
            dispatchErrorEvent("IME_STALE_TEXT_FLUSHED", ...)
        }
    }
}
```

### Why each step matters

| Step | What it does | What breaks without it |
|---|---|---|
| `removeComposingSpans(editable)` | Strips the `Spannable.SPAN_COMPOSING` flags that the IME attached to the in-progress composition region. | After `clear()` the IME sees its previously-composing indices still marked. Subsequent keystrokes merge into / get absorbed by the phantom composing region. |
| `editable.clear()` | Drops the visible text. | Obvious. |
| `restartInput(this)` | Forces the keyboard daemon to tear down its `InputConnection` and recreate it, discarding any composition events still in flight. | Samsung keyboard swipe / predict mode keeps re-flushing the previous composition. The corruption persists across every send until the app process is killed (because the EditText instance and its stale span state survive focus toggles). |
| Deferred check & `IME_STALE_TEXT_FLUSHED` | One-frame-later check that text isn't non-empty. | If the corruption still happens, you have no telemetry. |

This fixes the symptom users reported as: "after sending, the next message is truncated or contains characters from the previous send, and it stays broken until the app restarts."

## Input size dispatch

`onInputSizeChange` fires from two places:

1. `afterTextChanged` (every edit): synchronous dispatch via `dispatchInputSizeChangeEvent()`. Reads `layout.height` and `compoundPaddingTop/Bottom`.
2. `onSizeChanged(w, h, oldw, oldh)` (width change from rotation, fold, multi-window): when `w != oldw && w > 0`, schedules a deferred dispatch with `post { dispatchInputSizeChangeEvent(reportMissingLayout = true) }`. The `post` is needed because `getLayout()` may still reflect the **old** width during the synchronous `onSizeChanged` callback; deferring to the next message-loop tick gives Android time to rebuild the `StaticLayout` against the new width.

If the layout is still null after the post — meaning the EditText hasn't been measured against the new width yet — we fire `INPUT_SIZE_DISPATCH_FAILED` so the host can detect the (rare) edge case.

## Threading

A single `CoroutineScope(Dispatchers.Main + SupervisorJob())` is created at construction. It is used for:
- Cache writes (`Dispatchers.IO` via `withContext`).
- Stale-file cleanup on first mount.
- Error dispatches from IO-side callbacks (marshalled back to main via the scope's main dispatcher).

The scope is `cancel()`ed in `onDetachedFromWindow`. **It is not currently recreated on reattach** — see [`known-issues.md`](../known-issues.md#1-coroutinescope-not-recreated-on-reattach).

## Cache cleanup on mount

In `init`, on `Dispatchers.IO`:

```kotlin
context.cacheDir.listFiles { f ->
    f.name.startsWith("rich_content_") &&
    System.currentTimeMillis() - f.lastModified() > 7.days
}?.forEach { it.delete() }
```

There is no LRU tracking; files older than 7 days by `lastModified` are deleted. The host app is responsible for moving files it wants to keep elsewhere.

## Filename mapping

`mimeTypeToExtension()` recognizes: `gif`, `webp`, `png`, `jpeg`/`jpg`, `svg`, `mp4`, `webm`, `3gp`, `mkv`. Anything else gets `.bin`.

## ViewManager event registration

Fabric is supposed to handle event registration through the generated `RichChatInputViewManagerDelegate`, but we **also** override `getExportedCustomBubblingEventTypeConstants` to register `topChangeText`, `topRichContent`, `topInputSizeChange`, `topError` manually. This is defensive — RN's old-architecture registry path is still consulted in some hot-reload scenarios.

If you add a new event to the codegen spec, remember to also add it to this map.

## Recent bug fixes (history)

- **IME composing-span clear** (resolved the "incomplete message / merged messages / persists until app restart" bug). See "[`clear()` resets IME composing state](#clear-resets-ime-composing-state)" above.
- **Resize re-dispatch on width change** (resolved auto-grow being stuck at old wrap width after device rotation / fold / multi-window). See "[Input size dispatch](#input-size-dispatch)" above.
- **IME-advertised MIME types** (resolved GBoard's GIF button being grayed out when host app passed a custom `acceptedMimeTypes`). See "[Advertising MIME types to the keyboard](#advertising-mime-types-to-the-keyboard)" above.
