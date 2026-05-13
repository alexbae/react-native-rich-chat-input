# Auto-grow height (multiline)

When `multiline` is enabled and the user adds new lines (or pastes a long passage), the input needs to expand vertically. **The library does not do this automatically** — the host app must wire `onInputSizeChange` to a `height` state, then pass that state back through `style`.

## Why it's not automatic

iOS does ship a custom Fabric `ShadowNode` that stores `contentHeight` and feeds it into Yoga (see [`architecture.md`](../architecture.md#native-height-measurement-ios-only)), but the shadow-node path is **supplementary**, not authoritative:

- It only runs on iOS.
- It runs after the JS `style.height` is resolved, so JS-driven constraints (`minHeight`, `maxHeight`, animations) need a JS-side height state anyway.
- Yoga measurement reads stale state across some IME-driven edit cycles unless the host has its own height value to clamp against.

Treating `onInputSizeChange` as the canonical source — and letting JS drive height — produces consistent behavior across platforms.

## The pattern

```tsx
import { useState } from 'react';
import { StyleSheet } from 'react-native';
import { RichChatInput } from 'react-native-rich-chat-input';

const PADDING_V = 12;
const MIN_HEIGHT = 44;
const MAX_HEIGHT = 250;

function Composer() {
  const [height, setHeight] = useState(MIN_HEIGHT);

  return (
    <RichChatInput
      multiline
      style={[styles.input, { height }]}
      onInputSizeChange={({ height: contentHeight }) => {
        const total = contentHeight + PADDING_V * 2;
        setHeight(Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, total)));
      }}
    />
  );
}

const styles = StyleSheet.create({
  input: {
    paddingVertical: PADDING_V,
    paddingHorizontal: 16,
    fontSize: 16,
  },
});
```

### What's in `height`

`onInputSizeChange` emits the **text content height only** — vertical padding is NOT included:

```ts
{ width: number; height: number; }   // padding-exclusive
```

Add `paddingVertical * 2` (or `paddingTop + paddingBottom` if asymmetric) yourself.

### What's in `width`

`width` is the view's own width, padding included. It's emitted on every text change so you can use it for layout-dependent calculations (e.g. limiting attached image width to text-column width). For pure auto-grow you can ignore it.

## Common mistakes

### ❌ Relying on `flex` or implicit height

```tsx
<RichChatInput multiline style={styles.input} />  // height pinned at minHeight
```

Without `style={{ height }}` driven by `onInputSizeChange`, the input never grows past its initial measured size.

### ❌ Forgetting padding

```tsx
onInputSizeChange={({ height }) => setHeight(height)}  // input clips!
```

The text content fits in `height`, but your `paddingVertical` is on top of that — the visible bottom of the last line will be clipped. Always add the padding back in.

### ❌ Letting it grow unbounded

```tsx
onInputSizeChange={({ height }) => setHeight(height + 24)}
```

A paste of 50 lines will produce a 600-pixel-tall input. Always clamp with a sensible `MAX_HEIGHT`.

## Width changes (device rotation / fold / split-screen)

When the input's width changes (rotation, fold/unfold on foldables, multi-window resizing), the wrapped text layout changes height, but `afterTextChanged`/`NSTextStorageDidProcessEditingNotification` do not fire — the text didn't change, only the wrap width did.

The library handles this by re-dispatching `onInputSizeChange` on width change:

- Android: `onSizeChanged(w, h, oldw, oldh)` posts a deferred dispatch when `w != oldw`.
- iOS: `updateLayoutMetrics` re-dispatches when the layout width changes without a content-edit cause.

You do not need to do anything special — the same `onInputSizeChange` handler will fire with the new height, and your `setHeight` clamp will resize the input.

## Edge case: input height while first responder

iOS keeps the `UITextView`'s frame pinned while the user is typing (to avoid breaking the RTI session — see [`platform/ios.md`](../platform/ios.md#updatelayoutmetrics-frame-guard)). The frame is allowed to change in height when JS explicitly drives the layout height through `style.height`. So as long as you're using the `onInputSizeChange → setHeight → style.height` pattern, growth works while typing.

## Related events

- [`api-reference.md`](../api-reference.md#contentsizeresult) — `ContentSizeResult` type.
- [`platform/android.md`](../platform/android.md#input-size-dispatch) — Android implementation details.
- [`platform/ios.md`](../platform/ios.md#height-measurement-timing) — iOS implementation details and the `NSTextStorage` timing fix.
