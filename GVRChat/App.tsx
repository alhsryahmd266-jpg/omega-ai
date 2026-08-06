import React, { useState, useRef, useEffect, useCallback, useMemo } from 'react';
import {
  View, Text, TextInput, TouchableOpacity, FlatList,
  StyleSheet, KeyboardAvoidingView, Platform, StatusBar,
  ActivityIndicator, Dimensions, Animated, Modal,
  TouchableWithoutFeedback, ScrollView, Alert, Keyboard,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { LinearGradient } from 'expo-linear-gradient';
import { BlurView } from 'expo-blur';
import Svg, { Circle, Line, Defs, RadialGradient, Stop, Rect } from 'react-native-svg';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { Ionicons, MaterialCommunityIcons } from '@expo/vector-icons';

import {
  listLocalModels, importModelFromDevice, loadModel,
  isModelLoaded, isVisionReady, type ModelInfo,
} from './src/localLLM';
import { runWithAttachment, type StepEvent } from './src/gvrEngine';
import { prepareAttachment, pickAnyFile, type PreparedAttachment } from './src/attachments';

const { width: W, height: H } = Dimensions.get('window');

const C = {
  bg: '#05050f', surface: 'rgba(255,255,255,0.04)', border: 'rgba(255,255,255,0.08)',
  glow: '#7c3aed', glow2: '#06b6d4', accent: '#8b5cf6', accentB: '#06b6d4',
  green: '#10b981', user1: '#6d28d9', user2: '#4f46e5',
  text: '#f1f5f9', textDim: '#64748b', error: '#ef4444',
  toolBg: 'rgba(109,40,217,0.15)', toolBdr: 'rgba(139,92,246,0.3)',
  warn: '#f59e0b',
};

const NODES = Array.from({ length: 16 }, () => ({
  x: Math.random() * W, y: Math.random() * H * 0.55,
  r: 1.5 + Math.random() * 2,
}));

function NeuralBG() {
  const nodes = useMemo(() => NODES, []);
  return (
    <Svg width={W} height={H * 0.6} style={StyleSheet.absoluteFillObject} pointerEvents="none">
      <Defs>
        <RadialGradient id="g1" cx="50%" cy="35%" r="60%">
          <Stop offset="0%" stopColor="#7c3aed" stopOpacity="0.16" />
          <Stop offset="100%" stopColor="#05050f" stopOpacity="0" />
        </RadialGradient>
      </Defs>
      <Rect width={W} height={H * 0.6} fill="url(#g1)" />
      {nodes.map((a, i) => nodes.slice(i + 1).map((b, j) => {
        const d = Math.hypot(a.x - b.x, a.y - b.y);
        if (d > 130) return null;
        return <Line key={`${i}-${j}`} x1={a.x} y1={a.y} x2={b.x} y2={b.y}
                     stroke="#7c3aed" strokeWidth={0.6} strokeOpacity={(130 - d) / 130 * 0.14} />;
      }))}
      {nodes.map((n, i) => <Circle key={i} cx={n.x} cy={n.y} r={n.r} fill="#8b5cf6" fillOpacity={0.5} />)}
    </Svg>
  );
}

function GlowPulse({ color = C.accent, size = 80, style }: any) {
  const pulse = useRef(new Animated.Value(0.4)).current;
  useEffect(() => {
    Animated.loop(Animated.sequence([
      Animated.timing(pulse, { toValue: 1, duration: 1800, useNativeDriver: true }),
      Animated.timing(pulse, { toValue: 0.4, duration: 1800, useNativeDriver: true }),
    ])).start();
  }, []);
  return <Animated.View style={[{ width: size, height: size, borderRadius: size / 2,
    backgroundColor: color, opacity: pulse, position: 'absolute',
    shadowColor: color, shadowRadius: 24, shadowOpacity: 1, shadowOffset: { width: 0, height: 0 },
  }, style]} pointerEvents="none" />;
}

function AIAvatar({ size = 36 }: { size?: number }) {
  return (
    <View style={[styles.aiAvatar, { width: size, height: size, borderRadius: size * 0.28 }]}>
      <LinearGradient colors={[C.glow, C.glow2]} start={{x:0,y:0}} end={{x:1,y:1}} style={StyleSheet.absoluteFill} />
      <MaterialCommunityIcons name="brain" size={size * 0.55} color="#fff" />
    </View>
  );
}

const TOOL_LABELS: Record<string, { icon: string; label: string }> = {
  search:       { icon: 'magnify',              label: 'يبحث في الإنترنت' },
  device_info:  { icon: 'cellphone-cog',        label: 'يفحص الجهاز' },
  javascript:   { icon: 'code-braces',          label: 'ينفّذ كود' },
  mem_save:     { icon: 'content-save',         label: 'يحفظ في الذاكرة' },
  mem_get:      { icon: 'brain',                label: 'يسترجع من الذاكرة' },
  mem_list:     { icon: 'format-list-bulleted', label: 'يراجع الذاكرة' },
};

function LiveStatus({ event }: { event: StepEvent | null }) {
  const fade = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    Animated.timing(fade, { toValue: event ? 1 : 0, duration: 200, useNativeDriver: true }).start();
  }, [event]);

  if (!event) return null;

  let icon = 'dots-horizontal';
  let label = 'يفكر...';

  if (event.type === 'tool_call') {
    const t = TOOL_LABELS[event.tool] || { icon: 'tools', label: event.tool };
    icon = t.icon; label = `${t.label}: ${event.arg.slice(0, 40)}`;
  } else if (event.type === 'tool_result') {
    icon = 'check-circle-outline'; label = 'استلم النتيجة...';
  } else if (event.type === 'thought') {
    icon = 'thought-bubble'; label = event.text;
  } else if (event.type === 'branch_score') {
    icon = 'source-branch'; label = `مسار ${event.branch}: ${(event.score * 100).toFixed(0)}%`;
  }

  return (
    <Animated.View style={[styles.liveStatus, { opacity: fade }]}>
      <MaterialCommunityIcons name={icon as any} size={13} color={C.accentB} />
      <Text style={styles.liveStatusText} numberOfLines={1}>{label}</Text>
    </Animated.View>
  );
}

function AttachmentChip({ name, kind, onRemove }: { name: string; kind: string; onRemove: () => void }) {
  const icons: Record<string, string> = {
    text: 'file-document-outline', pdf: 'file-pdf-box',
    image: 'image-outline', video: 'video-outline', unsupported: 'file-question-outline',
  };
  return (
    <View style={styles.attachChip}>
      <MaterialCommunityIcons name={(icons[kind] || 'paperclip') as any} size={14} color={C.accent} />
      <Text style={styles.attachChipText} numberOfLines={1}>{name}</Text>
      <TouchableOpacity onPress={onRemove} hitSlop={8}>
        <Ionicons name="close-circle" size={16} color={C.textDim} />
      </TouchableOpacity>
    </View>
  );
}

interface Msg {
  id: number; role: 'user' | 'assistant';
  content: string; attachmentName?: string; attachmentKind?: string;
  elapsed?: number; score?: number; warnings?: string[]; isError?: boolean;
  toolsUsed?: string[];
}

function Bubble({ msg }: { msg: Msg }) {
  const isUser = msg.role === 'user';
  const slide = useRef(new Animated.Value(isUser ? 30 : -30)).current;
  const op = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    Animated.parallel([
      Animated.spring(slide, { toValue: 0, speed: 14, bounciness: 4, useNativeDriver: true }),
      Animated.timing(op, { toValue: 1, duration: 300, useNativeDriver: true }),
    ]).start();
  }, []);

  return (
    <Animated.View style={[styles.bubbleRow, isUser && styles.bubbleRowUser,
      { opacity: op, transform: [{ translateX: slide }] }]}>
      {!isUser && <AIAvatar />}
      <View style={[styles.bubble, isUser ? styles.bubbleUser : styles.bubbleAI]}>
        {isUser ? (
          <LinearGradient colors={[C.user1, C.user2]} start={{x:0,y:0}} end={{x:1,y:1}}
                          style={StyleSheet.absoluteFill} borderRadius={18} borderBottomRightRadius={4} />
        ) : (
          <View style={[StyleSheet.absoluteFill, { backgroundColor: 'rgba(255,255,255,0.035)',
            borderRadius: 18, borderBottomLeftRadius: 4 }]} />
        )}

        {msg.attachmentName && (
          <View style={styles.msgAttachTag}>
            <MaterialCommunityIcons name="paperclip" size={11} color={C.accent} />
            <Text style={styles.msgAttachTagText}>{msg.attachmentName}</Text>
          </View>
        )}

        <Text style={[styles.bubbleText, isUser && styles.bubbleTextUser]}>{msg.content}</Text>

        {msg.warnings && msg.warnings.length > 0 && (
          <View style={styles.warnBox}>
            {msg.warnings.map((w, i) => (
              <Text key={i} style={styles.warnText}>⚠ {w}</Text>
            ))}
          </View>
        )}

        {(msg.elapsed || msg.toolsUsed?.length) ? (
          <View style={styles.bubbleMeta}>
            {msg.elapsed && <Text style={styles.metaText}>{msg.elapsed}s</Text>}
            {msg.toolsUsed?.map((t, i) => (
              <View key={i} style={styles.toolTag}>
                <Text style={styles.toolTagText}>{t}</Text>
              </View>
            ))}
          </View>
        ) : null}
      </View>
      {isUser && (
        <View style={styles.userAvatar}>
          <LinearGradient colors={[C.user1, C.user2]} start={{x:0,y:0}} end={{x:1,y:1}}
                          style={StyleSheet.absoluteFill} borderRadius={36} />
          <Ionicons name="person" size={16} color="#fff" />
        </View>
      )}
    </Animated.View>
  );
}

function EmptyState({ modelLoaded, onSettings }: { modelLoaded: boolean; onSettings: () => void }) {
  const logoAnim = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    Animated.spring(logoAnim, { toValue: 1, speed: 3, bounciness: 12, useNativeDriver: true }).start();
  }, []);

  return (
    <View style={styles.emptyWrap}>
      <Animated.View style={{ transform: [{ scale: logoAnim }], opacity: logoAnim, alignItems: 'center' }}>
        <View style={styles.logoBg}>
          <GlowPulse color={C.glow} size={100} style={{ top: -12, left: -12 }} />
          <GlowPulse color={C.glow2} size={70} style={{ bottom: -8, right: -8 }} />
          <LinearGradient colors={[C.glow, C.glow2]} start={{x:0,y:0}} end={{x:1,y:1}}
                          style={[StyleSheet.absoluteFill, { borderRadius: 28 }]} />
          <MaterialCommunityIcons name="brain" size={52} color="#fff" />
        </View>
        <Text style={styles.emptyTitle}>GVR-Agent</Text>
        <Text style={styles.emptySub}>
          {modelLoaded
            ? 'اكتب طلبك، أو أرفق أي ملف\nالوكيل يقرر بنفسه إيه اللي محتاجه'
            : 'محتاج تحمّل نموذج الأول'}
        </Text>
        {!modelLoaded && (
          <TouchableOpacity style={styles.loadModelBtn} onPress={onSettings}>
            <LinearGradient colors={[C.user1, C.glow2]} start={{x:0,y:0}} end={{x:1,y:1}}
                            style={[StyleSheet.absoluteFill, { borderRadius: 14 }]} />
            <MaterialCommunityIcons name="download" size={16} color="#fff" />
            <Text style={styles.loadModelBtnText}>تحميل نموذج</Text>
          </TouchableOpacity>
        )}
      </Animated.View>
    </View>
  );
}

export default function App() {
  const [msgs, setMsgs]           = useState<Msg[]>([]);
  const [input, setInput]         = useState('');
  const [loading, setLoading]     = useState(false);
  const [liveEvent, setLiveEvent] = useState<StepEvent | null>(null);
  const [modelReady, setModelReady] = useState(false);
  const [visionReady, setVisionReadyState] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [pendingAttachment, setPendingAttachment] = useState<PreparedAttachment | null>(null);
  const [attaching, setAttaching] = useState(false);
  const [localModels, setLocalModels] = useState<ModelInfo[]>([]);
  const [modelLoading, setModelLoading] = useState(false);
  const [modelLoadPct, setModelLoadPct] = useState(0);

  const listRef = useRef<FlatList>(null);

  useEffect(() => {
    (async () => {
      const h = await AsyncStorage.getItem('gvr_msgs');
      if (h) setMsgs(JSON.parse(h));
      const models = await listLocalModels();
      setLocalModels(models);
      setModelReady(isModelLoaded());
      setVisionReadyState(isVisionReady());
    })();
  }, []);

  const save = async (m: Msg[]) => AsyncStorage.setItem('gvr_msgs', JSON.stringify(m.slice(-80)));

  const handleAttach = useCallback(async () => {
    try {
      setAttaching(true);
      const asset = await pickAnyFile();
      if (!asset) { setAttaching(false); return; }

      if (!modelReady) {
        Alert.alert('لا يوجد نموذج', 'حمّل نموذج الأول قبل إرفاق الملفات.');
        setAttaching(false);
        return;
      }

      const prepared = await prepareAttachment(asset);
      setPendingAttachment(prepared);
    } catch (e: any) {
      Alert.alert('خطأ في الملف', e.message);
    } finally {
      setAttaching(false);
    }
  }, [modelReady]);

  const send = useCallback(async (text?: string) => {
    const msg = (text || input).trim();
    if ((!msg && !pendingAttachment) || loading || !modelReady) return;

    Keyboard.dismiss();
    setInput('');
    const attachment = pendingAttachment;
    setPendingAttachment(null);

    const userMsg: Msg = {
      id: Date.now(), role: 'user',
      content: msg || `[أرسل ${attachment?.name}]`,
      attachmentName: attachment?.name, attachmentKind: attachment?.kind,
    };
    const next = [...msgs, userMsg];
    setMsgs(next);
    setLoading(true);
    setLiveEvent({ type: 'thought', text: 'يفكر...' });

    try {
      const res = await runWithAttachment(
        msg || 'صف/حلل المرفق ده',
        attachment,
        (e) => setLiveEvent(e),
        5
      );

      const toolsUsed = [...new Set(res.steps.map(s => s.tool))];
      const aiMsg: Msg = {
        id: Date.now() + 1, role: 'assistant',
        content: res.answer,
        elapsed: res.elapsed,
        score: res.score,
        warnings: res.attachmentWarnings,
        toolsUsed: toolsUsed.length ? toolsUsed : undefined,
      };
      const final = [...next, aiMsg];
      setMsgs(final);
      save(final);
    } catch (err: any) {
      const errMsg: Msg = {
        id: Date.now() + 1, role: 'assistant',
        content: `❌ ${err.message}`, isError: true,
      };
      setMsgs([...next, errMsg]);
    } finally {
      setLoading(false);
      setLiveEvent(null);
      setTimeout(() => listRef.current?.scrollToEnd({ animated: true }), 100);
    }
  }, [input, msgs, loading, modelReady, pendingAttachment]);

  const clearAll = () => Alert.alert('مسح المحادثة', 'هتتمسح كل الرسائل؟', [
    { text: 'إلغاء', style: 'cancel' },
    { text: 'مسح', style: 'destructive', onPress: async () => {
      setMsgs([]); await AsyncStorage.removeItem('gvr_msgs');
    }},
  ]);

  const handleImportModel = async () => {
    try {
      setModelLoading(true);
      const model = await importModelFromDevice();
      if (!model) { setModelLoading(false); return; }
      setLocalModels(await listLocalModels());
      await loadModel(model, {}, (pct) => setModelLoadPct(pct));
      setModelReady(true);
    } catch (e: any) {
      Alert.alert('فشل تحميل النموذج', e.message);
    } finally {
      setModelLoading(false);
      setModelLoadPct(0);
    }
  };

  const handleSelectModel = async (model: ModelInfo) => {
    try {
      setModelLoading(true);
      await loadModel(model, {}, (pct) => setModelLoadPct(pct));
      setModelReady(true);
      setShowSettings(false);
    } catch (e: any) {
      Alert.alert('فشل تحميل النموذج', e.message);
    } finally {
      setModelLoading(false);
      setModelLoadPct(0);
    }
  };

  return (
    <View style={styles.root}>
      <StatusBar barStyle="light-content" backgroundColor="#05050f" translucent />
      <NeuralBG />

      <SafeAreaView style={{ flex: 1 }} edges={['top']}>

        <BlurView intensity={40} tint="dark" style={styles.header}>
          <LinearGradient colors={['rgba(109,40,217,0.15)','rgba(6,182,212,0.05)']}
                          start={{x:0,y:0}} end={{x:1,y:1}} style={StyleSheet.absoluteFill} />
          <View style={styles.headerLeft}>
            <AIAvatar size={32} />
            <View>
              <Text style={styles.headerTitle}>GVR-Agent</Text>
              <View style={styles.statusRow}>
                <View style={[styles.statusDot, { backgroundColor: modelReady ? C.green : C.textDim }]} />
                <Text style={styles.statusText}>
                  {modelReady ? (visionReady ? 'نص + رؤية' : 'نص فقط') : 'مفيش نموذج'}
                </Text>
              </View>
            </View>
          </View>
          <View style={styles.headerRight}>
            <TouchableOpacity onPress={clearAll} style={styles.hBtn}>
              <Ionicons name="trash-outline" size={19} color={C.textDim} />
            </TouchableOpacity>
            <TouchableOpacity onPress={() => setShowSettings(true)} style={styles.hBtn}>
              <Ionicons name="settings-outline" size={19} color={C.textDim} />
            </TouchableOpacity>
          </View>
        </BlurView>

        <FlatList
          ref={listRef}
          data={msgs}
          keyExtractor={m => m.id.toString()}
          renderItem={({ item }) => <Bubble msg={item} />}
          contentContainerStyle={[styles.list, msgs.length === 0 && { flex: 1 }]}
          ListEmptyComponent={<EmptyState modelLoaded={modelReady} onSettings={() => setShowSettings(true)} />}
          showsVerticalScrollIndicator={false}
          onContentSizeChange={() => listRef.current?.scrollToEnd({ animated: true })}
        />

        {loading && (
          <View style={styles.loadingRow}>
            <AIAvatar size={28} />
            <View style={styles.liveStatusWrap}>
              <LiveStatus event={liveEvent} />
            </View>
          </View>
        )}

        <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
          {pendingAttachment && (
            <View style={styles.attachPreviewRow}>
              <AttachmentChip
                name={pendingAttachment.name}
                kind={pendingAttachment.kind}
                onRemove={() => setPendingAttachment(null)}
              />
            </View>
          )}

          <BlurView intensity={60} tint="dark" style={styles.inputBar}>
            <LinearGradient colors={['rgba(109,40,217,0.1)','rgba(6,182,212,0.05)']}
                            start={{x:0,y:0}} end={{x:1,y:1}} style={StyleSheet.absoluteFill} />
            <View style={styles.inputWrap}>
              <TouchableOpacity
                style={styles.attachBtn}
                onPress={handleAttach}
                disabled={attaching || !modelReady}
              >
                {attaching
                  ? <ActivityIndicator size={16} color={C.accent} />
                  : <Ionicons name="attach" size={22} color={modelReady ? C.accent : C.textDim} />
                }
              </TouchableOpacity>

              <TextInput
                style={styles.input}
                value={input}
                onChangeText={setInput}
                placeholder={modelReady ? 'اكتب طلبك...' : 'حمّل نموذج الأول من الإعدادات'}
                placeholderTextColor={C.textDim}
                multiline
                maxLength={4000}
                editable={modelReady}
              />

              <TouchableOpacity
                style={[styles.sendBtn, ((!input.trim() && !pendingAttachment) || loading || !modelReady) && styles.sendBtnOff]}
                onPress={() => send()}
                disabled={(!input.trim() && !pendingAttachment) || loading || !modelReady}
              >
                <LinearGradient colors={[C.user1, C.glow2]} start={{x:0,y:0}} end={{x:1,y:1}}
                                style={[StyleSheet.absoluteFill, { borderRadius: 22 }]} />
                {loading
                  ? <ActivityIndicator size={18} color="#fff" />
                  : <Ionicons name="arrow-up" size={20} color="#fff" />
                }
              </TouchableOpacity>
            </View>
          </BlurView>
        </KeyboardAvoidingView>

        <Modal visible={showSettings} transparent animationType="slide" onRequestClose={() => setShowSettings(false)}>
          <TouchableWithoutFeedback onPress={() => setShowSettings(false)}>
            <View style={styles.modalDim} />
          </TouchableWithoutFeedback>
          <BlurView intensity={80} tint="dark" style={styles.modal}>
            <LinearGradient colors={['rgba(109,40,217,0.2)','rgba(6,182,212,0.05)']}
                            start={{x:0,y:0}} end={{x:1,y:1}} style={StyleSheet.absoluteFill} />
            <View style={styles.modalHandle} />
            <Text style={styles.modalTitle}>النماذج</Text>

            <ScrollView style={{ maxHeight: H * 0.5 }}>
              {localModels.length === 0 && (
                <Text style={styles.noModelsText}>مفيش نماذج محمّلة بعد</Text>
              )}
              {localModels.map((m) => (
                <TouchableOpacity key={m.uri} style={styles.modelRow} onPress={() => handleSelectModel(m)}>
                  <MaterialCommunityIcons name="cube-outline" size={20} color={C.accent} />
                  <View style={{ flex: 1 }}>
                    <Text style={styles.modelName} numberOfLines={1}>{m.name}</Text>
                    <Text style={styles.modelSize}>{m.sizeGB} GB</Text>
                  </View>
                  <Ionicons name="chevron-forward" size={18} color={C.textDim} />
                </TouchableOpacity>
              ))}
            </ScrollView>

            {modelLoading && (
              <View style={styles.progressBox}>
                <ActivityIndicator size="small" color={C.accent} />
                <Text style={styles.progressText}>
                  جارٍ التحميل... {Math.round(modelLoadPct * 100)}%
                </Text>
              </View>
            )}

            <TouchableOpacity style={styles.importBtn} onPress={handleImportModel} disabled={modelLoading}>
              <MaterialCommunityIcons name="file-import-outline" size={18} color={C.accentB} />
              <Text style={styles.importBtnText}>استيراد نموذج GGUF من الجهاز</Text>
            </TouchableOpacity>

            <View style={styles.infoBox}>
              <MaterialCommunityIcons name="information-outline" size={14} color={C.textDim} />
              <Text style={styles.infoText}>
                يدعم أي نموذج بصيغة .gguf — Qwen (علي بابا)، DeepSeek، Llama، وغيرهم.{'\n'}
                للرؤية (تحليل الصور/الفيديو) لازم نموذج multimodal معاه ملف mmproj مطابق.
              </Text>
            </View>

            <TouchableOpacity style={styles.closeBtn} onPress={() => setShowSettings(false)}>
              <LinearGradient colors={[C.user1, C.glow2]} start={{x:0,y:0}} end={{x:1,y:1}}
                              style={[StyleSheet.absoluteFill, { borderRadius: 14 }]} />
              <Text style={styles.closeBtnText}>حسناً</Text>
            </TouchableOpacity>
          </BlurView>
        </Modal>

      </SafeAreaView>
    </View>
  );
}

const styles = StyleSheet.create({
  root:            { flex:1, backgroundColor: C.bg },
  header:          { flexDirection:'row', justifyContent:'space-between', alignItems:'center',
                     paddingHorizontal:16, paddingTop:4, paddingBottom:12,
                     borderBottomWidth:1, borderBottomColor: C.border, overflow:'hidden' },
  headerLeft:      { flexDirection:'row', alignItems:'center', gap:10 },
  headerTitle:     { color:'#f1f5f9', fontSize:16, fontWeight:'800', letterSpacing:-0.3 },
  statusRow:       { flexDirection:'row', alignItems:'center', gap:5, marginTop:1 },
  statusDot:       { width:6, height:6, borderRadius:3 },
  statusText:      { color: C.textDim, fontSize:11 },
  headerRight:     { flexDirection:'row', gap:2 },
  hBtn:            { padding:8 },
  list:            { padding:16, gap:4 },
  bubbleRow:       { flexDirection:'row', alignItems:'flex-end', gap:8, marginBottom:12 },
  bubbleRowUser:   { flexDirection:'row-reverse' },
  aiAvatar:        { alignItems:'center', justifyContent:'center', overflow:'hidden', flexShrink:0 },
  userAvatar:      { width:32, height:32, borderRadius:32, alignItems:'center',
                     justifyContent:'center', overflow:'hidden', flexShrink:0 },
  bubble:          { maxWidth: W * 0.76, borderRadius:18, overflow:'hidden', padding:12, paddingBottom:8 },
  bubbleUser:      { borderBottomRightRadius:4 },
  bubbleAI:        { borderBottomLeftRadius:4, borderWidth:1, borderColor: C.border },
  bubbleText:      { color:'rgba(255,255,255,0.82)', fontSize:15, lineHeight:23 },
  bubbleTextUser:  { color:'#fff' },
  msgAttachTag:    { flexDirection:'row', alignItems:'center', gap:4, marginBottom:6,
                     backgroundColor:'rgba(139,92,246,0.15)', alignSelf:'flex-start',
                     paddingHorizontal:8, paddingVertical:3, borderRadius:8 },
  msgAttachTagText:{ color: C.accent, fontSize:11 },
  bubbleMeta:      { flexDirection:'row', flexWrap:'wrap', gap:6, marginTop:6, alignItems:'center' },
  metaText:        { color:'rgba(255,255,255,0.25)', fontSize:11 },
  toolTag:         { backgroundColor:'rgba(6,182,212,0.15)', borderRadius:8, paddingHorizontal:7, paddingVertical:2 },
  toolTagText:     { color: C.accentB, fontSize:10, fontWeight:'600' },
  warnBox:         { marginTop:8, padding:8, backgroundColor:'rgba(245,158,11,0.1)',
                     borderRadius:8, borderWidth:1, borderColor:'rgba(245,158,11,0.25)' },
  warnText:        { color:'#fbbf24', fontSize:11, lineHeight:16, marginBottom:2 },
  liveStatusWrap:  { flex:1 },
  liveStatus:      { flexDirection:'row', alignItems:'center', gap:6,
                     backgroundColor:'rgba(6,182,212,0.1)', paddingHorizontal:12, paddingVertical:8,
                     borderRadius:14, borderWidth:1, borderColor:'rgba(6,182,212,0.2)', alignSelf:'flex-start' },
  liveStatusText:  { color: C.accentB, fontSize:12, flexShrink:1 },
  loadingRow:      { flexDirection:'row', alignItems:'center', gap:8, paddingHorizontal:16, paddingBottom:8 },
  attachPreviewRow:{ paddingHorizontal:14, paddingTop:8 },
  attachChip:      { flexDirection:'row', alignItems:'center', gap:6, alignSelf:'flex-start',
                     backgroundColor:'rgba(139,92,246,0.15)', borderRadius:12,
                     paddingHorizontal:10, paddingVertical:6, borderWidth:1, borderColor: C.toolBdr, maxWidth: W*0.7 },
  attachChipText:  { color: C.text, fontSize:12, flexShrink:1 },
  inputBar:        { borderTopWidth:1, borderTopColor: C.border,
                     paddingHorizontal:12, paddingTop:10, paddingBottom:8, overflow:'hidden' },
  inputWrap:       { flexDirection:'row', alignItems:'flex-end', gap:8 },
  attachBtn:       { width:40, height:40, alignItems:'center', justifyContent:'center' },
  input:           { flex:1, color:'#f1f5f9', fontSize:15, maxHeight:120,
                     backgroundColor:'rgba(255,255,255,0.06)', borderRadius:24,
                     paddingHorizontal:16, paddingVertical:11, borderWidth:1, borderColor: C.border, lineHeight:22 },
  sendBtn:         { width:44, height:44, borderRadius:22, alignItems:'center', justifyContent:'center',
                     overflow:'hidden', shadowColor: C.glow, shadowRadius:12, shadowOpacity:0.6,
                     shadowOffset:{width:0,height:0} },
  sendBtnOff:      { opacity:0.3 },
  logoBg:          { width:88, height:88, borderRadius:28, alignItems:'center', justifyContent:'center',
                     overflow:'hidden', marginBottom:20, shadowColor: C.glow, shadowRadius:30,
                     shadowOpacity:0.6, shadowOffset:{width:0,height:0} },
  emptyWrap:       { flex:1, alignItems:'center', justifyContent:'center', paddingHorizontal:24 },
  emptyTitle:      { color:'#f1f5f9', fontSize:30, fontWeight:'900', letterSpacing:-0.5, marginBottom:10 },
  emptySub:        { color: C.textDim, fontSize:14, textAlign:'center', lineHeight:22 },
  loadModelBtn:    { flexDirection:'row', alignItems:'center', gap:8, marginTop:20,
                     paddingHorizontal:20, paddingVertical:12, borderRadius:14, overflow:'hidden' },
  loadModelBtnText:{ color:'#fff', fontWeight:'700', fontSize:14 },
  modalDim:        { flex:1, backgroundColor:'rgba(0,0,0,0.7)' },
  modal:           { borderTopLeftRadius:28, borderTopRightRadius:28, padding:24, paddingBottom:40,
                     overflow:'hidden', borderTopWidth:1, borderTopColor: C.border },
  modalHandle:     { width:40, height:4, backgroundColor: C.border, borderRadius:2, alignSelf:'center', marginBottom:20 },
  modalTitle:      { color:'#f1f5f9', fontSize:20, fontWeight:'800', marginBottom:16 },
  noModelsText:    { color: C.textDim, fontSize:13, textAlign:'center', paddingVertical:20 },
  modelRow:        { flexDirection:'row', alignItems:'center', gap:12, paddingVertical:12,
                     borderBottomWidth:1, borderBottomColor: C.border },
  modelName:       { color:'#f1f5f9', fontSize:14, fontWeight:'600' },
  modelSize:       { color: C.textDim, fontSize:12, marginTop:2 },
  progressBox:     { flexDirection:'row', alignItems:'center', gap:8, marginTop:12,
                     backgroundColor:'rgba(139,92,246,0.1)', padding:10, borderRadius:10 },
  progressText:    { color: C.accent, fontSize:12 },
  importBtn:       { flexDirection:'row', alignItems:'center', gap:8, marginTop:14, padding:12,
                     borderRadius:12, borderWidth:1, borderColor:'rgba(6,182,212,0.25)',
                     backgroundColor:'rgba(6,182,212,0.06)' },
  importBtnText:   { color: C.accentB, fontSize:13, fontWeight:'600' },
  infoBox:         { flexDirection:'row', gap:8, marginTop:16, padding:12,
                     backgroundColor:'rgba(255,255,255,0.03)', borderRadius:10 },
  infoText:        { color: C.textDim, fontSize:11, lineHeight:17, flex:1 },
  closeBtn:        { borderRadius:14, padding:15, alignItems:'center', marginTop:20, overflow:'hidden' },
  closeBtnText:    { color:'#fff', fontWeight:'800', fontSize:16 },
});
