import { codegenNativeComponent } from 'react-native';
import type { ColorValue, ViewProps } from 'react-native';
import type {
  BubblingEventHandler,
  Float,
  Int32,
} from 'react-native/Libraries/Types/CodegenTypes';

type RichContentEvent = Readonly<{
  uri: string;
  mimeType: string;
}>;

type ChangeTextEvent = Readonly<{
  text: string;
}>;

type ContentSizeChangeEvent = Readonly<{
  width: Float;
  height: Float;
}>;

interface NativeProps extends ViewProps {
  placeholder?: string;
  placeholderTextColor?: ColorValue;
  editable?: boolean;
  multiline?: boolean;
  maxLength?: Int32;
  acceptedMimeTypes?: string[];
  onChangeText?: BubblingEventHandler<ChangeTextEvent>;
  onRichContent?: BubblingEventHandler<RichContentEvent>;
  onInputSizeChange?: BubblingEventHandler<ContentSizeChangeEvent>;
}

export default codegenNativeComponent<NativeProps>('RichChatInputView');
