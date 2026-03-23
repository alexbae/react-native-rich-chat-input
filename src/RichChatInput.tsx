import type { NativeSyntheticEvent, StyleProp, ViewStyle } from 'react-native';
import NativeRichChatInputView from './RichChatInputViewNativeComponent';

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
  acceptedMimeTypes?: string[];
  style?: StyleProp<ViewStyle>;
  onChangeText?: (text: string) => void;
  onRichContent?: (content: RichContentResult) => void;
  onInputSizeChange?: (size: ContentSizeResult) => void;
}

export function RichChatInput(props: RichChatInputProps) {
  const { onChangeText, onRichContent, onInputSizeChange, ...rest } = props;

  return (
    <NativeRichChatInputView
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
