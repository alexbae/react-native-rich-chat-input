// Codegen spec for the native RichChatInputView.
//
// IMPORTANT: when adding a new BubblingEventHandler prop here, also append a
// matching entry to RichChatInputViewManager.getExportedCustomBubblingEventTypeConstants
// in android/src/main/java/com/whosup/packages/richinput/RichChatInputViewManager.kt.
// That manual map duplicates what Codegen generates, but it's kept on purpose
// to cover hot-reload paths where the generated delegate is bypassed —
// removing it causes events to drop intermittently in dev. See that file's
// header comment for the full reasoning.
import codegenNativeComponent from 'react-native/Libraries/Utilities/codegenNativeComponent';
import codegenNativeCommands from 'react-native/Libraries/Utilities/codegenNativeCommands';
import type { HostComponent, ColorValue, ViewProps } from 'react-native';
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

// Fabric codegen does not support optional struct fields — empty strings
// are passed from native when a particular slot is N/A.
type ErrorEvent = Readonly<{
  code: string;
  message: string;
  nativeClass: string;
  nativeMessage: string;
  nativeStack: string;
}>;

interface NativeProps extends ViewProps {
  placeholder?: string;
  placeholderTextColor?: ColorValue;
  editable?: boolean;
  multiline?: boolean;
  maxLength?: Int32;
  fontSize?: Float;
  acceptedMimeTypes?: string[];
  onChangeText?: BubblingEventHandler<ChangeTextEvent>;
  onRichContent?: BubblingEventHandler<RichContentEvent>;
  onInputSizeChange?: BubblingEventHandler<ContentSizeChangeEvent>;
  onError?: BubblingEventHandler<ErrorEvent>;
}

type ComponentType = HostComponent<NativeProps>;

interface NativeCommands {
  clear: (viewRef: React.ElementRef<ComponentType>) => void;
}

export const Commands: NativeCommands = codegenNativeCommands<NativeCommands>({
  supportedCommands: ['clear'],
});

export default codegenNativeComponent<NativeProps>(
  'RichChatInputView'
) as ComponentType;
