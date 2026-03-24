#import "RichChatInputView.h"

#import <React/RCTConversions.h>

#import <react/renderer/components/RichChatInputViewSpec/EventEmitters.h>
#import <react/renderer/components/RichChatInputViewSpec/Props.h>
#import <react/renderer/components/RichChatInputViewSpec/RCTComponentViewHelpers.h>
#import "RichChatInputMeasuringShadowNode.h"

#import "RCTFabricComponentsPlugins.h"

using namespace facebook::react;

static const NSUInteger kRichContentMaxFileSize = 20 * 1024 * 1024; // 20 MB
static const NSTimeInterval kRichContentCacheMaxAge = 7 * 24 * 60 * 60; // 7일 (초)

// ---------------------------------------------------------------------------
#pragma mark - RichChatInputInternalTextView
// ---------------------------------------------------------------------------

@interface RichChatInputInternalTextView : UITextView

@property (nonatomic, weak, nullable) RichChatInputView *eventDelegate;
@property (nonatomic, copy, nullable) NSString *placeholderText;
@property (nonatomic, strong, nullable) UIColor *placeholderColor;
@property (nonatomic, copy) NSArray<NSString *> *acceptedMimeTypes;
@property (nonatomic, assign) BOOL multiline;
@property (nonatomic, assign) NSInteger maxLength;

- (void)updatePlaceholderVisibility;

@end

@implementation RichChatInputInternalTextView {
    UILabel *_placeholderLabel;
}

- (instancetype)initWithFrame:(CGRect)frame {
    if (self = [super initWithFrame:frame]) {
        // Do NOT set self.delegate = self here.
        // KCTextInputCompositeDelegate (react-native-keyboard-controller) replaces the
        // UITextView delegate on focus and saves the old delegate as its forwarding target.
        // If the saved target is the UITextView itself, UITextView's own forwarding path
        // bounces the call back to KCTextInputCompositeDelegate → infinite recursion → crash.
        // Instead, constraints (maxLength, multiline) are enforced via -insertText: override.
        self.backgroundColor = [UIColor clearColor];
        self.scrollEnabled = NO; // Enabled dynamically by RichChatInputView when content overflows
        // textContainerInset is set dynamically by RichChatInputView.updateLayoutMetrics
        // to mirror the padding values from the React style.
        self.textContainerInset = UIEdgeInsetsZero;
        self.textContainer.lineFragmentPadding = 0;
        self.font = [UIFont systemFontOfSize:17.0];

        _acceptedMimeTypes = @[@"image/*"];
        // C++ codegen 기본값 bool multiline{false}와 일치시킨다.
        // multiline prop을 전달하지 않으면 단일 라인으로 동작한다.
        _multiline = NO;
        _maxLength = 0;

        _placeholderLabel = [[UILabel alloc] init];
        _placeholderLabel.numberOfLines = 0;
        _placeholderLabel.userInteractionEnabled = NO;
        _placeholderLabel.font = self.font;
        _placeholderLabel.textColor = [UIColor placeholderTextColor];
        [self addSubview:_placeholderLabel];

        // NSTextStorageDidProcessEditingNotification fires from NSTextStorage itself
        // (lower than UITextViewDelegate), so it fires regardless of who the
        // UITextView delegate is — including when react-native-keyboard-controller
        // installs KCTextInputCompositeDelegate and blocks textViewDidChange:.
        [[NSNotificationCenter defaultCenter] addObserver:self
                                                 selector:@selector(_textStorageDidChange:)
                                                     name:NSTextStorageDidProcessEditingNotification
                                                   object:self.textStorage];

        [self _cleanStaleTempFiles];
    }
    return self;
}

- (void)layoutSubviews {
    [super layoutSubviews];
    UIEdgeInsets insets = self.textContainerInset;
    CGFloat lp = self.textContainer.lineFragmentPadding;
    CGFloat x = insets.left + lp;
    CGFloat y = insets.top;
    CGFloat w = self.bounds.size.width - insets.left - insets.right - lp * 2;
    CGFloat h = self.bounds.size.height - insets.top - insets.bottom;
    _placeholderLabel.frame = CGRectMake(x, y, w, h);
}

- (void)setPlaceholderText:(NSString *)placeholderText {
    _placeholderText = [placeholderText copy];
    _placeholderLabel.text = _placeholderText;
    [self updatePlaceholderVisibility];
}

- (void)setPlaceholderColor:(UIColor *)placeholderColor {
    _placeholderColor = placeholderColor;
    _placeholderLabel.textColor = placeholderColor ?: [UIColor placeholderTextColor];
}

- (void)updatePlaceholderVisibility {
    _placeholderLabel.hidden = (self.text.length > 0);
}

- (void)_textStorageDidChange:(NSNotification *)note {
    NSTextStorage *storage = note.object;
    // Only react to character edits (not attribute-only changes like spell-check highlights)
    if (!(storage.editedMask & NSTextStorageEditedCharacters)) return;
    [self updatePlaceholderVisibility];
    [self.eventDelegate dispatchChangeText:self.text];
    // Defer size measurement to the next run loop iteration.
    //
    // NSTextStorageDidProcessEditingNotification fires while NSLayoutManager has
    // only *invalidated* the layout — it has not yet re-laid out the text.
    // Calling sizeThatFits: synchronously at this point causes ensureLayoutForTextContainer:
    // to run inside the still-active editing cycle, which can return the pre-edit
    // height (newline not yet accounted for), preventing the height from expanding.
    // Deferring to the next run loop also lets the RTI acknowledgement return
    // immediately, avoiding [TextInputUI] Result accumulator timeout warnings.
    __weak RichChatInputInternalTextView *weakSelf = self;
    dispatch_async(dispatch_get_main_queue(), ^{
        __strong RichChatInputInternalTextView *strongSelf = weakSelf;
        if (!strongSelf || !strongSelf.window) return;
        CGFloat measureWidth = strongSelf.bounds.size.width > 0 ? strongSelf.bounds.size.width : 1000;
        CGSize contentSize = [strongSelf sizeThatFits:CGSizeMake(measureWidth, CGFLOAT_MAX)];
        [strongSelf.eventDelegate dispatchInputSizeChange:contentSize];
    });
}

// -insertText: is the UITextInput entry point for all keyboard-driven input
// (character typing, autocorrect replacements, predictive text, etc.).
// Enforcing constraints here replaces the UITextViewDelegate shouldChangeTextInRange:
// approach, which required self.delegate = self — the source of the
// KCTextInputCompositeDelegate infinite-recursion crash.
- (void)insertText:(NSString *)text {
    // single-line: block newlines
    if (!self.multiline) {
        if ([text rangeOfCharacterFromSet:[NSCharacterSet newlineCharacterSet]].location != NSNotFound) {
            return;
        }
    }

    // maxLength: account for the selected range that will be replaced
    if (self.maxLength > 0) {
        NSRange selectedRange = self.selectedRange;
        NSUInteger currentLen = self.text.length;
        NSUInteger replaceLen = (selectedRange.location != NSNotFound) ? selectedRange.length : 0;
        NSUInteger newLen = currentLen - replaceLen + text.length;
        if (newLen > (NSUInteger)self.maxLength) {
            // Insert only as many characters as the budget allows
            NSUInteger budget = (NSUInteger)self.maxLength - (currentLen - replaceLen);
            if (budget == 0) return;
            text = [text substringToIndex:MIN(budget, text.length)];
        }
    }

    [super insertText:text];
}

#pragma mark - Paste interception

- (void)paste:(id)sender {
#if TARGET_OS_SIMULATOR
    // iOS 시뮬레이터에서는 키보드 소스의 Rich Content(GIF, 스티커 등)가
    // UIPasteboard를 통해 전달되지 않으므로 기본 텍스트 붙여넣기로 폴스루한다.
    [super paste:sender];
#else
    UIPasteboard *pb = [UIPasteboard generalPasteboard];

    // 1. GIF — UIImage 변환 시 애니메이션 손실되므로 NSData 직접 처리
    NSString *gifUTI = @"com.compuserve.gif";
    if ([pb containsPasteboardTypes:@[gifUTI]]) {
        NSData *data = [pb dataForPasteboardType:gifUTI];
        if (data && [self _mimeTypeIsAccepted:@"image/gif"]) {
            [self _saveData:data mimeType:@"image/gif" extension:@"gif"];
            return;
        }
    }

    // 2. WebP
    NSString *webpUTI = @"org.webmproject.webp";
    if ([pb containsPasteboardTypes:@[webpUTI]]) {
        NSData *data = [pb dataForPasteboardType:webpUTI];
        if (data && [self _mimeTypeIsAccepted:@"image/webp"]) {
            [self _saveData:data mimeType:@"image/webp" extension:@"webp"];
            return;
        }
    }

    // 3. PNG (lossless 우선)
    NSString *pngUTI = @"public.png";
    if ([pb containsPasteboardTypes:@[pngUTI]]) {
        NSData *data = [pb dataForPasteboardType:pngUTI];
        if (data && [self _mimeTypeIsAccepted:@"image/png"]) {
            [self _saveData:data mimeType:@"image/png" extension:@"png"];
            return;
        }
    }

    // 4. Generic image (JPEG 등) — raw 바이트를 직접 사용해 메모리 이중 할당 방지
    //    (UIImage 디코딩 후 UIImagePNGRepresentation 재인코딩 방식 대비 메모리 절반 사용)
    if ([pb containsPasteboardTypes:UIPasteboardTypeListImage]) {
        NSData *jpegData = [pb dataForPasteboardType:@"public.jpeg"];
        if (jpegData && [self _mimeTypeIsAccepted:@"image/jpeg"]) {
            [self _saveData:jpegData mimeType:@"image/jpeg" extension:@"jpg"];
            return;
        }
        NSData *pngData2 = [pb dataForPasteboardType:@"public.png"];
        if (pngData2 && [self _mimeTypeIsAccepted:@"image/png"]) {
            [self _saveData:pngData2 mimeType:@"image/png" extension:@"png"];
            return;
        }
    }

    // 5. 처리되지 않은 타입 — 기본 동작 위임 (텍스트 붙여넣기 등)
    [super paste:sender];
#endif
}

- (UIPasteConfiguration *)pasteConfiguration API_AVAILABLE(ios(11.0)) {
#if TARGET_OS_SIMULATOR
    return nil;
#else
    NSMutableArray<NSString *> *utis = [NSMutableArray array];
    for (NSString *mime in self.acceptedMimeTypes) {
        if ([mime isEqualToString:@"image/*"] || [mime isEqualToString:@"image/gif"]) {
            [utis addObject:@"com.compuserve.gif"];
        }
        if ([mime isEqualToString:@"image/*"] || [mime isEqualToString:@"image/png"]) {
            [utis addObject:@"public.png"];
        }
        if ([mime isEqualToString:@"image/*"] || [mime isEqualToString:@"image/jpeg"]) {
            [utis addObject:@"public.jpeg"];
        }
        if ([mime isEqualToString:@"image/*"] || [mime isEqualToString:@"image/webp"]) {
            [utis addObject:@"org.webmproject.webp"];
        }
        if ([mime isEqualToString:@"image/*"]) {
            [utis addObject:@"public.image"];
        }
        if ([mime isEqualToString:@"video/*"]) {
            [utis addObject:@"public.movie"];
        }
    }
    if (utis.count == 0) {
        [utis addObject:@"public.image"];
    }
    return [[UIPasteConfiguration alloc] initWithAcceptableTypeIdentifiers:utis];
#endif
}

#pragma mark - Private helpers

- (BOOL)_mimeTypeIsAccepted:(NSString *)mimeType {
    if (self.acceptedMimeTypes.count == 0) return YES;
    for (NSString *pattern in self.acceptedMimeTypes) {
        if ([pattern isEqualToString:@"image/*"] && [mimeType hasPrefix:@"image/"]) return YES;
        if ([pattern isEqualToString:@"video/*"] && [mimeType hasPrefix:@"video/"]) return YES;
        if ([pattern caseInsensitiveCompare:mimeType] == NSOrderedSame) return YES;
    }
    return NO;
}

- (void)_saveData:(NSData *)data mimeType:(NSString *)mimeType extension:(NSString *)ext {
    if (data.length > kRichContentMaxFileSize) {
        NSLog(@"[RichChatInput] Rejecting content: size %lu bytes exceeds %lu MB limit",
              (unsigned long)data.length, (unsigned long)(kRichContentMaxFileSize / 1024 / 1024));
        return;
    }

    __weak RichChatInputInternalTextView *weakSelf = self;
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        NSString *fileName = [NSString stringWithFormat:@"rich_content_%@.%@",
                              [[NSUUID UUID] UUIDString], ext];
        NSString *filePath = [NSTemporaryDirectory() stringByAppendingPathComponent:fileName];
        NSURL *fileURL = [NSURL fileURLWithPath:filePath];

        NSError *error = nil;
        BOOL success = [data writeToURL:fileURL options:NSDataWritingAtomic error:&error];

        if (!success) {
            NSLog(@"[RichChatInput] Failed to write temp file: %@", error.localizedDescription);
        }

        dispatch_async(dispatch_get_main_queue(), ^{
            __strong RichChatInputInternalTextView *strongSelf = weakSelf;
            if (!strongSelf || !success) return;
            NSString *uri = fileURL.absoluteString; // "file:///..."
            [strongSelf.eventDelegate dispatchRichContent:uri mimeType:mimeType];
        });
    });
}

- (void)_cleanStaleTempFiles {
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_BACKGROUND, 0), ^{
        NSFileManager *fm = [NSFileManager defaultManager];
        NSString *tmpDir = NSTemporaryDirectory();
        NSError *error = nil;
        NSArray<NSString *> *files = [fm contentsOfDirectoryAtPath:tmpDir error:&error];
        if (!files) return;

        NSDate *cutoff = [NSDate dateWithTimeIntervalSinceNow:-kRichContentCacheMaxAge];
        for (NSString *name in files) {
            if (![name hasPrefix:@"rich_content_"]) continue;
            NSString *fullPath = [tmpDir stringByAppendingPathComponent:name];
            NSDictionary *attrs = [fm attributesOfItemAtPath:fullPath error:nil];
            NSDate *modDate = attrs[NSFileModificationDate];
            if (modDate && [modDate compare:cutoff] == NSOrderedAscending) {
                NSError *removeError = nil;
                if (![fm removeItemAtPath:fullPath error:&removeError]) {
                    NSLog(@"[RichChatInput] Failed to delete stale temp file %@: %@",
                          name, removeError.localizedDescription);
                }
            }
        }
    });
}

@end

// ---------------------------------------------------------------------------
#pragma mark - RichChatInputView (Fabric wrapper)
// ---------------------------------------------------------------------------

@implementation RichChatInputView {
    RichChatInputInternalTextView *_textView;
    RichChatInputMeasuringShadowNode::ConcreteState::Shared _state;
    CGFloat _lastContentHeight;
}

+ (ComponentDescriptorProvider)componentDescriptorProvider {
    return concreteComponentDescriptorProvider<RichChatInputMeasuringComponentDescriptor>();
}

- (instancetype)initWithFrame:(CGRect)frame {
    if (self = [super initWithFrame:frame]) {
        static const auto defaultProps = std::make_shared<const RichChatInputViewProps>();
        _props = defaultProps;
        _lastContentHeight = 0;

        _textView = [[RichChatInputInternalTextView alloc] initWithFrame:self.bounds];
        _textView.eventDelegate = self;
        // Use contentView so Fabric properly tracks this subview.
        // updateLayoutMetrics below overrides the frame back to full bounds after super
        // applies the padding-reduced content frame, so the text view always fills the
        // entire component while textContainerInset handles the visual padding.
        self.contentView = _textView;
    }
    return self;
}

- (void)updateLayoutMetrics:(const facebook::react::LayoutMetrics &)layoutMetrics
           oldLayoutMetrics:(const facebook::react::LayoutMetrics &)oldLayoutMetrics
{
    CGRect frameBefore = _textView.frame;
    BOOL isFirstResponder = _textView.isFirstResponder;

    // Detect whether JS has intentionally requested a new height (e.g. via
    // onContentSizeChange → setState). If so, we allow the frame change so
    // the input can auto-expand. Spurious re-renders that repeat the same
    // layout metrics are still blocked to protect the RTI session.
    CGFloat oldLayoutHeight = RCTCGRectFromRect(oldLayoutMetrics.getContentFrame()).size.height;
    CGFloat newLayoutHeight = RCTCGRectFromRect(layoutMetrics.getContentFrame()).size.height;
    BOOL layoutHeightChanged = fabs(newLayoutHeight - oldLayoutHeight) > 0.5;

    [super updateLayoutMetrics:layoutMetrics oldLayoutMetrics:oldLayoutMetrics];

    CGRect frameAfter = _textView.frame;
    BOOL frameChanged = !CGRectEqualToRect(frameBefore, frameAfter);
    // Only restore frame when: first responder AND frame changed AND the layout
    // height itself did NOT change. Allowing intentional height changes (driven
    // by onContentSizeChange) lets the input auto-expand while blocking spurious
    // Fabric layout passes that would disconnect the RTI session.
    if (frameChanged && isFirstResponder && !layoutHeightChanged) {
        _textView.frame = frameBefore;
    }

    // Enable scrolling only when content height exceeds the (possibly capped) view height.
    // This prevents the bounce effect and scrollbar from appearing when the input is short.
    CGFloat viewHeight = _textView.bounds.size.height;
    if (viewHeight > 0) {
        BOOL shouldScroll = _lastContentHeight > viewHeight + 0.5;
        if (_textView.scrollEnabled != shouldScroll) {
            _textView.scrollEnabled = shouldScroll;
        }
    }
}

// Forward all taps inside the component (including padding area) to _textView
// so the keyboard opens even when the user taps near the edges.
- (UIView *)hitTest:(CGPoint)point withEvent:(UIEvent *)event {
    if (CGRectContainsPoint(self.bounds, point) && _textView && !_textView.isHidden) {
        return _textView;
    }
    return [super hitTest:point withEvent:event];
}

- (void)updateProps:(Props::Shared const &)props oldProps:(Props::Shared const &)oldProps {
    const auto &oldViewProps = *std::static_pointer_cast<RichChatInputViewProps const>(_props);
    const auto &newViewProps = *std::static_pointer_cast<RichChatInputViewProps const>(props);

    // placeholder
    if (oldViewProps.placeholder != newViewProps.placeholder) {
        _textView.placeholderText = [NSString stringWithUTF8String:newViewProps.placeholder.c_str()];
    }

    // placeholderTextColor
    if (oldViewProps.placeholderTextColor != newViewProps.placeholderTextColor) {
        if (newViewProps.placeholderTextColor) {
            _textView.placeholderColor = RCTUIColorFromSharedColor(newViewProps.placeholderTextColor);
        } else {
            _textView.placeholderColor = nil;
        }
    }

    // editable
    // NOTE: C++ codegen 기본값이 bool editable{false}이므로 JS에서 editable={false}를
    // 첫 마운트 시 명시적으로 전달해도 old/new가 모두 false여서 diff가 발동하지 않는다.
    // UITextView 기본값(editable=YES)이 유지되는 부작용이 있으며, JS wrapper(Phase 3)에서
    // editable을 항상 명시적으로 전달하는 방식으로 해결할 예정이다.
    if (oldViewProps.editable != newViewProps.editable) {
        _textView.editable = newViewProps.editable;
        _textView.selectable = newViewProps.editable;
    }

    // multiline
    if (oldViewProps.multiline != newViewProps.multiline) {
        _textView.multiline = newViewProps.multiline;
    }

    // maxLength
    if (oldViewProps.maxLength != newViewProps.maxLength) {
        _textView.maxLength = newViewProps.maxLength;
    }

    // acceptedMimeTypes: std::vector<std::string> → NSArray<NSString *>
    if (oldViewProps.acceptedMimeTypes != newViewProps.acceptedMimeTypes) {
        NSMutableArray<NSString *> *types = [NSMutableArray array];
        for (const auto &t : newViewProps.acceptedMimeTypes) {
            NSString *s = [NSString stringWithUTF8String:t.c_str()];
            if (s.length > 0) [types addObject:s];
        }
        _textView.acceptedMimeTypes = types.count > 0 ? [types copy] : @[@"image/*"];
    }

    [super updateProps:props oldProps:oldProps];
}

- (void)updateState:(const facebook::react::State::Shared &)state
          oldState:(const facebook::react::State::Shared &)oldState {
    _state = std::static_pointer_cast<const RichChatInputMeasuringShadowNode::ConcreteState>(state);
}

- (void)prepareForRecycle {
    [super prepareForRecycle];
    _textView.text = @"";
    [_textView updatePlaceholderVisibility];
    _state = nullptr;
}

#pragma mark - Event dispatch (called from RichChatInputInternalTextView)

- (void)dispatchChangeText:(NSString *)text {
    if (!_eventEmitter) return;
    // TODO: Genmoji (NSAdaptiveImageGlyph, iOS 18+) and other NSTextAttachment objects
    // are represented as U+FFFC in the plain text string and cannot cross the JS bridge
    // as meaningful content. Proper fix: enumerate NSAttachmentAttributeName /
    // NSAdaptiveImageGlyphAttributeName in the NSAttributedString, extract the image
    // data, write to a temp file, and dispatch via onRichContent instead.
    // For now, empty-message prevention is handled on the JS side.
    auto emitter = std::static_pointer_cast<RichChatInputViewEventEmitter const>(_eventEmitter);
    emitter->onChangeText(RichChatInputViewEventEmitter::OnChangeText{
        .text = std::string(text.UTF8String ?: "")
    });
}

- (void)dispatchRichContent:(NSString *)uri mimeType:(NSString *)mimeType {
    if (!_eventEmitter) return;
    auto emitter = std::static_pointer_cast<RichChatInputViewEventEmitter const>(_eventEmitter);
    emitter->onRichContent(RichChatInputViewEventEmitter::OnRichContent{
        .uri      = std::string(uri.UTF8String ?: ""),
        .mimeType = std::string(mimeType.UTF8String ?: "")
    });
}

- (void)dispatchInputSizeChange:(CGSize)size {
    _lastContentHeight = size.height;

    // Update Fabric shadow node state directly — this triggers a native layout
    // pass (Yoga calls measureContent) without any JS round-trip, so the input
    // height grows in the same frame as the cursor movement.
    if (_state) {
        RichChatInputMeasuringStateData newStateData;
        newStateData.contentHeight = (float)size.height;
        if (newStateData != _state->getData()) {
            _state->updateState(std::move(newStateData));
        }
    }

    // Also emit the JS event for backward-compat (e.g. callers that still use
    // onInputSizeChange to drive other UI outside the input height).
    if (!_eventEmitter) return;
    auto emitter = std::static_pointer_cast<RichChatInputViewEventEmitter const>(_eventEmitter);
    emitter->onInputSizeChange(RichChatInputViewEventEmitter::OnInputSizeChange{
        .width  = (double)size.width,
        .height = (double)size.height
    });
}

@end

// Fabric 컴포넌트 등록
Class<RCTComponentViewProtocol> RichChatInputViewCls(void) {
    return RichChatInputView.class;
}
