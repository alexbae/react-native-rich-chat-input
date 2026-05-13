# Known issues

This document captures issues found during code review that are **not yet fixed**. Each entry lists severity, symptom, the line of code that's responsible, current workaround if any, and the planned fix direction.

For issues that **are** fixed, see the "Recent bug fixes (history)" sections in [`platform/android.md`](./platform/android.md#recent-bug-fixes-history) and [`platform/ios.md`](./platform/ios.md#recent-bug-fixes-history).

> **Note**: The four 🔴 high-priority issues from the original code review (coroutine scope reattach, iOS `prepareForRecycle` anti-pattern, paste main-thread block, missing synchronous text read) are now fixed. Their context lives in the platform docs' history sections and in [`api-reference.md`](./api-reference.md#imperative-api) (`getText()`).

---

## 🟡 Medium priority

### 1. `onInputSizeChange` fires synchronously inside `afterTextChanged` on Android

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

### 2. `pasteConfiguration` may be cached by UIKit

**Platform:** iOS  
**File:** `ios/RichChatInputView.mm`, `pasteConfiguration` override

**Symptom:** When `acceptedMimeTypes` changes after mount, UIKit may continue to use the previously-cached `pasteConfiguration` value, so the keyboard's "paste" suggestion doesn't reflect the new types until the view is refocused.

**Root cause:** `-pasteConfiguration` returns a fresh `UIPasteConfiguration` each call, but UIKit may not re-invoke the getter after the first invocation.

**Workaround:** None observable so far in practice; the Android equivalent is handled by `restartInput` which has no clean iOS analog.

**Fix direction:** In the `acceptedMimeTypes` updater, explicitly set `_textView.pasteConfiguration = nil` then re-assign it, OR rebuild the `_textView.pasteConfiguration` directly instead of relying on the getter.

---

### 3. `NSTextStorage` observer not explicitly removed

**Platform:** iOS  
**File:** `ios/RichChatInputView.mm`, `RichChatInputInternalTextView` (no `dealloc`)

**Symptom:** None in practice. On iOS 9+, selector-based notification observers are auto-removed at dealloc. iOS 15.1 is the deployment target, so the auto-removal applies.

**Root cause:** Best-practice violation rather than a real leak.

**Fix direction:** Add an explicit `dealloc` that removes the observer. Defensive only.

---

## 🟢 Low priority / cleanup

### 4. `package.json` lists a `cpp/` directory that doesn't exist

**File:** `package.json`, `files` array (`"cpp"`)

**Symptom:** Harmless — npm publish includes the array as a filter; missing entries are no-ops.

**Fix direction:** Remove the entry.

---

### 5. Event registration is declared in two places

**File:** `android/src/main/java/com/whosup/packages/richinput/RichChatInputViewManager.kt`, `getExportedCustomBubblingEventTypeConstants`

**Symptom:** None — defensive redundancy with the Codegen-generated `RichChatInputViewManagerDelegate`. But adding a new event requires updating both places, and the manual map can drift from the Codegen spec.

**Fix direction:** Decide on a single source. Currently the manual map is kept defensively because it covers some hot-reload edge cases the delegate path doesn't. Document the dual ownership in the file's header comment OR remove the manual override and rely on Codegen.

---

### 6. Native code has no unit tests

**Platforms:** Both  
**File:** `src/__tests__/index.test.tsx` (only covers the JS wrapper)

**Symptom:** Regressions in the native side are caught only by example-app smoke tests.

**Fix direction:**
- Android: Robolectric tests for `ViewManager` prop wiring and for `RichChatInputView` lifecycle (paste flow, `clearTextSafely`, MIME advertise, coroutine scope reattach).
- iOS: XCTest unit tests for the `RichChatInputInternalTextView` private class (paste decoding, `_clearTextReportingTelemetry:`, height measurement, `prepareForRecycle` clear-path reuse).

---

### 7. `onInputSizeChange` lacks a Jest test

**File:** `src/__tests__/index.test.tsx`

**Symptom:** None functional; coverage gap. `onChangeText`, `onRichContent`, `onError`, `clear`, and `getText` are now covered; `onInputSizeChange` is the remaining gap.

**Fix direction:** Add a test mirroring the existing pattern.

---

### 8. `getText()` cannot see past the latest dispatched `onChangeText`

**Platforms:** Both  
**File:** `src/RichChatInput.tsx`, `latestTextRef` + `useImperativeHandle`

**Symptom:** `getText()` returns a JS-side mirror updated by every `onChangeText` event. It is fresher than React state (no setState batching delay), but it cannot reach further than the most recently delivered event. A truly synchronous read of the EditText / UITextView's live text (bypassing the event pipeline entirely) is not available.

**Root cause:** Fabric `Commands` are nominally `void` and cannot return values synchronously. A true native-side read would require a TurboModule (e.g. `RichChatInputModule.getText(viewTag)`) which is a non-trivial addition.

**Workaround:** The existing `getText()` covers the typical "user types then taps send" race because typing events reach JS before the tap event. For pathological scenarios, the host can `await new Promise(r => requestAnimationFrame(r))` before reading.

**Fix direction:** Add a TurboModule that resolves the view by `reactTag` and returns its native `text` synchronously. Async-promise version is also acceptable.

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
