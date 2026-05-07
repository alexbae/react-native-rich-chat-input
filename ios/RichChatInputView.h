#import <React/RCTViewComponentView.h>
#import <UIKit/UIKit.h>

#ifndef RichChatInputViewNativeComponent_h
#define RichChatInputViewNativeComponent_h

NS_ASSUME_NONNULL_BEGIN

@class RichChatInputInternalTextView;

@interface RichChatInputView : RCTViewComponentView

- (void)dispatchChangeText:(NSString *)text;
- (void)dispatchRichContent:(NSString *)uri mimeType:(NSString *)mimeType;
- (void)dispatchInputSizeChange:(CGSize)size;
- (void)clear;

@end

NS_ASSUME_NONNULL_END

#endif /* RichChatInputViewNativeComponent_h */
