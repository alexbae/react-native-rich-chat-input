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

    const { onChangeText, onRichContent, onInputSizeChange, ...rest } = props;

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
      />
    );
  }
);
