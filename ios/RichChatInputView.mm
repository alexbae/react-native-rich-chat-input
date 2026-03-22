#import "RichChatInputView.h"

#import <React/RCTConversions.h>

#import <react/renderer/components/RichChatInputViewSpec/ComponentDescriptors.h>
#import <react/renderer/components/RichChatInputViewSpec/EventEmitters.h>
#import <react/renderer/components/RichChatInputViewSpec/Props.h>
#import <react/renderer/components/RichChatInputViewSpec/RCTComponentViewHelpers.h>

#import "RCTFabricComponentsPlugins.h"

using namespace facebook::react;

// ---------------------------------------------------------------------------
#pragma mark - RichChatInputInternalTextView
// ---------------------------------------------------------------------------

@interface RichChatInputInternalTextView : UITextView <UITextViewDelegate>

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
        self.delegate = self;
        self.backgroundColor = [UIColor clearColor];
        self.scrollEnabled = YES;
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

#pragma mark UITextViewDelegate

- (void)textViewDidChange:(UITextView *)textView {
    [self updatePlaceholderVisibility];
    [self.eventDelegate dispatchChangeText:textView.text];
}

- (BOOL)textView:(UITextView *)textView
    shouldChangeTextInRange:(NSRange)range
    replacementText:(NSString *)text {

    // maxLength 강제
    if (self.maxLength > 0) {
        NSUInteger newLen = textView.text.length - range.length + text.length;
        if (newLen > (NSUInteger)self.maxLength) return NO;
    }

    // single-line에서 줄바꿈 차단
    if (!self.multiline) {
        if ([text rangeOfCharacterFromSet:[NSCharacterSet newlineCharacterSet]].location != NSNotFound) {
            return NO;
        }
    }

    return YES;
}

#pragma mark - Paste interception

- (void)paste:(id)sender {
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

    // 4. Generic image (JPEG, HEIC 등) — UIImage로 디코딩 후 PNG 재인코딩
    if ([pb containsPasteboardTypes:UIPasteboardTypeListImage]) {
        UIImage *image = pb.image;
        if (image && [self _mimeTypeIsAccepted:@"image/png"]) {
            NSData *data = UIImagePNGRepresentation(image);
            if (data) {
                [self _saveData:data mimeType:@"image/png" extension:@"png"];
                return;
            }
        }
    }

    // 5. 처리되지 않은 타입 — 기본 동작 위임 (텍스트 붙여넣기 등)
    [super paste:sender];
}

- (UIPasteConfiguration *)pasteConfiguration API_AVAILABLE(ios(11.0)) {
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
    __weak RichChatInputInternalTextView *weakSelf = self;
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        NSString *fileName = [NSString stringWithFormat:@"rich_content_%@.%@",
                              [[NSUUID UUID] UUIDString], ext];
        NSString *filePath = [NSTemporaryDirectory() stringByAppendingPathComponent:fileName];
        NSURL *fileURL = [NSURL fileURLWithPath:filePath];

        NSError *error = nil;
        BOOL success = [data writeToURL:fileURL options:NSDataWritingAtomic error:&error];

        dispatch_async(dispatch_get_main_queue(), ^{
            __strong RichChatInputInternalTextView *strongSelf = weakSelf;
            if (!strongSelf || !success) return;
            NSString *uri = fileURL.absoluteString; // "file:///..."
            [strongSelf.eventDelegate dispatchRichContent:uri mimeType:mimeType];
        });
    });
}

@end

// ---------------------------------------------------------------------------
#pragma mark - RichChatInputView (Fabric wrapper)
// ---------------------------------------------------------------------------

@implementation RichChatInputView {
    RichChatInputInternalTextView *_textView;
}

+ (ComponentDescriptorProvider)componentDescriptorProvider {
    return concreteComponentDescriptorProvider<RichChatInputViewComponentDescriptor>();
}

- (instancetype)initWithFrame:(CGRect)frame {
    if (self = [super initWithFrame:frame]) {
        static const auto defaultProps = std::make_shared<const RichChatInputViewProps>();
        _props = defaultProps;

        _textView = [[RichChatInputInternalTextView alloc] initWithFrame:self.bounds];
        _textView.eventDelegate = self;
        _textView.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;

        self.contentView = _textView;
    }
    return self;
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

- (void)prepareForRecycle {
    [super prepareForRecycle];
    _textView.text = @"";
    [_textView updatePlaceholderVisibility];
}

#pragma mark - Event dispatch (called from RichChatInputInternalTextView)

- (void)dispatchChangeText:(NSString *)text {
    if (!_eventEmitter) return;
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

@end

// Fabric 컴포넌트 등록
Class<RCTComponentViewProtocol> RichChatInputViewCls(void) {
    return RichChatInputView.class;
}
