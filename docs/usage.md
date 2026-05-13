# Usage

## Minimal example

```tsx
import { RichChatInput } from 'react-native-rich-chat-input';

export function ChatComposer() {
  return (
    <RichChatInput
      placeholder="Type a message..."
      onChangeText={(text) => console.log(text)}
    />
  );
}
```

## Receiving rich content (GIFs / images from the keyboard)

```tsx
import { useState } from 'react';
import { Image, View } from 'react-native';
import { RichChatInput } from 'react-native-rich-chat-input';
import type { RichContentResult } from 'react-native-rich-chat-input';

export function ChatComposer() {
  const [preview, setPreview] = useState<RichContentResult | null>(null);

  return (
    <View>
      {preview && (
        <Image
          source={{ uri: preview.uri }}
          style={{ width: 200, height: 200 }}
        />
      )}
      <RichChatInput
        acceptedMimeTypes={['image/*']}
        onRichContent={setPreview}
      />
    </View>
  );
}
```

The `uri` is a local `file://` path under the app cache directory — see [`features/rich-content.md`](./features/rich-content.md) for the URI lifecycle, supported MIME types, and the 20 MB / 7-day cleanup limits.

## Auto-growing multiline input

```tsx
import { useState } from 'react';
import { StyleSheet } from 'react-native';
import { RichChatInput } from 'react-native-rich-chat-input';

const PADDING_V = 12;

export function ChatComposer() {
  const [height, setHeight] = useState(44);

  return (
    <RichChatInput
      multiline
      style={[styles.input, { height }]}
      onInputSizeChange={({ height: contentHeight }) => {
        setHeight(
          Math.max(44, Math.min(250, contentHeight + PADDING_V * 2))
        );
      }}
    />
  );
}

const styles = StyleSheet.create({
  input: {
    minHeight: 44,
    maxHeight: 250,
    paddingVertical: PADDING_V,
    paddingHorizontal: 16,
    fontSize: 16,
  },
});
```

**Without `onInputSizeChange`, multiline auto-expand does not work.** The height stays pinned at the initial value. See [`features/auto-grow-height.md`](./features/auto-grow-height.md) for the rationale.

## Imperative API: clearing the input

```tsx
import { useRef } from 'react';
import { Button, View } from 'react-native';
import { RichChatInput } from 'react-native-rich-chat-input';
import type { RichChatInputRef } from 'react-native-rich-chat-input';

export function ChatComposer() {
  const inputRef = useRef<RichChatInputRef>(null);

  const handleSend = () => {
    // ... send the message ...
    inputRef.current?.clear();
  };

  return (
    <View>
      <RichChatInput ref={inputRef} />
      <Button title="Send" onPress={handleSend} />
    </View>
  );
}
```

`clear()` does more than wipe the text — it also resets the IME composing state to prevent stuck-character bugs on both Android and iOS. See [`platform/android.md`](./platform/android.md#clear-resets-ime-composing-state) and [`platform/ios.md`](./platform/ios.md#clear-and-the-korean-cjk-ime-bug) for details.

## Observability: forwarding errors to Sentry

```tsx
import { RichChatInput } from 'react-native-rich-chat-input';
import type { RichChatInputError } from 'react-native-rich-chat-input';
import * as Sentry from '@sentry/react-native';

function reportRichChatInputError(err: RichChatInputError) {
  Sentry.captureException(
    new Error(`[RichChatInput:${err.code}] ${err.message}`),
    {
      tags: {
        rich_chat_input_code: err.code,
        rich_chat_input_native_class: err.nativeClass || 'none',
      },
      extra: {
        nativeMessage: err.nativeMessage,
        nativeStack: err.nativeStack,
      },
      fingerprint: ['rich-chat-input', err.code],
    }
  );
}

<RichChatInput onError={reportRichChatInputError} />;
```

The library does **not** take a Sentry SDK dependency. See [`features/error-handling.md`](./features/error-handling.md) for the full error-code table and groupings.

## Putting it all together

```tsx
import { useRef, useState } from 'react';
import { Image, StyleSheet, View, Button } from 'react-native';
import { RichChatInput } from 'react-native-rich-chat-input';
import type {
  RichChatInputRef,
  RichContentResult,
} from 'react-native-rich-chat-input';

export function ChatComposer({ onSubmit }: { onSubmit: (msg: { text: string; attachment?: RichContentResult }) => void }) {
  const inputRef = useRef<RichChatInputRef>(null);
  const [text, setText] = useState('');
  const [attachment, setAttachment] = useState<RichContentResult | null>(null);
  const [height, setHeight] = useState(44);

  const handleSend = () => {
    if (!text && !attachment) return;
    onSubmit({ text, attachment: attachment ?? undefined });
    setText('');
    setAttachment(null);
    inputRef.current?.clear();
  };

  return (
    <View style={styles.wrap}>
      {attachment && (
        <Image source={{ uri: attachment.uri }} style={styles.preview} />
      )}
      <RichChatInput
        ref={inputRef}
        multiline
        maxLength={2000}
        acceptedMimeTypes={['image/*']}
        style={[styles.input, { height }]}
        onChangeText={setText}
        onRichContent={setAttachment}
        onInputSizeChange={({ height: h }) =>
          setHeight(Math.max(44, Math.min(250, h + 24)))
        }
        onError={reportRichChatInputError}
      />
      <Button title="Send" onPress={handleSend} />
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { padding: 8 },
  preview: { width: 120, height: 120, marginBottom: 8 },
  input: {
    minHeight: 44,
    maxHeight: 250,
    paddingVertical: 12,
    paddingHorizontal: 16,
    fontSize: 16,
  },
});
```

For the complete prop reference, see [`api-reference.md`](./api-reference.md).
