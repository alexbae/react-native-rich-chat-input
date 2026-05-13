import React, { createRef } from 'react';
import { create, act } from 'react-test-renderer';
import { RichChatInput } from '../RichChatInput';
import type { RichChatInputRef } from '../RichChatInput';

// Mock the native component as a string tag so react-test-renderer can find
// it via `findByType`. The Commands.clear stub is a no-op — the wrapper
// guards behind `if (nativeRef.current)` (which is null in test-renderer),
// so the function is never actually called from the test; it just needs to
// exist on the imported object.
jest.mock('../RichChatInputViewNativeComponent', () => ({
  __esModule: true,
  default: 'RichChatInputView',
  Commands: { clear: jest.fn() },
}));

function render(ui: React.ReactElement) {
  let renderer!: ReturnType<typeof create>;
  act(() => {
    renderer = create(ui);
  });
  return renderer;
}

const getNativeProps = (renderer: ReturnType<typeof create>) =>
  renderer.root.findByType('RichChatInputView' as never).props;

describe('RichChatInput', () => {
  describe('onChangeText', () => {
    it('unwraps nativeEvent and calls handler with text string', () => {
      const onChangeText = jest.fn();
      const renderer = render(<RichChatInput onChangeText={onChangeText} />);

      act(() => {
        getNativeProps(renderer).onChangeText({
          nativeEvent: { text: 'hello' },
        });
      });

      expect(onChangeText).toHaveBeenCalledWith('hello');
    });

    it('still attaches a native handler when the host prop is absent (for getText tracking)', () => {
      const renderer = render(<RichChatInput />);
      // The wrapper always installs an onChangeText handler internally so
      // latestTextRef stays in sync for getText(). Native dispatches fire
      // unconditionally, so this has no perf cost.
      expect(typeof getNativeProps(renderer).onChangeText).toBe('function');
    });

    it('does not throw when the host prop is absent and a native event fires', () => {
      const renderer = render(<RichChatInput />);
      expect(() => {
        act(() => {
          getNativeProps(renderer).onChangeText({
            nativeEvent: { text: 'no-op' },
          });
        });
      }).not.toThrow();
    });
  });

  describe('imperative ref', () => {
    describe('clear()', () => {
      it('resets the JS-side latest-text mirror so getText() returns empty after clear', () => {
        const ref = createRef<RichChatInputRef>();
        const renderer = render(<RichChatInput ref={ref} />);

        act(() => {
          getNativeProps(renderer).onChangeText({
            nativeEvent: { text: 'hello' },
          });
        });
        expect(ref.current?.getText()).toBe('hello');

        act(() => {
          ref.current?.clear();
        });
        expect(ref.current?.getText()).toBe('');
      });

      it('does not throw when invoked before the native view ref is populated', () => {
        // react-test-renderer does not populate refs on string-typed host
        // components, so the wrapper's `if (nativeRef.current)` guard takes
        // the no-op branch. The imperative `clear()` must still complete
        // cleanly (and reset the JS-side mirror — covered above) instead of
        // throwing on the host. The native command dispatch is verified by
        // the example app and platform-specific tests.
        const ref = createRef<RichChatInputRef>();
        render(<RichChatInput ref={ref} />);

        expect(() => act(() => ref.current?.clear())).not.toThrow();
      });
    });

    describe('getText()', () => {
      it('returns empty string before any text-change event', () => {
        const ref = createRef<RichChatInputRef>();
        render(<RichChatInput ref={ref} />);
        expect(ref.current?.getText()).toBe('');
      });

      it('returns the most recent text dispatched by native onChangeText', () => {
        const ref = createRef<RichChatInputRef>();
        const renderer = render(<RichChatInput ref={ref} />);

        act(() => {
          getNativeProps(renderer).onChangeText({
            nativeEvent: { text: 'first' },
          });
        });
        expect(ref.current?.getText()).toBe('first');

        act(() => {
          getNativeProps(renderer).onChangeText({
            nativeEvent: { text: 'second' },
          });
        });
        expect(ref.current?.getText()).toBe('second');
      });

      it('tracks the latest value even when the host did not pass onChangeText', () => {
        const ref = createRef<RichChatInputRef>();
        const renderer = render(<RichChatInput ref={ref} />);

        act(() => {
          getNativeProps(renderer).onChangeText({
            nativeEvent: { text: 'tracked-anyway' },
          });
        });

        expect(ref.current?.getText()).toBe('tracked-anyway');
      });
    });
  });

  describe('onRichContent', () => {
    it('unwraps nativeEvent and calls handler with content object', () => {
      const onRichContent = jest.fn();
      const renderer = render(<RichChatInput onRichContent={onRichContent} />);

      const content = { uri: 'file:///tmp/abc.gif', mimeType: 'image/gif' };
      act(() => {
        getNativeProps(renderer).onRichContent({ nativeEvent: content });
      });

      expect(onRichContent).toHaveBeenCalledWith(content);
    });

    it('is undefined when prop is not provided', () => {
      const renderer = render(<RichChatInput />);
      expect(getNativeProps(renderer).onRichContent).toBeUndefined();
    });
  });

  describe('onError', () => {
    it('unwraps nativeEvent and calls handler with error object', () => {
      const onError = jest.fn();
      const renderer = render(<RichChatInput onError={onError} />);

      const err = {
        code: 'RICH_CONTENT_FILE_NOT_FOUND' as const,
        message: 'Pasted content URI was not found',
        nativeClass: 'java.io.FileNotFoundException',
        nativeMessage: 'No such file or directory',
        nativeStack: 'java.io.FileNotFoundException: ...',
      };
      act(() => {
        getNativeProps(renderer).onError({ nativeEvent: err });
      });

      expect(onError).toHaveBeenCalledWith(err);
    });

    it('is undefined when prop is not provided', () => {
      const renderer = render(<RichChatInput />);
      expect(getNativeProps(renderer).onError).toBeUndefined();
    });
  });

  describe('prop pass-through', () => {
    it('passes all non-event props directly to native component', () => {
      const renderer = render(
        <RichChatInput
          placeholder="Type here"
          placeholderTextColor="#999"
          editable={false}
          multiline
          maxLength={500}
          acceptedMimeTypes={['image/gif', 'image/webp']}
        />
      );

      const props = getNativeProps(renderer);
      expect(props.placeholder).toBe('Type here');
      expect(props.placeholderTextColor).toBe('#999');
      expect(props.editable).toBe(false);
      expect(props.multiline).toBe(true);
      expect(props.maxLength).toBe(500);
      expect(props.acceptedMimeTypes).toEqual(['image/gif', 'image/webp']);
    });
  });
});
