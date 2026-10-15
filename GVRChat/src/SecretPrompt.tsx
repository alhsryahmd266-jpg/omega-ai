/**
 * SecretPrompt.tsx — a secure box that opens when the agent needs a secret.
 * The value goes straight into encrypted storage and is cleared from memory.
 */
import React, { useEffect, useState } from 'react';
import { Modal, View, Text, TextInput, Pressable, StyleSheet } from 'react-native';
import { setSecretRequester, saveSecret } from './secrets';

interface Req { name: string; reason: string; resolve: (ok: boolean) => void }

export function SecretPrompt() {
  const [req, setReq] = useState<Req | null>(null);
  const [value, setValue] = useState('');

  useEffect(() => {
    setSecretRequester((name, reason) => new Promise<boolean>(resolve => setReq({ name, reason, resolve })));
    return () => setSecretRequester(null);
  }, []);

  const close = (ok: boolean) => {
    const current = req;
    setValue('');
    setReq(null);
    if (current) current.resolve(ok);
  };

  const save = async () => {
    if (!req || !value.trim()) return;
    try {
      await saveSecret(req.name, value.trim());
      close(true);
    } catch {
      close(false);
    }
  };

  return (
    <Modal visible={!!req} transparent animationType="fade" onRequestClose={() => close(false)}>
      <View style={styles.backdrop}>
        <View style={styles.card}>
          <Text style={styles.title}>Secure field</Text>
          <Text style={styles.body}>
            The agent needs {req?.name}{req?.reason ? ` (${req.reason})` : ''}. Enter it once. It is stored encrypted on this phone, and the agent never sees the value.
          </Text>
          <TextInput
            value={value}
            onChangeText={setValue}
            secureTextEntry
            autoCapitalize="none"
            autoCorrect={false}
            placeholder="Paste value"
            placeholderTextColor="#777"
            style={styles.input}
          />
          <View style={styles.row}>
            <Pressable onPress={() => close(false)} style={styles.btn}>
              <Text style={styles.btnText}>Cancel</Text>
            </Pressable>
            <Pressable onPress={save} style={[styles.btn, styles.primary]}>
              <Text style={styles.btnText}>Save</Text>
            </Pressable>
          </View>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.6)', justifyContent: 'center', padding: 24 },
  card: { backgroundColor: '#15121f', borderRadius: 16, padding: 20, borderWidth: 1, borderColor: '#3a2f5c' },
  title: { color: '#fff', fontSize: 18, fontWeight: '700', marginBottom: 8 },
  body: { color: '#bbb', fontSize: 14, lineHeight: 20, marginBottom: 14 },
  input: { backgroundColor: '#0d0b14', color: '#fff', borderRadius: 10, padding: 12, borderWidth: 1, borderColor: '#3a2f5c' },
  row: { flexDirection: 'row', justifyContent: 'flex-end', marginTop: 16, gap: 10 },
  btn: { paddingVertical: 10, paddingHorizontal: 16, borderRadius: 10, backgroundColor: '#2a2440' },
  primary: { backgroundColor: '#5b3fd6' },
  btnText: { color: '#fff', fontWeight: '600' },
});
