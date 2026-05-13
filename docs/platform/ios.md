# iOS implementation notes

Source files:
- `ios/RichChatInputView.h` — header.
- `ios/RichChatInputView.mm` — Objective-C++ implementation.
- `ios/RichChatInputMeasuringShadowNode.h` — custom Fabric `ShadowNode` for native Yoga measurement.

The component is structured as:

```
RichChatInputView : RCTViewComponentView         // Fabric wrapper
  └─ contentView: RichChatInputInternalTextView  // UITextView subclass (private class in the .mm)
```

Splitting the internal `UITextView` into a private class lets us override UIKit text methods (`paste:`, `insertText:`, `pasteConfiguration`) and add a placeholder overlay without polluting the Fabric wrapper, and — critically — sidesteps a `react-native-keyboard-controller` recursion (see [below](#react-native-keyboard-controller-compatibility)).

Deployment target: iOS 15.1 (`min_ios_version_supported` from RN's podspec helper).

## Rich content (paste, keyboard image)

### `paste:` override

We override `-[UITextView paste:]` to intercept rich content before the default text-paste behavior runs:

```objc
- (void)paste:(id)sender {
#if TARGET_OS_SIMULATOR
    [super paste:sender];   // simulator has no real keyboard; just text
    return;
#endif
    UIPasteboard *pb = UIPasteboard.generalPasteboard;
    // Check in priority order:
    //   GIF (com.compuserve.gif)  →  WebP  →  PNG  →  generic image  →  super
    // ...
}
```

We check pasteboard types in priority order — GIF first because `UIImage` decoding of an animated GIF would lose all frames after frame 0; we read the raw bytes via `[pb dataForPasteboardType:]` to preserve animation.

For non-image content, we fall through to `[super paste:sender]` so plain text paste still works.

### `pasteConfiguration`

```objc
- (UIPasteConfiguration *)pasteConfiguration {
    return [[UIPasteConfiguration alloc] initWithAcceptableTypeIdentifiers:utis];
}
```

UTIs are computed from `acceptedMimeTypes`. This is what enables third-party GIF-keyboard apps' "paste into field" suggestion. The simulator's pasteboard layer doesn't expose UTIs, so on simulator we return `nil` and skip the whole intercept path.

### Saving to cache

```objc
- (void)_saveData:(NSData *)data mimeType:(NSString *)mt extension:(NSString *)ext {
    if (data.length > kRichContentMaxFileSize) { /* dispatchError TOO_LARGE */ return; }
    dispatch_async(global_queue, ^{
        NSURL *fileURL = [NSURL fileURLWithPath:[NSTemporaryDirectory() stringByAppendingPathComponent:fileName]];
        BOOL ok = [data writeToURL:fileURL options:NSDataWritingAtomic error:&err];
        dispatch_async(main, ^{
            if (!ok) /* dispatchError CACHE_WRITE_FAILED */;
            else    /* dispatchRichContent */;
        });
    });
}
```

Memory optimization: we read bytes via `[pb dataForPasteboardType:]` rather than `pb.image → UIImagePNGRepresentation()`. The image-decoding path roughly doubles peak memory (decoded `CGImage` plus re-encoded PNG); the raw-bytes path holds only the original encoded data.

## React-native-keyboard-controller compatibility

`react-native-keyboard-controller` swaps the `UITextView.delegate` for its `KCTextInputCompositeDelegate`, which forwards unhandled selectors back to the original delegate. If we set `self.delegate = self` and adopt `<UITextViewDelegate>`:

```
KCTextInputCompositeDelegate.forwardingTarget(for:)
  → activeDelegate (UITextView itself)
    → UITextView's own forwarding → delegate (KCTextInputCompositeDelegate)
      → forwardingTarget(for:) again → ... infinite recursion → SIGSEGV
```

Workarounds:

| Need | What we do instead of `UITextViewDelegate` |
|---|---|
| `shouldChangeTextInRange:` (enforce `maxLength`, prevent newline in single-line) | Override `-insertText:` directly |
| `textViewDidChange:` (fire `onChangeText`) | Observe `NSTextStorageDidProcessEditingNotification` — operates at the `NSTextStorage` level, fires regardless of who owns the delegate |
| `textViewDidEndEditing:` (cleanup) | `prepareForRecycle` from Fabric |

This is why you'll see no `<UITextViewDelegate>` adoption anywhere in the file.

## RTI (Remote Text Input) preservation

iOS routes soft-keyboard input through an XPC service that delivers `insertText:` to the focused text view. If the text view's frame changes while it's first responder, the RTI session can drop — subsequent keystrokes are silently lost.

### `updateLayoutMetrics` frame guard

When React re-renders, `updateLayoutMetrics` is called and `_textView.frame` gets reset to match the new layout. We allow this only when JS is explicitly driving height (multiline auto-grow); for any other frame change (width, x, y from sibling re-renders), we revert:

```objc
- (void)updateLayoutMetrics:(LayoutMetrics const &)layoutMetrics
              oldLayoutMetrics:(LayoutMetrics const &)oldLayoutMetrics {
    CGRect frameBefore = _textView.frame;
    BOOL isFirstResponder = _textView.isFirstResponder;
    BOOL layoutHeightChanged = fabs(newH - oldH) > 0.5;
    [super updateLayoutMetrics:layoutMetrics oldLayoutMetrics:oldLayoutMetrics];
    if (!CGRectEqualToRect(frameBefore, _textView.frame)
        && isFirstResponder && !layoutHeightChanged) {
        _textView.frame = frameBefore;  // revert
    }
}
```

`layoutHeightChanged` is the signal that JS intentionally changed the height (via `style.height` from the `onInputSizeChange` → `setHeight` flow). All other frame changes during typing are treated as collateral damage from sibling re-renders and reverted.

## Height measurement timing

### The bug we fixed

Naïve implementation:

```objc
- (void)_textStorageDidChange:(NSNotification *)note {
    CGSize contentSize = [self sizeThatFits:CGSizeMake(self.bounds.size.width, CGFLOAT_MAX)];
    [self.eventDelegate dispatchInputSizeChange:contentSize];
}
```

This fires from inside `NSTextStorage`'s editing cycle. Calling `sizeThatFits:` here triggers `ensureLayoutForTextContainer:`, but `NSLayoutManager` is **mid-cycle** and returns the **previous** layout's height. As a result, pressing Return doesn't grow the input, and the simulator logs:

```
[TextInputUI] Result accumulator timeout: 0.250000, exceeded
```

…because `_state->updateState()` synchronously enqueues a Fabric main-thread job that delays the RTI ack past 250 ms.

### The fix

Defer the measurement and dispatch to the next main runloop:

```objc
- (void)_textStorageDidChange:(NSNotification *)note {
    dispatch_async(dispatch_get_main_queue(), ^{
        CGSize contentSize = [self sizeThatFits:CGSizeMake(self.bounds.size.width, CGFLOAT_MAX)];
        [self.eventDelegate dispatchInputSizeChange:contentSize];
    });
}
```

By the time the deferred block runs, the `NSTextStorage` edit cycle is fully torn down and `NSLayoutManager` returns the correct, post-edit height. The RTI ack is no longer blocked.

## `clear()` and the Korean / CJK IME bug

CJK IMEs hold the in-progress composing syllable in a **marked text range** whose backing buffer lives in the keyboard daemon (RTI process), not in the text view's storage. Naïve clearing:

```objc
_textView.text = @"";
```

bypasses the daemon entirely. A few frames later, RTI flushes the last composing syllable back via `insertText:`, producing the user-visible "안녕하세요 → 요 잔류" bug.

The correct clear path:

```objc
UITextRange *markedRange = _textView.markedTextRange;
if (markedRange) {
    [_textView replaceRange:markedRange withText:@""];   // step 1: tell RTI to drop composition
    if (_textView.markedTextRange) {                     // defensive fallback
        [self dispatchError:@"IME_MARKED_TEXT_PERSISTED" ...];
        [_textView unmarkText];
    }
}
UITextRange *allRange = [_textView textRangeFromPosition:beginning toPosition:end];
[_textView replaceRange:allRange withText:@""];          // step 2: clear remaining text

if (hadMarkedText) {
    dispatch_after(100 ms, ^{
        if (_textView.text.length > 0) {
            [self dispatchError:@"IME_STALE_TEXT_FLUSHED" ...];
        }
    });
}
```

Step 1 uses `-replaceRange:withText:` (the `UITextInput` protocol method) rather than `-unmarkText`. The latter just commits the marked characters into regular storage; RTI keeps its composing-buffer reference and re-injects later. `-replaceRange:withText:@""` actually deletes the marked region and synchronously notifies RTI to cancel composition.

The 100 ms deferred check fires `IME_STALE_TEXT_FLUSHED` if the bug still manages to happen so the host app sees occurrences in production.

## Hit-test for padding

When `style.padding` is set, the JS layout puts that padding inside `RichChatInputView` (the Fabric wrapper), so tapping the padded area doesn't land on the inner `_textView`. We override `hitTest:withEvent:` to route any tap inside the wrapper's bounds to `_textView`:

```objc
- (UIView *)hitTest:(CGPoint)point withEvent:(UIEvent *)event {
    if ([self pointInside:point withEvent:event]) return _textView;
    return [super hitTest:point withEvent:event];
}
```

## Placeholder

`UITextView` doesn't ship a placeholder. We overlay a `UILabel` on top and toggle its `hidden` based on text length. The label gets positioned from `textContainerInset` so it lines up with the actual text origin even when padding changes.

## Custom Fabric `ShadowNode`

`RichChatInputMeasuringShadowNode` is a custom `ConcreteViewShadowNode` that stores `contentHeight` in Fabric state. The `_textStorageDidChange:` handler writes the new height via `_state->updateState(...)`, which triggers Yoga to re-lay out without a JS roundtrip.

This is **supplementary** to `onInputSizeChange`, not a replacement — see [`features/auto-grow-height.md`](../features/auto-grow-height.md#why-its-not-automatic). The shadow-node path improves measurement determinism for screen rotation / dynamic type but the JS `height` state remains the authoritative size.

## Recent bug fixes (history)

- **Korean IME stuck character on clear** — see "[`clear()` and the Korean / CJK IME bug](#clear-and-the-korean-cjk-ime-bug)" above.
- **`onInputSizeChange` timing** — see "[Height measurement timing](#height-measurement-timing)" above.
- **`react-native-keyboard-controller` infinite recursion** — see "[React-native-keyboard-controller compatibility](#react-native-keyboard-controller-compatibility)" above.
- **RTI session drop on re-render** — see "[`updateLayoutMetrics` frame guard](#updatelayoutmetrics-frame-guard)" above.
- **Padding-area tap doesn't focus** — see "[Hit-test for padding](#hit-test-for-padding)" above.

## Simulator caveats

- `UIPasteboard` is severely limited; rich-content paste won't work. The `paste:` override falls through to `[super paste:]` on simulator.
- The `[TextInputUI] Result accumulator timeout` warning only ever appears on simulator (Mac keyboard → RTI proxy is slow). The deferred-dispatch fix above resolves it; if you still see it, you're on a build before that fix.
