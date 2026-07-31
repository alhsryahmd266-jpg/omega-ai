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
import axios from 'axios';

const { width: W, height: H } = Dimensions.get('window');

/* ── PALETTE ────────────────────────────────────────────────────────────────── */
const C = {
  bg:       '#05050f',
  surface:  'rgba(255,255,255,0.04)',
  border:   'rgba(255,255,255,0.08)',
  glow:     '#7c3aed',
  glow2:    '#06b6d4',
  accent:   '#8b5cf6',
  accentB:  '#06b6d4',
  green:    '#10b981',
  user1:    '#6d28d9',
  user2:    '#4f46e5',
  text:     '#f1f5f9',
  textDim:  '#64748b',
  error:    '#ef4444',
  toolBg:   'rgba(109,40,217,0.15)',
  toolBdr:  'rgba(139,92,246,0.3)',
};

/* ── API ─────────────────────────────────────────────────────────────────────── */
let API_URL = 'http://localhost:5000';
const api = () => axios.create({ baseURL: API_URL, timeout: 120000 });

async function sendMsg(message: string, mode: string) {
  try {
    const r = await api().post('/chat', { message, mode });
    return r.data;
  } catch (e: any) {
    if (e.code === 'ECONNREFUSED' || e.code === 'ERR_NETWORK')
      throw new Error('Server offline.\n\nOpen Termux and run:\npython ~/gvr-agent/server.py');
    throw new Error(e.response?.data?.error || e.message);
  }
}

async function checkHealth() {
  try {
    const r = await api().get('/health', { timeout: 3000 });
    return r.data;
  } catch { return null; }
}

/* ── ANIMATED NEURAL BACKGROUND ─────────────────────────────────────────────── */
const NODES = Array.from({ length: 18 }, (_, i) => ({
  id: i,
  x: Math.random() * W,
  y: Math.random() * H * 0.6,
  r: 1.5 + Math.random() * 2,
  speed: 0.2 + Math.random() * 0.3,
  phase: Math.random() * Math.PI * 2,
}));

function NeuralBG() {
  const tick = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    Animated.loop(
      Animated.timing(tick, { toValue: 1, duration: 8000, useNativeDriver: false })
    ).start();
  }, []);

  const nodes = useMemo(() => NODES, []);

  return (
    <Svg width={W} height={H * 0.65} style={StyleSheet.absoluteFillObject} pointerEvents="none">
      <Defs>
        <RadialGradient id="g1" cx="50%" cy="40%" r="60%">
          <Stop offset="0%" stopColor="#7c3aed" stopOpacity="0.18" />
          <Stop offset="100%" stopColor="#05050f" stopOpacity="0" />
        </RadialGradient>
        <RadialGradient id="g2" cx="80%" cy="20%" r="40%">
          <Stop offset="0%" stopColor="#06b6d4" stopOpacity="0.10" />
          <Stop offset="100%" stopColor="#05050f" stopOpacity="0" />
        </RadialGradient>
      </Defs>
      <Rect width={W} height={H * 0.65} fill="url(#g1)" />
      <Rect width={W} height={H * 0.65} fill="url(#g2)" />
      {nodes.map((a, i) =>
        nodes.slice(i + 1).map((b, j) => {
          const dist = Math.hypot(a.x - b.x, a.y - b.y);
          if (dist > 140) return null;
          const op = ((140 - dist) / 140) * 0.15;
          return (
            <Line key={`${i}-${j}`} x1={a.x} y1={a.y} x2={b.x} y2={b.y}
                  stroke="#7c3aed" strokeWidth={0.6} strokeOpacity={op} />
          );
        })
      )}
      {nodes.map(n => (
        <Circle key={n.id} cx={n.x} cy={n.y} r={n.r}
                fill="#8b5cf6" fillOpacity={0.5} />
      ))}
    </Svg>
  );
}

/* ── GLOW PULSE ──────────────────────────────────────────────────────────────── */
function GlowPulse({ color = C.accent, size = 80, style }: any) {
  const pulse = useRef(new Animated.Value(0.4)).current;
  useEffect(() => {
    Animated.loop(Animated.sequence([
      Animated.timing(pulse, { toValue: 1, duration: 1800, useNativeDriver: true }),
      Animated.timing(pulse, { toValue: 0.4, duration: 1800, useNativeDriver: true }),
    ])).start();
  }, []);
  return (
    <Animated.View
      style={[{ width: size, height: size, borderRadius: size / 2,
        backgroundColor: color, opacity: pulse,
        shadowColor: color, shadowRadius: 24, shadowOpacity: 1,
        shadowOffset: { width: 0, height: 0 },
        position: 'absolute',
      }, style]}
      pointerEvents="none"
    />
  );
}

/* ── TYPING ANIMATION ────────────────────────────────────────────────────────── */
function TypingIndicator() {
  const dots = [0, 1, 2].map(() => useRef(new Animated.Value(0)).current);
  useEffect(() => {
    const anims = dots.map((d, i) =>
      Animated.loop(Animated.sequence([
        Animated.delay(i * 160),
        Animated.spring(d, { toValue: 1, speed: 12, bounciness: 8, useNativeDriver: true }),
        Animated.spring(d, { toValue: 0, speed: 12, bounciness: 0, useNativeDriver: true }),
      ]))
    );
    Animated.parallel(anims).start();
  }, []);
  return (
    <View style={styles.typingWrap}>
      <View style={styles.typingBubble}>
        <LinearGradient colors={['rgba(139,92,246,0.15)','rgba(6,182,212,0.08)']}
                        start={{ x: 0, y: 0 }} end={{ x: 1, y: 1 }}
                        style={StyleSheet.absoluteFill} />
        {dots.map((d, i) => (
          <Animated.View key={i} style={[styles.dot, {
            transform: [{ translateY: d.interpolate({ inputRange:[0,1], outputRange:[0,-6] }) }],
            backgroundColor: i === 1 ? C.accentB : C.accent,
          }]} />
        ))}
      </View>
    </View>
  );
}

/* ── AVATAR ──────────────────────────────────────────────────────────────────── */
function AIAvatar({ size = 36 }: { size?: number }) {
  return (
    <View style={[styles.aiAvatar, { width: size, height: size, borderRadius: size * 0.28 }]}>
      <LinearGradient colors={[C.glow, C.glow2]}
                      start={{ x: 0, y: 0 }} end={{ x: 1, y: 1 }}
                      style={StyleSheet.absoluteFill} />
      <MaterialCommunityIcons name="brain" size={size * 0.55} color="#fff" />
    </View>
  );
}

/* ── TOOL STEP CARD ──────────────────────────────────────────────────────────── */
function ToolStep({ step, index }: any) {
  const [open, setOpen] = useState(false);
  const icons: any = { terminal: 'terminal', python: 'language-python',
                        search: 'magnify', file_read: 'file-eye', default: 'code-brackets' };
  const icon = icons[step.tool] || icons.default;
  return (
    <TouchableOpacity style={styles.toolStep} onPress={() => setOpen(!open)} activeOpacity={0.8}>
      <LinearGradient colors={['rgba(109,40,217,0.2)','rgba(6,182,212,0.08)']}
                      start={{ x: 0, y: 0 }} end={{ x: 1, y: 1 }}
                      style={StyleSheet.absoluteFill} />
      <View style={styles.toolStepHeader}>
        <MaterialCommunityIcons name={icon} size={13} color={C.accent} />
        <Text style={styles.toolStepName}>{step.tool.toUpperCase()}</Text>
        <Text style={styles.toolStepIdx}>#{index + 1}</Text>
        <Ionicons name={open ? 'chevron-up' : 'chevron-down'} size={12} color={C.textDim} style={{ marginLeft: 'auto' }} />
      </View>
      <Text style={styles.toolStepArg} numberOfLines={open ? 10 : 2}>{step.arg}</Text>
      {open && step.obs && (
        <View style={styles.toolStepObs}>
          <Text style={styles.toolStepObsLabel}>OUTPUT</Text>
          <Text style={styles.toolStepObsText}>{step.obs}</Text>
        </View>
      )}
    </TouchableOpacity>
  );
}

/* ── MESSAGE BUBBLE ──────────────────────────────────────────────────────────── */
function Bubble({ msg }: { msg: any }) {
  const isUser = msg.role === 'user';
  const slideAnim = useRef(new Animated.Value(isUser ? 30 : -30)).current;
  const opAnim = useRef(new Animated.Value(0)).current;
  const [stepsOpen, setStepsOpen] = useState(false);

  useEffect(() => {
    Animated.parallel([
      Animated.spring(slideAnim, { toValue: 0, speed: 14, bounciness: 4, useNativeDriver: true }),
      Animated.timing(opAnim, { toValue: 1, duration: 350, useNativeDriver: true }),
    ]).start();
  }, []);

  return (
    <Animated.View style={[
      styles.bubbleRow,
      isUser && styles.bubbleRowUser,
      { opacity: opAnim, transform: [{ translateX: slideAnim }] },
    ]}>
      {!isUser && <AIAvatar />}

      <View style={[styles.bubble, isUser ? styles.bubbleUser : styles.bubbleAI]}>
        {isUser ? (
          <LinearGradient colors={[C.user1, C.user2]} start={{ x: 0, y: 0 }} end={{ x: 1, y: 1 }}
                          style={StyleSheet.absoluteFill} borderRadius={18} borderBottomRightRadius={4} />
        ) : (
          <View style={[StyleSheet.absoluteFill, {
            backgroundColor: 'rgba(255,255,255,0.035)',
            borderRadius: 18, borderBottomLeftRadius: 4,
          }]} />
        )}

        {/* Tool steps toggle */}
        {msg.steps?.length > 0 && (
          <TouchableOpacity style={styles.stepsToggle} onPress={() => setStepsOpen(!stepsOpen)}>
            <MaterialCommunityIcons name="tools" size={12} color={C.accent} />
            <Text style={styles.stepsToggleText}>{msg.steps.length} action{msg.steps.length > 1 ? 's' : ''}</Text>
            <Ionicons name={stepsOpen ? 'chevron-up' : 'chevron-down'} size={11} color={C.textDim} />
          </TouchableOpacity>
        )}
        {stepsOpen && msg.steps.map((s: any, i: number) => <ToolStep key={i} step={s} index={i} />)}

        <Text style={[styles.bubbleText, isUser && styles.bubbleTextUser]}>
          {msg.content}
        </Text>

        <View style={styles.bubbleMeta}>
          {msg.elapsed && <Text style={styles.metaText}>{msg.elapsed}s</Text>}
          {msg.score != null && (
            <View style={[styles.scorePill, { backgroundColor: msg.score >= 0.7 ? 'rgba(16,185,129,0.2)' : 'rgba(239,68,68,0.2)' }]}>
              <Text style={[styles.scoreText, { color: msg.score >= 0.7 ? C.green : C.error }]}>
                {msg.score >= 0.7 ? '✓' : '~'} {Math.round(msg.score * 100)}%
              </Text>
            </View>
          )}
        </View>
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

/* ── EMPTY STATE ─────────────────────────────────────────────────────────────── */
function EmptyState({ onQuick }: { onQuick: (t: string) => void }) {
  const SUGGESTIONS = [
    '🔍 ابحث عن آخر أخبار الذكاء الاصطناعي',
    '💻 اكتب كود Python لفرز قائمة',
    '📁 اعرض ملفات الهاتف',
    '🧮 احسب مسألة رياضية معقدة',
    '🌐 ابحث عن أفضل نماذج AI مجانية',
  ];
  const logoAnim = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    Animated.spring(logoAnim, { toValue: 1, speed: 3, bounciness: 12, useNativeDriver: true }).start();
  }, []);

  return (
    <ScrollView contentContainerStyle={styles.emptyWrap} showsVerticalScrollIndicator={false}>
      <Animated.View style={{
        transform: [{ scale: logoAnim }], opacity: logoAnim,
        alignItems: 'center', marginBottom: 32,
      }}>
        <View style={styles.logoBg}>
          <GlowPulse color={C.glow} size={100} style={{ top: -12, left: -12 }} />
          <GlowPulse color={C.glow2} size={70} style={{ bottom: -8, right: -8 }} />
          <LinearGradient colors={[C.glow, C.glow2]} start={{x:0,y:0}} end={{x:1,y:1}}
                          style={[StyleSheet.absoluteFill, { borderRadius: 28 }]} />
          <MaterialCommunityIcons name="brain" size={52} color="#fff" />
        </View>
        <Text style={styles.emptyTitle}>GVR-Agent</Text>
        <Text style={styles.emptySub}>
          ذكاء اصطناعي محلي · DeepSeek-R1-14B{'\n'}
          أدوات حقيقية · يعمل بدون إنترنت
        </Text>
      </Animated.View>

      <View style={styles.suggestGrid}>
        {SUGGESTIONS.map((s, i) => (
          <TouchableOpacity key={i} style={styles.suggestCard} onPress={() => onQuick(s)}
                            activeOpacity={0.7}>
            <LinearGradient colors={['rgba(139,92,246,0.12)','rgba(6,182,212,0.06)']}
                            start={{x:0,y:0}} end={{x:1,y:1}}
                            style={StyleSheet.absoluteFill} borderRadius={14} />
            <View style={styles.suggestBorder} />
            <Text style={styles.suggestText}>{s}</Text>
          </TouchableOpacity>
        ))}
      </View>
    </ScrollView>
  );
}

/* ── MAIN APP ─────────────────────────────────────────────────────────────────── */
type Mode = 'agent' | 'gvr' | 'chat';
interface Msg {
  id: number; role: 'user' | 'assistant';
  content: string; steps?: any[];
  elapsed?: number; score?: number; isError?: boolean;
}

export default function App() {
  const [msgs, setMsgs]         = useState<Msg[]>([]);
  const [input, setInput]       = useState('');
  const [loading, setLoading]   = useState(false);
  const [mode, setMode]         = useState<Mode>('agent');
  const [online, setOnline]     = useState<boolean | null>(null);
  const [showMenu, setShowMenu] = useState(false);
  const [modelInfo, setModelInfo] = useState<any>(null);

  const listRef = useRef<FlatList>(null);

  useEffect(() => {
    (async () => {
      const h = await AsyncStorage.getItem('msgs');
      if (h) setMsgs(JSON.parse(h));
    })();
    pingServer();
  }, []);

  const pingServer = async () => {
    const h = await checkHealth();
    setOnline(!!h);
    if (h) setModelInfo(h);
  };

  const save = async (m: Msg[]) => {
    await AsyncStorage.setItem('msgs', JSON.stringify(m.slice(-80)));
  };

  const send = useCallback(async (text?: string) => {
    const msg = text || input;
    if (!msg.trim() || loading) return;
    Keyboard.dismiss();
    setInput('');

    const userMsg: Msg = { id: Date.now(), role: 'user', content: msg };
    const next = [...msgs, userMsg];
    setMsgs(next);
    setLoading(true);

    try {
      const res = await sendMsg(msg, mode);
      const aiMsg: Msg = {
        id: Date.now() + 1, role: 'assistant',
        content: res.answer || '',
        steps: res.steps || [],
        elapsed: res.elapsed,
        score: res.score,
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
      if (!online) setShowMenu(true);
    } finally {
      setLoading(false);
      setTimeout(() => listRef.current?.scrollToEnd({ animated: true }), 100);
    }
  }, [input, msgs, mode, loading, online]);

  const clearAll = () => Alert.alert('مسح المحادثة', 'هتتمسح كل الرسائل?', [
    { text: 'إلغاء', style: 'cancel' },
    { text: 'مسح', style: 'destructive', onPress: async () => {
      setMsgs([]); await AsyncStorage.removeItem('msgs');
    }},
  ]);

  const MODE_DATA: { key: Mode; label: string; icon: string; desc: string }[] = [
    { key: 'agent', label: 'Agent',  icon: 'robot',           desc: 'يستخدم أدوات تلقائياً' },
    { key: 'gvr',   label: 'GVR',    icon: 'autorenew',       desc: 'يراجع ويصحح نفسه' },
    { key: 'chat',  label: 'Chat',   icon: 'chat-processing', desc: 'محادثة سريعة مباشرة' },
  ];

  return (
    <View style={styles.root}>
      <StatusBar barStyle="light-content" backgroundColor="#05050f" translucent />
      <NeuralBG />

      <SafeAreaView style={{ flex: 1 }} edges={['top']}>

        {/* ── HEADER ── */}
        <BlurView intensity={40} tint="dark" style={styles.header}>
          <LinearGradient
            colors={['rgba(109,40,217,0.15)','rgba(6,182,212,0.05)']}
            start={{x:0,y:0}} end={{x:1,y:1}}
            style={StyleSheet.absoluteFill}
          />
          <View style={styles.headerLeft}>
            <AIAvatar size={34} />
            <View>
              <Text style={styles.headerTitle}>GVR-Agent</Text>
              <View style={styles.statusRow}>
                <View style={[styles.statusDot,
                  { backgroundColor: online === null ? C.textDim : online ? C.green : C.error }]} />
                <Text style={styles.statusText}>
                  {online === null ? 'جارٍ الاتصال...' : online
                    ? `متصل · ${modelInfo?.model_size_gb || '?'} GB`
                    : 'غير متصل'}
                </Text>
              </View>
            </View>
          </View>
          <View style={styles.headerRight}>
            <TouchableOpacity onPress={pingServer} style={styles.hBtn}>
              <Ionicons name="refresh" size={20} color={C.textDim} />
            </TouchableOpacity>
            <TouchableOpacity onPress={clearAll} style={styles.hBtn}>
              <Ionicons name="trash-outline" size={20} color={C.textDim} />
            </TouchableOpacity>
            <TouchableOpacity onPress={() => setShowMenu(true)} style={styles.hBtn}>
              <Ionicons name="ellipsis-vertical" size={20} color={C.textDim} />
            </TouchableOpacity>
          </View>
        </BlurView>

        {/* ── MODE SWITCHER ── */}
        <View style={styles.modeBar}>
          {MODE_DATA.map(m => {
            const active = mode === m.key;
            return (
              <TouchableOpacity key={m.key} style={styles.modeBtn} onPress={() => setMode(m.key)}>
                {active && (
                  <LinearGradient colors={[C.user1, C.glow2]} start={{x:0,y:0}} end={{x:1,y:1}}
                                  style={[StyleSheet.absoluteFill, { borderRadius: 20 }]} />
                )}
                <MaterialCommunityIcons name={m.icon as any} size={14}
                                        color={active ? '#fff' : C.textDim} />
                <Text style={[styles.modeBtnText, active && { color: '#fff', fontWeight: '700' }]}>
                  {m.label}
                </Text>
              </TouchableOpacity>
            );
          })}
        </View>

        {/* offline banner */}
        {online === false && (
          <TouchableOpacity style={styles.offlineBanner} onPress={pingServer}>
            <LinearGradient colors={['rgba(239,68,68,0.2)','rgba(239,68,68,0.05)']}
                            start={{x:0,y:0}} end={{x:1,y:1}} style={StyleSheet.absoluteFill} />
            <Ionicons name="warning" size={14} color="#fca5a5" />
            <Text style={styles.offlineText}>  السيرفر غير متصل — في Termux اكتب: python ~/gvr-agent/server.py</Text>
          </TouchableOpacity>
        )}

        {/* ── MESSAGES ── */}
        <FlatList
          ref={listRef}
          data={msgs}
          keyExtractor={m => m.id.toString()}
          renderItem={({ item }) => <Bubble msg={item} />}
          contentContainerStyle={[styles.list, msgs.length === 0 && { flex: 1 }]}
          ListEmptyComponent={<EmptyState onQuick={send} />}
          showsVerticalScrollIndicator={false}
          onContentSizeChange={() => listRef.current?.scrollToEnd({ animated: true })}
        />

        {loading && <TypingIndicator />}

        {/* ── INPUT BAR ── */}
        <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
          <BlurView intensity={60} tint="dark" style={styles.inputBar}>
            <LinearGradient
              colors={['rgba(109,40,217,0.1)','rgba(6,182,212,0.05)']}
              start={{x:0,y:0}} end={{x:1,y:1}}
              style={StyleSheet.absoluteFill}
            />
            <View style={styles.inputWrap}>
              <TextInput
                style={styles.input}
                value={input}
                onChangeText={setInput}
                placeholder={`اسأل GVR-Agent... (${mode})`}
                placeholderTextColor={C.textDim}
                multiline
                maxLength={4000}
              />
              <TouchableOpacity
                style={[styles.sendBtn, (!input.trim() || loading) && styles.sendBtnOff]}
                onPress={() => send()}
                disabled={!input.trim() || loading}
              >
                <LinearGradient colors={[C.user1, C.glow2]} start={{x:0,y:0}} end={{x:1,y:1}}
                                style={[StyleSheet.absoluteFill, { borderRadius: 22 }]} />
                {loading
                  ? <ActivityIndicator size={18} color="#fff" />
                  : <Ionicons name="arrow-up" size={20} color="#fff" />
                }
              </TouchableOpacity>
            </View>
            <Text style={styles.inputHint}>
              {mode === 'agent' ? '🤖 Agent يستخدم أدوات حقيقية' :
               mode === 'gvr'   ? '🔁 يراجع ويصحح إجاباته' : '💬 محادثة سريعة'}
            </Text>
          </BlurView>
        </KeyboardAvoidingView>

        {/* ── MENU MODAL ── */}
        <Modal visible={showMenu} transparent animationType="slide" onRequestClose={() => setShowMenu(false)}>
          <TouchableWithoutFeedback onPress={() => setShowMenu(false)}>
            <View style={styles.modalDim} />
          </TouchableWithoutFeedback>
          <BlurView intensity={80} tint="dark" style={styles.modal}>
            <LinearGradient colors={['rgba(109,40,217,0.2)','rgba(6,182,212,0.05)']}
                            start={{x:0,y:0}} end={{x:1,y:1}}
                            style={StyleSheet.absoluteFill} />
            <View style={styles.modalHandle} />
            <Text style={styles.modalTitle}>الإعدادات</Text>

            {modelInfo && (
              <View style={styles.infoCard}>
                <LinearGradient colors={['rgba(139,92,246,0.15)','rgba(6,182,212,0.08)']}
                                start={{x:0,y:0}} end={{x:1,y:1}}
                                style={StyleSheet.absoluteFill} borderRadius={12} />
                <Text style={styles.infoTitle}>معلومات النموذج</Text>
                <Text style={styles.infoRow}>📦 الحجم: {modelInfo.model_size_gb} GB</Text>
                <Text style={styles.infoRow}>✅ الحالة: {modelInfo.model_loaded ? 'محمّل' : 'غير محمّل'}</Text>
              </View>
            )}

            <Text style={styles.settingLabel}>الوضع الحالي</Text>
            {MODE_DATA.map(m => (
              <TouchableOpacity key={m.key} style={styles.modeRow}
                                onPress={() => { setMode(m.key); setShowMenu(false); }}>
                <View style={[styles.modeRowRadio, mode === m.key && styles.modeRowActive]} />
                <View style={{ flex: 1 }}>
                  <Text style={styles.modeRowLabel}>{m.label}</Text>
                  <Text style={styles.modeRowDesc}>{m.desc}</Text>
                </View>
              </TouchableOpacity>
            ))}

            <View style={styles.setupBox}>
              <MaterialCommunityIcons name="console" size={16} color={C.accentB} />
              <Text style={styles.setupText}>
                {'  '}لتشغيل السيرفر في Termux:{'\n'}
                <Text style={styles.setupCode}>python ~/gvr-agent/server.py</Text>
              </Text>
            </View>

            <TouchableOpacity style={styles.closeBtn} onPress={() => setShowMenu(false)}>
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

/* ── STYLES ──────────────────────────────────────────────────────────────────── */
const styles = StyleSheet.create({
  root:            { flex:1, backgroundColor: C.bg },
  header:          { flexDirection:'row', justifyContent:'space-between', alignItems:'center',
                     paddingHorizontal:16, paddingTop:4, paddingBottom:12,
                     borderBottomWidth:1, borderBottomColor: C.border, overflow:'hidden' },
  headerLeft:      { flexDirection:'row', alignItems:'center', gap:10 },
  headerTitle:     { color:'#f1f5f9', fontSize:17, fontWeight:'800', letterSpacing:-0.3 },
  statusRow:       { flexDirection:'row', alignItems:'center', gap:5, marginTop:1 },
  statusDot:       { width:6, height:6, borderRadius:3 },
  statusText:      { color: C.textDim, fontSize:11 },
  headerRight:     { flexDirection:'row', gap:2 },
  hBtn:            { padding:8 },
  modeBar:         { flexDirection:'row', gap:6, paddingHorizontal:14, paddingVertical:10,
                     borderBottomWidth:1, borderBottomColor: C.border },
  modeBtn:         { flexDirection:'row', alignItems:'center', gap:5,
                     paddingHorizontal:14, paddingVertical:7, borderRadius:20, overflow:'hidden',
                     backgroundColor:'rgba(255,255,255,0.04)',
                     borderWidth:1, borderColor: C.border },
  modeBtnText:     { color: C.textDim, fontSize:13 },
  offlineBanner:   { paddingHorizontal:14, paddingVertical:8, flexDirection:'row',
                     alignItems:'center', overflow:'hidden' },
  offlineText:     { color:'#fca5a5', fontSize:11, flex:1 },
  list:            { padding:16, gap:4 },
  bubbleRow:       { flexDirection:'row', alignItems:'flex-end', gap:8, marginBottom:12 },
  bubbleRowUser:   { flexDirection:'row-reverse' },
  aiAvatar:        { alignItems:'center', justifyContent:'center', overflow:'hidden', flexShrink:0 },
  userAvatar:      { width:32, height:32, borderRadius:32, alignItems:'center',
                     justifyContent:'center', overflow:'hidden', flexShrink:0 },
  bubble:          { maxWidth: W * 0.76, borderRadius:18, overflow:'hidden',
                     padding:12, paddingBottom:8 },
  bubbleUser:      { borderBottomRightRadius:4 },
  bubbleAI:        { borderBottomLeftRadius:4, borderWidth:1, borderColor: C.border },
  bubbleText:      { color:'rgba(255,255,255,0.82)', fontSize:15, lineHeight:23 },
  bubbleTextUser:  { color:'#fff' },
  bubbleMeta:      { flexDirection:'row', gap:8, marginTop:5, alignItems:'center' },
  metaText:        { color:'rgba(255,255,255,0.25)', fontSize:11 },
  scorePill:       { borderRadius:10, paddingHorizontal:8, paddingVertical:2 },
  scoreText:       { fontSize:11, fontWeight:'600' },
  stepsToggle:     { flexDirection:'row', alignItems:'center', gap:5,
                     marginBottom:8, padding:6, backgroundColor:'rgba(139,92,246,0.1)',
                     borderRadius:8, borderWidth:1, borderColor:'rgba(139,92,246,0.2)' },
  stepsToggleText: { color: C.accent, fontSize:12, fontWeight:'600', flex:1 },
  toolStep:        { borderRadius:10, padding:10, marginBottom:6, overflow:'hidden',
                     borderWidth:1, borderColor: C.toolBdr },
  toolStepHeader:  { flexDirection:'row', alignItems:'center', gap:6, marginBottom:5 },
  toolStepName:    { color: C.accent, fontSize:11, fontWeight:'800', letterSpacing:1 },
  toolStepIdx:     { color: C.textDim, fontSize:10 },
  toolStepArg:     { color:'#c4b5fd', fontSize:12, fontFamily:'monospace', lineHeight:18 },
  toolStepObs:     { backgroundColor:'rgba(0,0,0,0.3)', borderRadius:8, padding:8, marginTop:6 },
  toolStepObsLabel:{ color: C.accentB, fontSize:9, fontWeight:'800', letterSpacing:1.5, marginBottom:4 },
  toolStepObsText: { color:'#94a3b8', fontSize:11, fontFamily:'monospace', lineHeight:16 },
  typingWrap:      { paddingHorizontal:16, paddingBottom:4 },
  typingBubble:    { flexDirection:'row', alignItems:'center', gap:6,
                     padding:12, borderRadius:18, borderBottomLeftRadius:4, width:70,
                     overflow:'hidden', borderWidth:1, borderColor: C.border },
  dot:             { width:7, height:7, borderRadius:4 },
  logoBg:          { width:88, height:88, borderRadius:28, alignItems:'center',
                     justifyContent:'center', overflow:'hidden', marginBottom:20,
                     shadowColor: C.glow, shadowRadius:30, shadowOpacity:0.6,
                     shadowOffset:{width:0,height:0} },
  emptyWrap:       { flexGrow:1, alignItems:'center', justifyContent:'center',
                     paddingTop:40, paddingBottom:20, paddingHorizontal:20 },
  emptyTitle:      { color:'#f1f5f9', fontSize:30, fontWeight:'900',
                     letterSpacing:-0.5, marginBottom:10 },
  emptySub:        { color: C.textDim, fontSize:14, textAlign:'center',
                     lineHeight:22, marginBottom:36 },
  suggestGrid:     { width:'100%', gap:10 },
  suggestCard:     { padding:14, borderRadius:14, overflow:'hidden',
                     borderWidth:1, borderColor: C.border },
  suggestBorder:   { position:'absolute', inset:0, borderRadius:14,
                     borderWidth:1, borderColor:'rgba(139,92,246,0.2)' },
  suggestText:     { color:'rgba(255,255,255,0.75)', fontSize:14, lineHeight:20 },
  inputBar:        { borderTopWidth:1, borderTopColor: C.border,
                     paddingHorizontal:12, paddingTop:10, paddingBottom:8, overflow:'hidden' },
  inputWrap:       { flexDirection:'row', alignItems:'flex-end', gap:8 },
  input:           { flex:1, color:'#f1f5f9', fontSize:15, maxHeight:120,
                     backgroundColor:'rgba(255,255,255,0.06)',
                     borderRadius:24, paddingHorizontal:16, paddingVertical:11,
                     borderWidth:1, borderColor: C.border, lineHeight:22 },
  sendBtn:         { width:44, height:44, borderRadius:22, alignItems:'center',
                     justifyContent:'center', overflow:'hidden',
                     shadowColor: C.glow, shadowRadius:12, shadowOpacity:0.6,
                     shadowOffset:{width:0,height:0} },
  sendBtnOff:      { opacity:0.3 },
  inputHint:       { color: C.textDim, fontSize:11, marginTop:6, paddingLeft:4 },
  modalDim:        { flex:1, backgroundColor:'rgba(0,0,0,0.7)' },
  modal:           { borderTopLeftRadius:28, borderTopRightRadius:28,
                     padding:24, paddingBottom:40, overflow:'hidden',
                     borderTopWidth:1, borderTopColor: C.border },
  modalHandle:     { width:40, height:4, backgroundColor: C.border,
                     borderRadius:2, alignSelf:'center', marginBottom:20 },
  modalTitle:      { color:'#f1f5f9', fontSize:20, fontWeight:'800', marginBottom:20 },
  infoCard:        { padding:14, borderRadius:12, marginBottom:20, overflow:'hidden',
                     borderWidth:1, borderColor:'rgba(139,92,246,0.25)' },
  infoTitle:       { color: C.accent, fontSize:12, fontWeight:'700',
                     letterSpacing:1, marginBottom:8 },
  infoRow:         { color:'rgba(255,255,255,0.7)', fontSize:13, marginBottom:4 },
  settingLabel:    { color: C.textDim, fontSize:11, textTransform:'uppercase',
                     letterSpacing:1.5, marginBottom:12 },
  modeRow:         { flexDirection:'row', alignItems:'flex-start', gap:12, paddingVertical:12,
                     borderBottomWidth:1, borderBottomColor: C.border },
  modeRowRadio:    { width:20, height:20, borderRadius:10, borderWidth:2, borderColor: C.border, marginTop:2 },
  modeRowActive:   { backgroundColor: C.accent, borderColor: C.accent },
  modeRowLabel:    { color:'#f1f5f9', fontWeight:'700', fontSize:15, marginBottom:3 },
  modeRowDesc:     { color: C.textDim, fontSize:12, lineHeight:18 },
  setupBox:        { flexDirection:'row', alignItems:'flex-start',
                     backgroundColor:'rgba(6,182,212,0.08)', borderRadius:12,
                     padding:14, marginTop:16, marginBottom:8,
                     borderWidth:1, borderColor:'rgba(6,182,212,0.2)' },
  setupText:       { color:'rgba(255,255,255,0.7)', fontSize:13, flex:1, lineHeight:20 },
  setupCode:       { color: C.accentB, fontFamily:'monospace', fontSize:13 },
  closeBtn:        { borderRadius:14, padding:15, alignItems:'center',
                     marginTop:16, overflow:'hidden' },
  closeBtnText:    { color:'#fff', fontWeight:'800', fontSize:16 },
});
