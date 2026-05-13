# Rich content (GIFs, stickers, images)

The library intercepts non-text content from the IME and from system paste, copies the bytes to local cache, and hands the host app a `file://` URI via the `onRichContent` event.

## Trigger points

| Source | Android | iOS |
|---|---|---|
| Keyboard "image / GIF / sticker" button (GBoard, Samsung, Naver, etc.) | `OnReceiveContentListener` | `pasteConfiguration` + `paste:` |
| System clipboard paste | `OnReceiveContentListener` (covers both API paths since Android 12) | `paste:` reading `UIPasteboard` |
| Drag & drop | Not implemented | Not implemented |

## `acceptedMimeTypes`

```tsx
<RichChatInput acceptedMimeTypes={['image/*']} />        // default
<RichChatInput acceptedMimeTypes={['image/gif']} />      // GIF only
<RichChatInput acceptedMimeTypes={['image/*', 'video/*']} />
```

The list is **advertised to the keyboard**, not just used as a filter:

- Android: passed to `EditorInfoCompat.setContentMimeTypes(...)` so the keyboard knows which preview types to enable (GIF carousel, sticker drawer, etc.).
- iOS: converted to UTI strings (`com.compuserve.gif`, `public.image`, etc.) and applied to `UIPasteConfiguration`.

Glob patterns (`image/*`) are normalised; duplicates removed. Whitespace-only / empty entries fall back to the default `['image/*']`.

Changes to `acceptedMimeTypes` after mount take effect immediately:
- Android: triggers `InputMethodManager.restartInput(this)` so the keyboard reflects the new types.
- iOS: updates `_textView.pasteConfiguration` directly.

## `onRichContent` payload

```ts
{
  uri: string;       // file:// path in your app's cache
  mimeType: string;  // detected from the source (image/gif, image/webp, ...)
}
```

The URI is **always a `file://` path your app already has read access to**. You do not need to request runtime permissions to read it. Pass it directly to `<Image source={{ uri }} />`, an upload library, etc.

## Permission window (Android)

When the keyboard delivers content via `OnReceiveContentListener`, the URI it provides is a `content://` URI **scoped to the keyboard's process** with a temporary read grant. That grant expires when the receive callback returns. If you tried to read the `content://` URI later (e.g. on send), you would get a `SecurityException` or `FileNotFoundException`.

To stay safe, the library:

1. Reads the full byte stream **synchronously on the main thread** inside the listener (before the permission window closes).
2. Writes those bytes to `context.cacheDir` on `Dispatchers.IO`.
3. Dispatches `onRichContent` with the resulting `file://` URI, which has no permission expiry.

This is why large pastes can briefly block the UI thread — see [`known-issues.md`](../known-issues.md#3-android-readanddispatchcontent-blocks-the-ui-thread).

## Size limit

Both platforms cap pasted content at **20 MB**. Anything larger is rejected and fires `onError` with code `RICH_CONTENT_TOO_LARGE`. No `onRichContent` event is emitted.

## Cache lifecycle

- **Android**: `context.cacheDir/rich_content_<uuid>.<ext>`
- **iOS**: `NSTemporaryDirectory()/rich_content_<uuid>.<ext>`

The first time a `RichChatInput` mounts after process start, the library scans the cache directory and deletes any `rich_content_*` file older than **7 days** on a background thread. There is no per-file LRU tracking — modification time is the only signal.

The host app is free to copy the file elsewhere (e.g. into a permanent attachments directory) before the 7-day window closes. The library does not delete files it knows the host is still using; cleanup only runs at mount, on stale-by-mtime files.

## Filename extensions

The native code maps known MIME types to extensions for the cached file:

| MIME | Extension |
|---|---|
| `image/gif` | `.gif` |
| `image/webp` | `.webp` |
| `image/png` | `.png` |
| `image/jpeg` | `.jpg` |
| `image/svg+xml` | `.svg` |
| `video/mp4` | `.mp4` |
| `video/webm` | `.webm` |
| `video/3gpp` | `.3gp` |
| `video/x-matroska` | `.mkv` |
| anything else | `.bin` |

The extension is cosmetic — your `<Image>` / decoder should rely on the `mimeType` field, not the URI suffix.

## Error paths

If the paste fails, `onRichContent` is **not** called and `onError` fires with one of:

| Code | When |
|---|---|
| `RICH_CONTENT_MIME_UNKNOWN` | Android: `ContentResolver.getType` returned null |
| `RICH_CONTENT_OPEN_FAILED` | Android: `openInputStream` returned null |
| `RICH_CONTENT_FILE_NOT_FOUND` | Android: URI's underlying file is gone |
| `RICH_CONTENT_PERMISSION_DENIED` | Android: read grant expired before the read finished |
| `RICH_CONTENT_IO_ERROR` | Android: any other `IOException` during read |
| `RICH_CONTENT_TOO_LARGE` | Either: > 20 MB |
| `RICH_CONTENT_CACHE_WRITE_FAILED` | Either: cache directory write failed (disk full, etc.) |

See [`features/error-handling.md`](./error-handling.md) for forwarding patterns.

## Why not insert images inline?

See [`architecture.md`](../architecture.md#design-rationale).
