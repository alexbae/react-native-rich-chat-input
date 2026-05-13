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
  clear: () => void;
}

export const RichChatInput = forwardRef<RichChatInputRef, RichChatInputProps>(
  (props, ref) => {
    const nativeRef =
      useRef<React.ElementRef<typeof NativeRichChatInputView>>(null);

    useImperativeHandle(ref, () => ({
      clear: () => {
        if (nativeRef.current) {
          Commands.clear(nativeRef.current);
        }
      },
    }));

    const { onChangeText, onRichContent, onInputSizeChange, onError, ...rest } =
      props;

    return (
      <NativeRichChatInputView
        ref={nativeRef}
        {...rest}
        onChangeText={
          onChangeText
            ? (e: NativeSyntheticEvent<{ text: string }>) =>
                onChangeText(e.nativeEvent.text)
            : undefined
        }
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
