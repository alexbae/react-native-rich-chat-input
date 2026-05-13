# Host App Setup

End-to-end checklist for adding `react-native-rich-chat-input` to a consumer app (e.g. `whosup`). Covers both the **npm release** path and the **git URL** path (used while iterating on `develop` before publishing).

> If you only want a one-line summary: `yarn add` it → add the Metro resolver block → `pod install` → run. The rest of this doc explains the **why** for each step and the gotchas you hit if you skip one.

---

## 1. Verify host requirements

| Item | Required value |
|---|---|
| React Native | **0.76+** with **New Architecture enabled** |
| iOS deployment target | **15.1+** |
| Android `minSdkVersion` | **24** |
| Android `compileSdkVersion` | **36** |
| Kotlin | **2.0.21** (or a version your RN release pins) |

Check New Arch is on:

- **iOS** — `ios/Podfile` has either `:fabric_enabled => true` or `:new_arch_enabled => true` (RN 0.76 uses the latter by default).
- **Android** — `android/gradle.properties` contains `newArchEnabled=true`.

The library ships only the Fabric (New Arch) renderer. Paper is not supported.

---

## 2. Install

Pick one of the two methods.

### 2a. Install from npm (once a version is published)

```sh
yarn add react-native-rich-chat-input
# or
npm install react-native-rich-chat-input
```

Nothing else is special — the published tarball contains the compiled `lib/` output, so the default `main`/`exports` entries resolve cleanly. You can skip step **3 (Metro resolver)** in this mode.

### 2b. Install from this repo's `develop` branch (current path)

```sh
yarn add "github:alexbae/react-native-rich-chat-input#develop"
```

This is the path used while the package is unpublished. Two things differ from an npm install and **both matter**:

1. **No `lib/` is produced.** `package.json`'s `prepare` script is intentionally a no-op (see [`troubleshooting.md`](./troubleshooting.md#yarn-prepare-fails-with-err_require_esm)). Without `lib/`, the default resolver target (`./lib/module/index.js`) does not exist, so Metro must be told to use the TypeScript source instead — see step **3** below.
2. **Yarn pins the git ref to a SHA at install time.** New commits pushed to `develop` are **not** picked up by a plain `yarn install` afterwards. To refresh:
   ```sh
   yarn cache clean
   yarn install --force
   ```
   Or re-add the package:
   ```sh
   yarn remove react-native-rich-chat-input
   yarn add "github:alexbae/react-native-rich-chat-input#develop"
   ```

---

## 3. Metro resolver (required for the git URL path)

Without this, the first `import { RichChatInput } from 'react-native-rich-chat-input'` fails with `Cannot find module` because Metro tries to resolve `lib/module/index.js`, which the git checkout doesn't contain.

Add to the host's `metro.config.js`:

```js
const { getDefaultConfig } = require('@react-native/metro-config');

const config = getDefaultConfig(__dirname);

// react-native-rich-chat-input does not ship a built lib/ in its git tree.
// Tell Metro to prefer the package.json "source" export condition so it
// resolves src/index.tsx directly.
config.resolver.unstable_conditionsByPlatform = {
  ios: ['source', 'react-native'],
  android: ['source', 'react-native'],
};

module.exports = config;
```

If your `metro.config.js` already customizes `resolver`, merge the field instead of overwriting.

This config is harmless once the package is installed from npm (the published version still has `lib/`, but the `source` condition simply takes precedence and resolves the same code from `src/`).

---

## 4. iOS

```sh
cd ios && pod install && cd ..
```

`pod install` runs Codegen, which generates the C++ headers (`Props.h`, the EventEmitter, the ComponentDescriptor) under `ios/build/generated/`. Re-run `pod install` whenever any of these change in the library:

- A prop or event is added/removed in `src/RichChatInputViewNativeComponent.ts`.
- A `.mm` or `.h` file is added or renamed under the library's `ios/`.
- `RichChatInput.podspec` is edited.

If you upgrade the library and the first iOS build fails with `Props.h: No such file or directory`, the Codegen cache is stale:

```sh
cd ios
rm -rf build/generated Pods Podfile.lock
pod install
```

---

## 5. Android

No manual step required. Gradle runs Codegen on every build. If you change `acceptedMimeTypes` / events / props in the library and Android doesn't pick them up, force a clean build:

```sh
cd android && ./gradlew clean && cd ..
```

---

## 6. TypeScript (optional, only for the git URL path)

The git checkout has no `lib/typescript/` either, so `tsc` against the published `types` entry will fail with `Cannot find module ... lib/typescript/src/index.d.ts`. Two fixes — pick one:

### Option 1 — `tsconfig.json` path mapping

```json
{
  "compilerOptions": {
    "baseUrl": ".",
    "paths": {
      "react-native-rich-chat-input": [
        "./node_modules/react-native-rich-chat-input/src/index"
      ]
    }
  }
}
```

### Option 2 — TS custom conditions (TS 5.0+)

```json
{
  "compilerOptions": {
    "customConditions": ["source", "react-native"]
  }
}
```

This makes TypeScript respect the same `source` export condition Metro uses, so it resolves `src/index.tsx` directly. Matches what's already in the library's own `tsconfig.json`.

---

## 7. Run on a real device

Use **physical devices** for verification. The simulator misses several IME-specific issues (Korean composing-span behavior, GBoard rich content) and surfaces some false positives (RTI accumulator warnings on iOS Simulator only).

```sh
# iOS
npx react-native run-ios --device "<Device Name>"

# Android (USB-connected device with developer mode on)
npx react-native run-android --device
```

---

## 8. Smoke test the install

Paste this into a screen and confirm it builds and renders:

```tsx
import { useRef } from 'react';
import { RichChatInput } from 'react-native-rich-chat-input';
import type {
  RichChatInputRef,
  RichChatInputError,
} from 'react-native-rich-chat-input';

export function SmokeTest() {
  const ref = useRef<RichChatInputRef>(null);

  return (
    <RichChatInput
      ref={ref}
      placeholder="type and send..."
      multiline
      acceptedMimeTypes={['image/*']}
      onChangeText={(t) => console.log('text:', t)}
      onRichContent={({ uri, mimeType }) =>
        console.log('attachment:', mimeType, uri)
      }
      onError={(e: RichChatInputError) =>
        console.warn(`[RichChatInput:${e.code}] ${e.message}`, e.nativeMessage)
      }
    />
  );
}
```

If this renders and `onChangeText` fires on every keystroke, the native binding is wired correctly. From there see [`usage.md`](./usage.md) for the production patterns (ref-based `getText()` / `clear()`, auto-grow, Sentry forwarding).

---

## 9. Common host-side mistakes

| Symptom | Likely cause | Fix |
|---|---|---|
| `Cannot find module 'react-native-rich-chat-input'` at first import | Metro resolver block missing (git URL install) | Section **3** |
| iOS build fails with `Props.h: No such file or directory` | Stale Codegen cache | Section **4** (rm + `pod install`) |
| `tsc` errors on `lib/typescript/...` not found | Git URL install + no TS workaround | Section **6** |
| New commits on `develop` aren't being picked up | Yarn pinned the git ref to a SHA | `yarn cache clean && yarn install --force` |
| Runtime crash: `IllegalStateException: New Architecture must be enabled` (Android) | `newArchEnabled=false` | Section **1** |
| iOS link error mentioning Fabric symbols | Paper-only host app | Section **1** — host must be on New Arch |
| GIF / image paste silently drops | `acceptedMimeTypes` too narrow, or coroutine scope torn down on screen navigation | [`troubleshooting.md` → Android](./troubleshooting.md#android) |
| Korean IME leaves trailing characters after send | Host called `setText('')` instead of `inputRef.current?.clear()` | Use the imperative `clear()` |

For anything not listed here, see [`troubleshooting.md`](./troubleshooting.md) and [`known-issues.md`](./known-issues.md).

---

## 10. When to switch back from git URL to npm

Once a version of this package is published to npm, the host app should switch:

```sh
yarn remove react-native-rich-chat-input
yarn add react-native-rich-chat-input
```

After that:
- The Metro resolver block in section **3** is still safe to keep — it just becomes a no-op (the published tarball has both `lib/` and `src/`; the `source` condition picks `src/` either way).
- The TypeScript workaround in section **6** is no longer needed; you can drop it.
- The git-ref-pinning problem (section **2b** point 2) goes away — normal semver applies.
