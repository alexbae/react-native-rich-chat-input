import React from 'react';
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
export interface RichChatInputRef {
    clear: () => void;
}
export declare const RichChatInput: React.ForwardRefExoticComponent<RichChatInputProps & React.RefAttributes<RichChatInputRef>>;
//# sourceMappingURL=RichChatInput.d.ts.map
