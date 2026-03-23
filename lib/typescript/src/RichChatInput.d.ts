import type { StyleProp, ViewStyle } from 'react-native';
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
export declare function RichChatInput(props: RichChatInputProps): import("react/jsx-runtime").JSX.Element;
//# sourceMappingURL=RichChatInput.d.ts.map