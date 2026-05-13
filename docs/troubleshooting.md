# Troubleshooting

## Build & Codegen

### `No such file or directory` — `Props.h` missing (iOS)

```
error: .../build/generated/ios/react/renderer/components/RichChatInputViewSpec/Props.h: No such file or directory
        (in target 'ReactCodegen' from project 'Pods')
```

Codegen output is stale or partial. Regenerate:

```sh
cd <your-app>/ios
rm -rf build/generated
pod install
```

If that still fails, nuke Pods completely:

```sh
cd <your-app>/ios
rm -rf build/generated Pods Podfile.lock
pod install
```

### `yarn prepare` fails with `ERR_REQUIRE_ESM`

```
Error [ERR_REQUIRE_ESM]: require() of ES Module .../arktype/out/index.js not supported.
```

`react-native-builder-bob@0.40.18` tries to `require()` the ESM-only `arktype@2.x`. **`yarn prepare` is intentionally a no-op in `package.json`** for this reason — local development does not need a `lib/` build. The Metro resolver config in [`installation.md`](./installation.md#metro-resolver-setup) makes the host app pick `src/index.tsx` directly.

When npm publishing actually requires a `lib/` build:
- Wait for `react-native-builder-bob` to ship a fix, OR
- Switch to Node.js 22 (where the `require(ESM)` interop works), OR
- Build manually: `npx bob build` after temporarily patching `prepare`.

## Auto-grow

### Multiline height stuck at minimum

```tsx
// ❌ no height growth
<RichChatInput multiline style={styles.input} />

// ✅
const [h, setH] = useState(44);
<RichChatInput
  multiline
  style={[styles.input, { height: h }]}
  onInputSizeChange={({ height }) => setH(Math.max(44, Math.min(250, height + 24)))}
/>
```

`onInputSizeChange` is **required** for auto-grow. See [`features/auto-grow-height.md`](./features/auto-grow-height.md).

## iOS

### `[TextInputUI] Result accumulator timeout: 0.250000, exceeded` (simulator only)

The keyboard daemon (RTI) is waiting for the input field to ack a key event, and the synchronous Fabric work in the original `_textStorageDidChange:` handler ate too much of the 250 ms budget. This is fixed by deferring measurement with `dispatch_async(main_queue, ...)`. If you still see this:

- Confirm your build includes the fix in `ios/RichChatInputView.mm` (look for `_textStorageDidChange:` calling `dispatch_async`).
- Real devices don't show this warning even when present; it's a simulator-only RTI quirk.

### Crash inside `react-native-keyboard-controller` forwarding

```
EXC_BAD_ACCESS (SIGSEGV) inside KCTextInputCompositeDelegate.forwardingTarget
```

You added `<UITextViewDelegate>` adoption or `self.delegate = self` somewhere in the iOS code. Don't — the library deliberately avoids both because `keyboard-controller`'s composite delegate creates an infinite-recursion path when the text view forwards selectors back through itself. Use the patterns documented in [`platform/ios.md`](./platform/ios.md#react-native-keyboard-controller-compatibility) instead.

### Korean / Chinese / Japanese IME leaves a stuck character after send

Symptom: user types "안녕하세요", taps send, the message goes out, but "요" reappears in the input a moment later (or the next message starts with leftover composing characters).

Should not happen on current versions — `clear()` properly resets the marked text range and notifies RTI. If it still occurs:

1. Check that your "send" handler calls `inputRef.current?.clear()` (and not `setText('')` or similar JS-only resets — those don't notify the keyboard daemon).
2. Check your `onError` log for `IME_STALE_TEXT_FLUSHED` events. If you're getting them, that's the bug recurring; please file an issue with the `message` field's character sample.

## Android

### Image paste silently does nothing

Several possible causes:

1. **`acceptedMimeTypes` doesn't include the image type the keyboard is delivering.** Default is `['image/*']`. If you narrowed it to `['image/gif']`, PNG/WebP paste will be rejected by the listener filter.
2. **Component was navigated away and back.** Known issue — see [`known-issues.md`](./known-issues.md#1-coroutinescope-not-recreated-on-reattach). Workaround: force-remount the component (change `key`) on screen focus, or wait for the fix.
3. **Pasted file > 20 MB.** Check `onError` for `RICH_CONTENT_TOO_LARGE`.
4. **`FileNotFoundException` from expired URI permission.** Check `onError` for `RICH_CONTENT_FILE_NOT_FOUND` or `RICH_CONTENT_PERMISSION_DENIED`.

Wire `onError` (see [`features/error-handling.md`](./features/error-handling.md)) — every paste failure path emits a specific code.

### Send sometimes drops characters or merges messages

Symptom: user types a message and taps send. Sometimes the message arrives truncated, or the next message starts with leftover from the previous one. Once it starts happening, it persists until the app is killed.

This is the Android IME composing-span bug. Should not happen on current versions — `clear()` calls `BaseInputConnection.removeComposingSpans()` and `InputMethodManager.restartInput()`. If it still occurs:

1. Verify your send path uses `inputRef.current?.clear()` (not just `setText('')`).
2. Check `onError` log for `IME_STALE_TEXT_FLUSHED` — if you see it, the bug recurred and the message field will have a sample of the leftover characters.
3. Possible host-side cause if the library `clear()` is working: send button isn't debounced and fires twice on fast taps. See [`known-issues.md`](./known-issues.md#4-no-synchronous-way-to-read-the-current-text-from-js).

### Keyboard's GIF button is grayed out

The keyboard reads `EditorInfo.contentMimeTypes` to decide which rich-content controls to enable. If your `acceptedMimeTypes` doesn't include the relevant MIME (e.g. `image/gif`), GBoard / Samsung will gray out the GIF picker.

Default `['image/*']` covers all image types. Don't narrow this prop unless you really want to reject everything else.

## Codegen / TypeScript

### `'react-native/Libraries/Types/CodegenTypes' has no exported member ...`

This module's types aren't bundled with `react-native`. `src/shims.d.ts` declares them by re-exporting from `CodegenTypesNamespace`. If your TypeScript setup is dropping `shims.d.ts`, ensure it's included in `tsconfig.json`'s `include`/`files` (it lives under `src/` so the default glob picks it up).

### A new event/prop doesn't show up after editing the spec

1. iOS: re-run `pod install` from the host app's `ios/` directory.
2. Android: clean rebuild (`./gradlew clean` then run again).
3. Verify the spec passes Codegen validation (run a full `pod install` and watch the Codegen logs — type errors show as `TSNumberKeyword` etc.).

## Other

### `useImperativeHandle`'s `clear()` does nothing

`Commands.clear` requires a valid native view handle. If `inputRef.current` is null when you call it, the native command is silently dropped. Common cause: calling `clear()` during an unmount transition. Defer to the next tick if needed (`setTimeout(..., 0)` or use the `onMomentumScrollEnd`-style callback the screen library exposes).
