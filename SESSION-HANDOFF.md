# Session Handoff — react-native-rich-chat-input

> Written by a prior Claude Code session on **2026-11-27** to let a fresh
> session continue the in-flight iOS Korean IME bug investigation.
>
> **Read this file first** when picking up this branch. It's temporary —
> delete it when the investigation is closed.

---

## TL;DR — status at handoff

- **Branch**: `claude/fix-mobile-input-bugs-1bYkq` (HEAD `8fea5c5`)
- **Waiting on**: user to install the debug-logging build on a real iOS 26 device, reproduce the Korean IME residue bug, and send back the `[RCI]`-filtered logs
- **Not yet understood**: why the iOS IME residue persists despite the `replaceRange(markedTextRange, "")` + full-range clear + input restart treatment
- **Everything else on this branch is landed and verified correct**: Android IME composing-span clear, EditorInfo MIME advertise, paste streaming + AFD pre-check, coroutineScope reattach, `getText()` imperative API, onError event, docs restructure, host-setup guide, review-feedback fixes
- **Host-side race hypothesis was ruled out**: user already moved `clear()` before the send await; still reproduces

---

## Project at a glance

| | |
|---|---|
| Library | `react-native-rich-chat-input` (Fabric View, RN 0.76+, iOS 15.1+, Android minSdk 24) |
| Host app | **whosup** (external repo, not in this sandbox) — the user installs via `github:alexbae/react-native-rich-chat-input#develop` |
| Android package | `com.whosup.packages.richinput` |
| User | alexbae (alexbae84@gmail.com) |
| Default branch | `main` |
| Staging branch | `develop` (user workflow: test on develop → promote to main) |

### Branch map (as of handoff)

```
main                                  0619011
  └─ develop                          fa04209  (origin), local was advanced to db704ae
      └─ claude/fix-mobile-input-bugs-1bYkq
          d342b74 fix: resize/IME content/Korean IME
          2649834 feat: onError + Sentry pattern
          3351f46 fix(android): IME composing-span clear
          28040d5 docs: README → docs/ tree
          b8eedd5 fix: 4 high-priority review items
          fa04209 fix: review feedback
          db704ae docs: host-setup.md
          8fea5c5 chore(debug): [RCI] tracing  ← HEAD, TEMPORARY
```

PR #11 was opened against `main` and manually closed by the user (they prefer the develop-first workflow; the PR wasn't needed because the content was already fast-forwarded to `develop` locally — and the user said they'd push `develop` from their own machine, which hit a `fetch first` rejection and was resolved via rebase).

---

## Current blocker — iOS Korean IME residue

### Symptoms (user-reported, physical iOS 26.4.2 device)

1. Type `안녕하세요?` → tap Send → immediately type `그럼요`
2. Input shows `안그럼요` (ghost `안` from the previous message)
3. Delete repeatedly reveals one hidden char at a time, in a non-obvious order:
   `안` → `?` → `요` → `세`
4. Also observed: fast type after Send keeps the full `안녕하세요?다음` in the input

### What's been ruled out

- **Host-side race** (`setMessageText("") → useEffect → clear()`): user already restructured to call `clear()` before the send `await`. Still reproduces.
- **`IME_STALE_TEXT_FLUSHED` event** does NOT fire. So the 100ms watchdog sees empty text. That means either:
  - The text re-appears only when the user types the next key (not auto-flushed by RTI)
  - Or it appears later than 100 ms (why `8fea5c5` added watchdogs at +16/50/100/200/500/1000 ms)

### Open hypotheses (prioritised)

1. **iOS 26 UITextView behavior change** — iOS 26 has significant UITextInput internals changes this year. `attributedText = @""` or `replaceRange:` may have slightly different semantics around attachment attributes / predictions / selection reset.
2. **RTI shadow length mismatch** — RTI keeps a cached document length. After clear, if the next `insertText:` from RTI uses the stale length as its insertion offset, UITextView may "restore" positions on demand.
3. **Inline predictions / attachment attribute** — `UITextView.inlinePredictionType` defaults to `.default` on iOS 17.2+. A prediction attribute may survive `replaceRange:` and reveal on delete.
4. **Controlled/uncontrolled race** — whosup might set a `value`-like prop or re-set text from JS state after clear.
5. **`setMarkedText:` arriving after clear** — Korean composition events may queue in RTI and apply post-clear.

### Debug logging landed in `8fea5c5`

All logs prefix `[RCI` for grep:

- `insertText:` entry/exit — logs incoming text, current `self.text`, `markedTextRange`, `selectedTextRange`
- `setMarkedText:selectedRange:` + `unmarkText` overrides — logs composition state transitions
- `_textStorageDidChange:` — logs `editedRange`, `changeInLength`, full state
- `dispatchChangeText:` — logs text emitted to JS bridge
- `_clearTextReportingTelemetry:` — logs entry, after-step-1, after-step-2, exit
- **Multi-tick watchdog**: text/marked/selected sampled at +16, 50, 100, 200, 500, 1000 ms after clear
- JS wrapper (`src/RichChatInput.tsx`): logs every `clear()`, `getText()`, and `onChangeText` receipt with timestamps

Toggle via `kRCIDebugLoggingEnabled` (iOS) — currently `YES`.

### What to do once logs arrive

1. Walk the log sequence chronologically. Specifically check:
   - Does `_clearTextReportingTelemetry:` actually run? (confirms JS→native bridge)
   - After clear EXIT, is `self.text` empty? (confirms clear worked synchronously)
   - Do any further `insertText:` / `setMarkedText:` events arrive BETWEEN clear exit and user's next perceived keystroke? (RTI replay smoking gun)
   - At each watchdog tick: is text still empty? If text re-appears at (say) +500 ms, that's beyond the 100 ms telemetry window and would explain the silent failure
2. Match a hypothesis above → write a proper fix
3. **Revert the debug logging commit** (`git revert 8fea5c5`) in the same PR as the fix
4. Push, test again

---

## Everything else — status

### Verified correct (keep)

- **Android `clearTextSafely`** — `BaseInputConnection.removeComposingSpans(editable)` + `editable.clear()` + `InputMethodManager.restartInput(this)` → fixes the "send corrupts next message, persists until app restart" bug. User hasn't reported continued Android breakage.
- **Android `EditorInfoCompat.setContentMimeTypes`** in `onCreateInputConnection` + `restartInput` on `acceptedMimeTypes` change → GBoard GIF button works.
- **Android paste** — `openAssetFileDescriptor` size pre-check + 64 KB chunked read with mid-stream abort on 20 MB → no more main-thread freeze on oversized paste.
- **Android `onSizeChanged` re-dispatch** → auto-grow adapts to rotation/fold/multi-window.
- **Android `coroutineScope` `@Volatile` + recreate on `onAttachedToWindow`** → image paste survives screen navigation.
- **`onError` event with 10 codes** (see `src/RichChatInput.tsx` `RichChatInputErrorCode`). Host app wires Sentry via the pattern in `docs/features/error-handling.md`.
- **`getText()` imperative** on `RichChatInputRef` — JS-side `latestTextRef` mirror, bypasses React setState batching.

### Uncertain / reconsider based on log findings

- **iOS `_clearTextReportingTelemetry:`** — the primary change (replaceRange(markedRange,"") + replaceRange(allRange,"")) was supposed to fix the Korean residue. Doesn't. May need to be replaced entirely or augmented with: `selectedTextRange` reset, `inputDelegate` notifications, inline-prediction toggle, or a completely different approach depending on what logs reveal.
- **iOS `prepareForRecycle` uses safe clear** — defensive but unverified. Leave in place for now (no downside), but if the iOS clear path is rewritten, this call site should be reviewed.

### Documentation (all current)

- `README.md` — thin index linking to `docs/*`
- `docs/installation.md` — requirements, install, local dev
- `docs/usage.md` — quick-start patterns + full example
- `docs/api-reference.md` — props, imperative API, event payloads, error-code table
- `docs/architecture.md` — design rationale, Fabric+Codegen flow
- `docs/features/rich-content.md` — paste/MIME/cache/size
- `docs/features/auto-grow-height.md` — multiline auto-expand
- `docs/features/error-handling.md` — onError + Sentry
- `docs/platform/android.md` — IME composing, resize, threading
- `docs/platform/ios.md` — RTI, marked text, paste, timing
- `docs/troubleshooting.md` — build/Codegen/IME/paste
- `docs/known-issues.md` — 8 tracked follow-ups
- `docs/host-setup.md` — git URL install, Metro resolver, iOS Codegen, TS paths

### Known issues (tracked, not blockers)

See `docs/known-issues.md` for full detail. Headline items:

1. Android `onInputSizeChange` fires synchronously in `afterTextChanged` (iOS already deferred)
2. iOS `pasteConfiguration` may be cached by UIKit after prop change
3. iOS internal text view lacks explicit `dealloc`
4. `package.json` `files` lists non-existent `cpp/`
5. Event registration duplicated (Codegen delegate + manual bubbling map)
6. No native unit tests (Robolectric / XCTest)
7. `onInputSizeChange` lacks a Jest test
8. **In-flight paste coroutine may drop final dispatch during detach** — rare but documented with three fix directions
9. `getText()` can't reach past the latest dispatched `onChangeText` (TurboModule follow-up)

---

## Host-app (whosup) context

- Install method: `yarn add "github:alexbae/react-native-rich-chat-input#develop"` (needs `yarn cache clean && yarn install --force` to refresh git ref)
- Metro resolver MUST be configured:
  ```js
  config.resolver.unstable_conditionsByPlatform = {
    ios: ['source', 'react-native'],
    android: ['source', 'react-native'],
  };
  ```
- `cd ios && pod install` after each library change (Codegen regen)
- TS paths workaround needed if `tsc` is run (see `docs/host-setup.md` §6)

### User's send path (confirmed)

1. `screens/Chat/index.tsx:474, :485` — `handleSend` calls `sendMessage(options)` fire-and-forget
2. `screens/Chat/hooks/useMessageSender.ts:112` — `setMessageText("")` BEFORE send promise (good)
3. `screens/Chat/components/ChatInput.tsx:99-108` — **useEffect** watching `messageText === ""` calls `richInputRef.current?.clear()`

User was advised to replace the useEffect-based clear with imperative `clear()` inside `handleSend` (eliminates ~30-50 ms render delay). User did this but iOS Korean residue still reproduces → confirms it's NOT the host race.

### User's platform

- iOS test device: **iOS 26.4.2** (iOS 26 is a new major release with significant UITextInput internals; keep this in mind when hypothesising)
- Keyboard: Apple Korean (not specified which layout)
- iOS Android device: not reported working/broken on Android side

---

## Repo navigation shortcuts

| Role | File |
|---|---|
| JS wrapper (forwardRef, clear, getText, onError unwrap) | `src/RichChatInput.tsx` |
| Codegen spec (props, events, Commands) | `src/RichChatInputViewNativeComponent.ts` |
| iOS internal UITextView subclass | `ios/RichChatInputView.mm` → `RichChatInputInternalTextView` |
| iOS Fabric wrapper | `ios/RichChatInputView.mm` → `RichChatInputView` |
| iOS header | `ios/RichChatInputView.h` |
| iOS shadow node (Yoga measure) | `ios/RichChatInputMeasuringShadowNode.h` |
| Android view (AppCompatEditText subclass) | `android/src/main/java/com/whosup/packages/richinput/RichChatInputView.kt` |
| Android ViewManager | `android/src/main/java/com/whosup/packages/richinput/RichChatInputViewManager.kt` |
| Android package | `android/src/main/java/com/whosup/packages/richinput/RichChatInputPackage.kt` |
| Tests (JS wrapper only) | `src/__tests__/index.test.tsx` |
| Example app | `example/src/App.tsx` |

---

## Operational notes for the next session

- **Push constraints**: this sandbox's git proxy allows pushes to `claude/fix-mobile-input-bugs-1bYkq` but rejects direct pushes to `develop` or `main` (HTTP 403). The user pushes `develop` from their own machine after fetching our work.
- **GitHub MCP**: sometimes transiently disconnects — `ToolSearch` for `select:mcp__github__*` loads schemas on demand.
- **CI checks**: lint/typecheck/test/build-android/build-library are quick (~1-4 min each); build-ios is ~3-5 min. All passed as of HEAD `8fea5c5`.
- **Precommit hooks** (lefthook): runs `lint` and `commitlint` — write conventional commits (`fix:`, `feat:`, `docs:`, `chore:`).
- **Attribution for new commits** (per Claude Code policy):
  ```
  Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_016Pd449K57s4QVxaN36UFdn
  ```

---

## Quick-resume checklist for a new session

1. `cat SESSION-HANDOFF.md` (this file)
2. `git log --oneline origin/main..HEAD -20`
3. Read `docs/known-issues.md` for background
4. Read the iOS clear path: `ios/RichChatInputView.mm:510-640` (approximate line range, grep for `_clearTextReportingTelemetry`)
5. Ask the user:
   - Do you have the `[RCI]` logs from a reproduction attempt?
   - If not, has the debug-logging build (`8fea5c5`) been installed on the iOS device?
6. If logs are in hand → analyse against the 5 hypotheses above → write fix + revert `8fea5c5`
7. If logs aren't yet collected → walk the user through `docs/host-setup.md` install + the reproduction steps at the end of the "whosup side host logging" instruction (last turn before this handoff was written)

---

## Last human-authored message before this handoff

> "근데 지금 아이폰이랑 안드로이드에서 문제가 없거든 현재 letup에서 사용하는 react-native-rich-chat-input이 어떤 버전이야?"

Translation: "By the way, there are no issues on iPhone and Android right now. What version of react-native-rich-chat-input is currently used in letup?"

Interpretation: Either (a) the user temporarily reverted their install to an older version and things are fine, (b) they're testing a different app called "letup" (possibly a typo of whosup), or (c) the current branch's fixes actually did resolve things for them without `IME_STALE_TEXT_FLUSHED` ever firing and the logging is moot. **The previous Claude session asked the user to run several `cat package.json`/`yarn.lock` commands to determine the installed SHA — await their output to disambiguate.**

---

*End of handoff. Delete this file once the iOS IME investigation is closed.*
