import { useState } from 'react';
import {
  Image,
  Platform,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { RichChatInput } from 'react-native-rich-chat-input';

import type { RichContentResult } from 'react-native-rich-chat-input';

export default function App() {
  const [text, setText] = useState('');
  const [richContents, setRichContents] = useState<RichContentResult[]>([]);

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.headerTitle}>RichChatInput Test</Text>
        <Text style={styles.headerSub}>Platform: {Platform.OS}</Text>
      </View>

      <ScrollView style={styles.previewArea} contentContainerStyle={styles.previewContent}>
        {richContents.length === 0 ? (
          <Text style={styles.emptyHint}>
            {'Keyboard\uc5d0\uc11c GIF/\uc774\ubbf8\uc9c0\ub97c \ubd99\uc5ec\ub123\uc73c\uba74 \ud504\ub9ac\ubdf0\uac00 \ud45c\uc2dc\ub429\ub2c8\ub2e4'}
          </Text>
        ) : (
          richContents.map((item, index) => (
            <View key={index} style={styles.previewItem}>
              <Image source={{ uri: item.uri }} style={styles.previewImage} resizeMode="contain" />
              <Text style={styles.mimeText}>{item.mimeType}</Text>
              <Text style={styles.uriText} numberOfLines={2}>{item.uri}</Text>
            </View>
          ))
        )}
      </ScrollView>

      <View style={styles.textPreview}>
        <Text style={styles.textPreviewLabel}>onChangeText:</Text>
        <Text style={styles.textPreviewValue} numberOfLines={2}>
          {text || '(empty)'}
        </Text>
      </View>

      <View style={styles.inputWrapper}>
        <RichChatInput
          placeholder="Type a message or insert GIF..."
          placeholderTextColor="#999"
          multiline
          maxLength={2000}
          acceptedMimeTypes={['image/*']}
          onChangeText={(text) => setText(text)}
          onRichContent={(content) => setRichContents((prev) => [content, ...prev])}
          style={styles.input}
        />
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  header: {
    backgroundColor: '#1a1a2e',
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  headerTitle: {
    color: '#fff',
    fontSize: 18,
    fontWeight: 'bold',
  },
  headerSub: {
    color: '#aaa',
    fontSize: 12,
    marginTop: 2,
  },
  previewArea: {
    flex: 1,
    padding: 12,
  },
  previewContent: {
    flexGrow: 1,
  },
  emptyHint: {
    color: '#999',
    textAlign: 'center',
    marginTop: 40,
    fontSize: 14,
  },
  previewItem: {
    backgroundColor: '#fff',
    borderRadius: 8,
    padding: 10,
    marginBottom: 10,
    shadowColor: '#000',
    shadowOpacity: 0.05,
    shadowRadius: 4,
    elevation: 2,
  },
  previewImage: {
    width: '100%',
    height: 200,
    borderRadius: 6,
    backgroundColor: '#eee',
  },
  mimeText: {
    fontSize: 12,
    color: '#4a90e2',
    marginTop: 6,
    fontWeight: '600',
  },
  uriText: {
    fontSize: 10,
    color: '#888',
    marginTop: 2,
  },
  textPreview: {
    backgroundColor: '#fff',
    marginHorizontal: 12,
    marginBottom: 8,
    borderRadius: 8,
    padding: 10,
    borderWidth: 1,
    borderColor: '#e0e0e0',
  },
  textPreviewLabel: {
    fontSize: 11,
    color: '#999',
    marginBottom: 2,
  },
  textPreviewValue: {
    fontSize: 14,
    color: '#333',
  },
  inputWrapper: {
    backgroundColor: '#fff',
    borderTopWidth: 1,
    borderTopColor: '#e0e0e0',
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
  input: {
    minHeight: 40,
    maxHeight: 120,
    fontSize: 16,
    color: '#333',
  },
});
