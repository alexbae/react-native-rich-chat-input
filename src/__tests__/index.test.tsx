import { create, act } from 'react-test-renderer';
import { RichChatInput } from '../RichChatInput';

jest.mock('../RichChatInputViewNativeComponent', () => 'RichChatInputView');

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

    it('is undefined when prop is not provided', () => {
      const renderer = render(<RichChatInput />);
      expect(getNativeProps(renderer).onChangeText).toBeUndefined();
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
