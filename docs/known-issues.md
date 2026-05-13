# Known issues

This document captures issues found during code review that are **not yet fixed**. Each entry lists severity, symptom, the line of code that's responsible, current workaround if any, and the planned fix direction.

For issues that **are** fixed, see the "Recent bug fixes (history)" sections in [`platform/android.md`](./platform/android.md#recent-bug-fixes-history) and [`platform/ios.md`](./platform/ios.md#recent-bug-fixes-history).

---

## 🔴 High priority

### 1. `coroutineScope` not recreated on reattach

**Platform:** Android  
**File:** `android/src/main/java/com/whosup/packages/richinput/RichChatInputView.kt` (scope created at construction, cancelled in `onDetachedFromWindow`)

**Symptom:** After the view detaches from the window and reattaches (RN Navigation screen pop+push, `react-native-screens` stash/restore, `FlatList`/`ScrollView` recycling), image paste silently fails. No error, no `onRichContent` event. Same applies to error dispatches originating from IO-side callbacks and to first-mount cache cleanup.

**Root cause:** The single `CoroutineScope(Dispatchers.Main + SupervisorJob())` is cancelled in `onDetachedFromWindow` and never recreated in `onAttachedToWindow`. Subsequent `coroutineScope.launch { ... }` calls hit a cancelled scope and silently no-op.

**Workaround:** Force-remount the component on screen focus (rotate the React `key`).

**Fix direction:**
- Override `onAttachedToWindow` to recreate the scope (or)
- Replace the field with a lazy / nullable scope that is constructed on first use after attach.

---

### 2. `prepareForRecycle` bypasses the keyboard daemon

**Platform:** iOS  
**File:** `ios/RichChatInputView.mm`, `prepareForRecycle` method

**Symptom:** When Fabric recycles a `RichChatInputView` for a different `RichChatInput` mount, any in-flight IME composition from the previous mount can be flushed into the recycled view, reproducing the "안녕하세요 → 요 잔류" Korean IME bug — but in a context the host app can't easily reproduce (it happens during view recycling, not during their own `clear()` calls).

**Root cause:**

```objc
- (void)prepareForRecycle {
    [super prepareForRecycle];
    _textView.text = @"";   // ← bypasses RTI; the very pattern clear()'s
                            //    comment warns against
    [_textView updatePlaceholderVisibility];
    _state = nullptr;
}
```

**Workaround:** None.

**Fix direction:** Call the same code path as `clear()` (replace marked range first via `replaceRange:withText:`, then full-range replace, then `updatePlaceholderVisibility`). Skip the deferred-flush detection — at recycle time there's no host handler to receive `onError`.

---

### 3. `readAndDispatchContent` blocks the UI thread

**Platform:** Android  
**File:** `android/src/main/java/com/whosup/packages/richinput/RichChatInputView.kt`, `readAndDispatchContent` method

**Symptom:** Large GIF / image pastes (5–20 MB) freeze the UI for hundreds of milliseconds. On low-end devices, can trip the ANR watchdog.

**Root cause:** Intentional design — the `content://` URI's temporary read permission expires when the `OnReceiveContentListener` callback returns, so we read the bytes synchronously on the main thread to stay inside the permission window. The 20 MB size check happens **after** the read, so a 19 MB paste blocks for the full read duration.

**Workaround:** Set a stricter `acceptedMimeTypes` if your use case allows narrowing types.

**Fix direction:**
- Use `ContentResolver.openAssetFileDescriptor` to peek size before reading; reject early if > 20 MB.
- Or: take a persistable URI grant via `ContentResolver.takePersistableUriPermission` (requires `Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION` from the keyboard, which most keyboards don't set), then read in background.
- Or: stream the read in chunks to a temp file (still on main thread, but bounded; allows aborting on size overrun).

---

### 4. No synchronous way to read the current text from JS

**Platform:** Both  
**Files:** `src/RichChatInputViewNativeComponent.ts` (no `getText` command), `src/RichChatInput.tsx` (ref only exposes `clear`)

**Symptom:** Host apps that read `text` from state on send-button tap can race the latest `onChangeText` event. If the user types fast and taps send, the last keystroke's text-change event may not have crossed the JS bridge yet — the send handler sees stale text. The dropped character(s) then arrive after the send fires and either get lost (if `clear()` runs right after) or prepended to the next message.

**Root cause:** All text-change events go through Fabric's async event dispatcher. There is no controlled `text`/`value` prop and no imperative `getText()` for synchronous read.

**Workaround:** Tap a "send" button that takes the input text from state, then `setTimeout(..., 50)` before actually firing the send (gives async events time to flush). Not great UX.

**Fix direction:** Add a Codegen `Commands.getText` that returns the EditText/UITextView's `text` synchronously through the Fabric command channel (requires a callback-based or TurboModule-based pattern since Fabric commands are nominally `void`). Expose via the ref:

```ts
inputRef.current?.getText()   // string, read directly from native
```

---

## 🟡 Medium priority

### 5. `onInputSizeChange` fires synchronously inside `afterTextChanged` on Android

**Platform:** Android  
**File:** `android/src/main/java/com/whosup/packages/richinput/RichChatInputView.kt`, `setupTextWatcher`

**Symptom:** During multi-line transitions (Enter at end of long line that re-wraps), `layout.height` can be one frame stale because layout is mid-recompute. Rare and self-correcting (the next edit triggers a corrected dispatch).

**Root cause:** Synchronous dispatch in `afterTextChanged`:

```kotlin
override fun afterTextChanged(s: Editable?) {
    dispatchChangeTextEvent(s?.toString() ?: "")
    dispatchInputSizeChangeEvent()   // ← uses possibly-stale layout
}
```

iOS solved the equivalent problem by deferring with `dispatch_async(main, ...)`; Android still dispatches synchronously.

**Workaround:** None needed; the staleness is one frame.

**Fix direction:** Wrap the dispatch in `post { ... }` to defer to the next message-loop tick, matching the iOS pattern. The `onSizeChanged` path already uses `post`.

---

### 6. `pasteConfiguration` may be cached by UIKit

**Platform:** iOS  
**File:** `ios/RichChatInputView.mm`, `pasteConfiguration` override

**Symptom:** When `acceptedMimeTypes` changes after mount, UIKit may continue to use the previously-cached `pasteConfiguration` value, so the keyboard's "paste" suggestion doesn't reflect the new types until the view is refocused.

**Root cause:** `-pasteConfiguration` returns a fresh `UIPasteConfiguration` each call, but UIKit may not re-invoke the getter after the first invocation.

**Workaround:** None observable so far in practice; the Android equivalent is handled by `restartInput` which has no clean iOS analog.

**Fix direction:** In the `acceptedMimeTypes` updater, explicitly set `_textView.pasteConfiguration = nil` then re-assign it, OR rebuild the `_textView.pasteConfiguration` directly instead of relying on the getter.

---

### 7. `NSTextStorage` observer not explicitly removed

**Platform:** iOS  
**File:** `ios/RichChatInputView.mm`, `RichChatInputInternalTextView` (no `dealloc`)

**Symptom:** None in practice. On iOS 9+, selector-based notification observers are auto-removed at dealloc. iOS 15.1 is the deployment target, so the auto-removal applies.

**Root cause:** Best-practice violation rather than a real leak.

**Fix direction:** Add an explicit `dealloc` that removes the observer. Defensive only.

---

## 🟢 Low priority / cleanup

### 8. `package.json` lists a `cpp/` directory that doesn't exist

**File:** `package.json`, `files` array (`"cpp"`)

**Symptom:** Harmless — npm publish includes the array as a filter; missing entries are no-ops.

**Fix direction:** Remove the entry.

---

### 9. Event registration is declared in two places

**File:** `android/src/main/java/com/whosup/packages/richinput/RichChatInputViewManager.kt`, `getExportedCustomBubblingEventTypeConstants`

**Symptom:** None — defensive redundancy with the Codegen-generated `RichChatInputViewManagerDelegate`. But adding a new event requires updating both places, and the manual map can drift from the Codegen spec.

**Fix direction:** Decide on a single source. Currently the manual map is kept defensively because it covers some hot-reload edge cases the delegate path doesn't. Document the dual ownership in the file's header comment OR remove the manual override and rely on Codegen.

---

### 10. Native code has no unit tests

**Platforms:** Both  
**File:** `src/__tests__/index.test.tsx` (only covers the JS wrapper)

**Symptom:** Regressions in the native side are caught only by example-app smoke tests.

**Fix direction:**
- Android: Robolectric tests for `ViewManager` prop wiring and for `RichChatInputView` lifecycle (paste flow, `clearTextSafely`, MIME advertise).
- iOS: XCTest unit tests for the `RichChatInputInternalTextView` private class (paste decoding, `clear` with marked text, height measurement).

---

### 11. `onInputSizeChange` lacks a Jest test

**File:** `src/__tests__/index.test.tsx`

**Symptom:** None functional; coverage gap. `onChangeText`, `onRichContent`, and `onError` have wrapper-unwrap tests; `onInputSizeChange` does not.

**Fix direction:** Add a test mirroring the existing pattern.

---

## How to file a new known issue

When a bug is reported but not yet fixed, add an entry here. Format:

```markdown
### N. <one-line summary>

**Platform:** Android / iOS / Both
**File:** path/to/file:line if known

**Symptom:** What the user observes.
**Root cause:** Why it happens (link to file/line).
**Workaround:** What the host app can do today (or "None").
**Fix direction:** Planned approach.
```

When the issue is fixed, move the relevant context into the appropriate `platform/<os>.md` "Recent bug fixes (history)" section and delete the entry here.
