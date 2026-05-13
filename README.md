# react-native-rich-chat-input

A React Native chat input that receives **rich content** (GIFs, stickers, images) from the native keyboard and the system clipboard, on top of the New Architecture (Fabric).

- **Android**: `AppCompatEditText` + `ViewCompat.setOnReceiveContentListener`
- **iOS**: `UITextView` + `paste:` override + `UIPasteboard`
- **React Native**: 0.76+ (New Architecture required)

## Install

```sh
npm install react-native-rich-chat-input
# iOS: cd ios && pod install
```

See [docs/installation.md](./docs/installation.md) for full requirements and local development setup.

## Quick example

```tsx
import { RichChatInput } from 'react-native-rich-chat-input';

<RichChatInput
  placeholder="Type a message..."
  acceptedMimeTypes={['image/*']}
  onChangeText={(text) => console.log(text)}
  onRichContent={({ uri, mimeType }) => console.log('attachment:', uri, mimeType)}
/>;
```

For multiline auto-grow, `clear()` after send, and forwarding errors to Sentry, see [docs/usage.md](./docs/usage.md).

## Documentation

### Getting started

| Document | What it covers |
|---|---|
| [installation.md](./docs/installation.md) | Requirements, install steps, local development build |
| [usage.md](./docs/usage.md) | Quick start patterns: minimal, rich content, auto-grow, ref + `clear()`, Sentry wiring |
| [api-reference.md](./docs/api-reference.md) | Full Props / Imperative API / Event payload / Error code reference |
| [architecture.md](./docs/architecture.md) | Design rationale, Fabric + Codegen flow, threading model |

### Features

| Document | What it covers |
|---|---|
| [features/rich-content.md](./docs/features/rich-content.md) | GIF / sticker / image paste — MIME advertising, URI lifecycle, 20 MB limit, 7-day cleanup |
| [features/auto-grow-height.md](./docs/features/auto-grow-height.md) | Multiline auto-expand via `onInputSizeChange`, height/padding accounting, width-change re-dispatch |
| [features/error-handling.md](./docs/features/error-handling.md) | `onError` event, error code table, Sentry forwarding pattern, severity guidance |

### Platform-specific

| Document | What it covers |
|---|---|
| [platform/android.md](./docs/platform/android.md) | Kotlin implementation: `OnReceiveContentListener`, IME composing-span clear, resize re-dispatch, coroutine threading |
| [platform/ios.md](./docs/platform/ios.md) | Objective-C++ implementation: `paste:`, RTI preservation, marked text clear, height timing, hit-test, `keyboard-controller` compatibility |

### Operations

| Document | What it covers |
|---|---|
| [troubleshooting.md](./docs/troubleshooting.md) | Build errors, Codegen issues, IME / paste edge cases, common host-app mistakes |
| [known-issues.md](./docs/known-issues.md) | Bugs found in code review that aren't fixed yet, with severity and fix direction |

## Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md) for the development workflow, commit message convention, and PR process.

## License

MIT
