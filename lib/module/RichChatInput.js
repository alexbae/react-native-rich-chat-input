"use strict";

import React, { forwardRef, useImperativeHandle, useRef } from 'react';
import NativeRichChatInputView, { Commands } from './RichChatInputViewNativeComponent';
import { jsx as _jsx } from "react/jsx-runtime";
export const RichChatInput = forwardRef((props, ref) => {
  const nativeRef = useRef(null);
  useImperativeHandle(ref, () => ({
    clear: () => {
      if (nativeRef.current) {
        Commands.clear(nativeRef.current);
      }
    }
  }));
  const {
    onChangeText,
    onRichContent,
    onInputSizeChange,
    ...rest
  } = props;
  return /*#__PURE__*/_jsx(NativeRichChatInputView, {
    ref: nativeRef,
    ...rest,
    onChangeText: onChangeText ? e => onChangeText(e.nativeEvent.text) : undefined,
    onRichContent: onRichContent ? e => onRichContent(e.nativeEvent) : undefined,
    onInputSizeChange: onInputSizeChange ? e => onInputSizeChange(e.nativeEvent) : undefined
  });
});
//# sourceMappingURL=RichChatInput.js.map
