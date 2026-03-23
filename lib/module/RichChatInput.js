"use strict";

import NativeRichChatInputView from './RichChatInputViewNativeComponent';
import { jsx as _jsx } from "react/jsx-runtime";
export function RichChatInput(props) {
  const {
    onChangeText,
    onRichContent,
    onInputSizeChange,
    ...rest
  } = props;
  return /*#__PURE__*/_jsx(NativeRichChatInputView, {
    ...rest,
    onChangeText: onChangeText ? e => onChangeText(e.nativeEvent.text) : undefined,
    onRichContent: onRichContent ? e => onRichContent(e.nativeEvent) : undefined,
    onInputSizeChange: onInputSizeChange ? e => onInputSizeChange(e.nativeEvent) : undefined
  });
}
//# sourceMappingURL=RichChatInput.js.map