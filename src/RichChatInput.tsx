import React, { forwardRef, useImperativeHandle, useRef } from 'react';
import type { NativeSyntheticEvent, StyleProp, ViewStyle } from 'react-native';
import NativeRichChatInputView, {
  Commands,
} from './RichChatInputViewNativeComponent';

export interface RichContentResult {
  uri: string;
  mimeType: string;
}

export interface ContentSizeResult {
  width: number;
  height: number;
}

/**
 * Machine-readable codes for issues the native side can detect. Forward this
 * event to your error tracker (e.g. Sentry.captureException) so production
 * occurrences of the known sporadic bugs are observable:
 *
 *   - RICH_CONTENT_*           : Android/iOS image-paste path failures.
 *   - INPUT_SIZE_DISPATCH_FAILED: Android post-resize remeasure could not
 *                                  obtain a text layout (related to the
 *                                  resizable-screen height-stale bug).
 *   - IME_MARKED_TEXT_PERSISTED: iOS clear() fallback hit — the marked text
 *                                  range survived the primary replace path.
 *   - IME_STALE_TEXT_FLUSHED   : iOS Korean/CJK IME re-injected the last
 *                                  composing character after clear()
 *                                  (the "안녕하세요 → 요 잔류" bug).
 */
export type RichChatInputErrorCode =
  | 'RICH_CONTENT_MIME_UNKNOWN'
  | 'RICH_CONTENT_OPEN_FAILED'
  | 'RICH_CONTENT_FILE_NOT_FOUND'
  | 'RICH_CONTENT_PERMISSION_DENIED'
  | 'RICH_CONTENT_IO_ERROR'
  | 'RICH_CONTENT_TOO_LARGE'
  | 'RICH_CONTENT_CACHE_WRITE_FAILED'
  | 'INPUT_SIZE_DISPATCH_FAILED'
  | 'IME_MARKED_TEXT_PERSISTED'
  | 'IME_STALE_TEXT_FLUSHED'
  | (string & {});

export interface RichChatInputError {
  code: RichChatInputErrorCode;
  message: string;
  /** Underlying exception class name (e.g. "java.io.FileNotFoundException"), or "" when not applicable. */
  nativeClass: string;
  /** Localized exception message from the native side, or "" when not applicable. */
  nativeMessage: string;
  /** Native stack trace string, or "" when not applicable. Useful as Sentry `extra`. */
  nativeStack: string;
}

export interface RichChatInputProps {
  placeholder?: string;
  placeholderTextColor?: string;
  editable?: boolean;
  multiline?: boolean;
  maxLength?: number;
  fontSize?: number;
  acceptedMimeTypes?: string[];
  style?: StyleProp<ViewStyle>;
  onChangeText?: (text: string) => void;
  onRichContent?: (content: RichContentResult) => void;
  onInputSizeChange?: (size: ContentSizeResult) => void;
  /**
   * Fires when the native side detects a recoverable error (paste failure,
   * IME stuck character, resize remeasure failure, etc.). Forward to your
   * crash/error reporting service for observability.
   */
  onError?: (error: RichChatInputError) => void;
}

export interface RichChatInputRef {
  /**
   * Clears the input text and resets the IME composing state on both
   * platforms. Always call this when programmatically emptying the input
   * (e.g. after sending) — `setText('')` in JS does not notify the
   * keyboard daemon and can leave stale composition state.
   */
  clear: () => void;
  /**
   * Returns the most recent text the input has reported via `onChangeText`,
   * read from a JS ref. This bypasses React state batching, so a send
   * handler can read the freshest text on tap without racing the
   * `setState(text)` re-render.
   *
   * Note: this is the *JS-side* latest value (updated by every native
   * `onChangeText` event). It cannot reach further forward than the most
   * recently dispatched text-change event. For the vast majority of
   * "user types and taps send" flows, that's enough.
   */
  getText: () => string;
}

export const RichChatInput = forwardRef<RichChatInputRef, RichChatInputProps>(
  (props, ref) => {
    const nativeRef =
      useRef<React.ElementRef<typeof NativeRichChatInputView>>(null);
    // Mirrors the latest text reported by the native onChangeText. Read
    // by getText() and reset to '' on clear() so callers see the post-clear
    // value synchronously rather than waiting for the empty-text event to
    // round-trip from native.
    const latestTextRef = useRef('');

    useImperativeHandle(ref, () => ({
      clear: () => {
        // [RCI debug-logging]
        if (__DEV__) {
          // eslint-disable-next-line no-console
          console.log(
            `[RCI:js t=${Date.now() % 100000}] clear() called latestText="${latestTextRef.current}" hasNativeRef=${!!nativeRef.current}`
          );
        }
        latestTextRef.current = '';
        if (nativeRef.current) {
          Commands.clear(nativeRef.current);
        }
      },
      getText: () => {
        // [RCI debug-logging]
        if (__DEV__) {
          // eslint-disable-next-line no-console
          console.log(
            `[RCI:js t=${Date.now() % 100000}] getText() -> "${latestTextRef.current}"`
          );
        }
        return latestTextRef.current;
      },
    }));

    const { onChangeText, onRichContent, onInputSizeChange, onError, ...rest } =
      props;

    return (
      <NativeRichChatInputView
        ref={nativeRef}
        {...rest}
        // Always wrap onChangeText so latestTextRef stays in sync, even when
        // the host does not subscribe to the prop. The native dispatch fires
        // unconditionally either way, so there's no perf cost.
        onChangeText={(e: NativeSyntheticEvent<{ text: string }>) => {
          // [RCI debug-logging]
          if (__DEV__) {
            // eslint-disable-next-line no-console
            console.log(
              `[RCI:js t=${Date.now() % 100000}] onChangeText from native -> "${e.nativeEvent.text}" (len=${e.nativeEvent.text.length})`
            );
          }
          latestTextRef.current = e.nativeEvent.text;
          onChangeText?.(e.nativeEvent.text);
        }}
        onRichContent={
          onRichContent
            ? (e: NativeSyntheticEvent<RichContentResult>) =>
                onRichContent(e.nativeEvent)
            : undefined
        }
        onInputSizeChange={
          onInputSizeChange
            ? (e: NativeSyntheticEvent<ContentSizeResult>) =>
                onInputSizeChange(e.nativeEvent)
            : undefined
        }
        onError={
          onError
            ? (e: NativeSyntheticEvent<RichChatInputError>) =>
                onError(e.nativeEvent)
            : undefined
        }
      />
    );
  }
);
