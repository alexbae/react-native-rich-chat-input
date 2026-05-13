# Installation

## Requirements

| | Version |
|---|---|
| React Native | **0.76+** (New Architecture must be enabled) |
| Android `minSdkVersion` | **24** (Android 7.0) |
| Android `compileSdkVersion` | 36 |
| iOS deployment target | **15.1+** (uses RN's `min_ios_version_supported`) |
| Kotlin | 2.0.21 (matches `android/build.gradle`) |

The library is published as a Fabric View component. The legacy Paper renderer is **not** supported.

## Install

```sh
npm install react-native-rich-chat-input
# or
yarn add react-native-rich-chat-input
```

### iOS

```sh
cd ios && pod install
```

`pod install` triggers Codegen to regenerate the C++ event emitters and `Props.h` headers for the `RichChatInputViewSpec`.

### Android

No extra step. Gradle runs Codegen automatically on the next build.

## Local development against this repo

When this package is consumed via a `file:` link (e.g. another app's `package.json` points at a local checkout):

### iOS

```sh
cd <your-app>/ios
pod install
cd <your-app>
npm run ios
```

Re-run `pod install` whenever you:
- Add or remove a prop/event in `src/RichChatInputViewNativeComponent.ts` (Codegen needs to regenerate the C++ EventEmitter)
- Add or rename a `.mm` / `.h` file under `ios/`
- Edit `RichChatInput.podspec`

### Android

```sh
cd <your-app>
npm run android
```

Gradle handles Codegen on every build, so no separate step is required.

### Metro resolver setup

Add this to the host app's `metro.config.js` so Metro picks the library's TypeScript source (`src/index.tsx`) instead of a stale `lib/` output:

```js
config.resolver.unstable_conditionsByPlatform = {
  ios: ['source', 'react-native'],
  android: ['source', 'react-native'],
};
```

With this setup, `yarn prepare` (the `lib/` build) is only needed when **publishing to npm**, not during local development.

> **Installing this package into another app (e.g. via a git URL on the `develop` branch)?** See [host-setup.md](./host-setup.md) for the complete consumer-side checklist — Metro resolver, Codegen, TypeScript path mapping, refreshing the git ref, and common host-side mistakes.

## `yarn prepare` is intentionally a no-op

The `prepare` script in `package.json` is an empty string. This avoids a known incompatibility between `react-native-builder-bob@0.40.18` and ESM-only `arktype@2.x`. See [troubleshooting.md](./troubleshooting.md#yarn-prepare-fails-with-err_require_esm) for the workaround when an actual `lib/` build is needed.
