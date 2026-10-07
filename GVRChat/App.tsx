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
  listLocalModels, importModelFromDevice, loadModel, deleteModel, autoLoadLastModel,
  isModelLoaded, isVisionReady, getLoadedModel, getVisionNote, stopGeneration,
  getModelProfile, downloadRecommendedModel, cancelDownload, RECOMMENDED_MODELS, type RecommendedModel,
  type ModelInfo,
} from './src/localLLM';
import { runWithAttachment, resetConversation, type StepEvent, type AgentStep } from './src/gvrEngine';
import { prepareAttachment, pickAnyFile, type PreparedAttachment } from './src/attachments';
import {
  setToolConfirmHandler, resetToolApprovals, resetTerminalState, toolTerminal,
  setOpenTerminalHandler, refreshLinuxState,
} from './src/tools';
import Terminal from './modules/terminal/src';
import {
  PERM_KEYS, PERMISSION_LABELS, getPermissionsStatus, requestPermission,
  type PermKey, type PermStatus,
} from './src/permissions';

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
  fetch_url:    { icon: 'web',                  label: 'يفتح صفحة ويب' },
  download_file:{ icon: 'download',             label: 'ينزّل ملف' },
  terminal:     { icon: 'console',              label: 'ينفّذ أمر في الترمنال' },
  javascript:   { icon: 'code-braces',          label: 'ينفّذ كود' },
  calc:         { icon: 'calculator',           label: 'يحسب' },
  datetime:     { icon: 'clock-outline',        label: 'يشوف الوقت' },
  read_file:    { icon: 'file-document-outline',label: 'يقرأ ملف' },
  write_file:   { icon: 'file-edit-outline',    label: 'يكتب ملف' },
  list_dir:     { icon: 'folder-outline',       label: 'يستعرض مجلد' },
  delete_file:  { icon: 'delete-outline',       label: 'يمسح ملف' },
  device_info:  { icon: 'cellphone-cog',        label: 'يفحص الجهاز' },
  open_url:     { icon: 'open-in-new',          label: 'يفتح رابط' },
  share_text:   { icon: 'share-variant',        label: 'يشارك نص' },
  permission:   { icon: 'shield-key-outline',   label: 'الصلاحيات' },
  mem_save:     { icon: 'content-save',         label: 'يحفظ في الذاكرة' },
  mem_get:      { icon: 'brain',                label: 'يسترجع من الذاكرة' },
  mem_list:     { icon: 'format-list-bulleted', label: 'يراجع الذاكرة' },
  mem_delete:   { icon: 'delete-outline',       label: 'يمسح من الذاكرة' },
  inspect_file: { icon: 'file-search-outline',  label: 'يفحص الملف' },
  open_terminal:{ icon: 'console',              label: 'يفتح الترمنال' },
  python:       { icon: 'language-python',      label: 'ينفّذ بايثون' },
  pkg_install:  { icon: 'package-variant',      label: 'يثبّت حزمة' },
};

/** Tools whose output is rendered as a real console block, not a generic card. */
const CONSOLE_TOOLS = new Set(['terminal', 'python', 'pkg_install']);


/** Three staggered pulsing dots — the "thinking" / "running" indicator used everywhere below. */
function ThinkingDots({ color = C.accentB, size = 5 }: { color?: string; size?: number }) {
  const vals = useRef([0, 1, 2].map(() => new Animated.Value(0.3))).current;
  useEffect(() => {
    const loops = vals.map((v, i) =>
      Animated.loop(
        Animated.sequence([
          Animated.delay(i * 160),
          Animated.timing(v, { toValue: 1, duration: 420, useNativeDriver: true }),
          Animated.timing(v, { toValue: 0.3, duration: 420, useNativeDriver: true }),
          Animated.delay((2 - i) * 160),
        ]),
      ),
    );
    loops.forEach(l => l.start());
    return () => loops.forEach(l => l.stop());
  }, []);
  return (
    <View style={{ flexDirection: 'row', gap: 4, alignItems: 'center' }}>
      {vals.map((v, i) => (
        <Animated.View key={i} style={{
          width: size, height: size, borderRadius: size / 2, backgroundColor: color,
          opacity: v, transform: [{ scale: v.interpolate({ inputRange: [0.3, 1], outputRange: [0.7, 1.15] }) }],
        }} />
      ))}
    </View>
  );
}

/** Check mark that pops in with a spring the instant a step finishes. */
function DonePop({ color = C.green }: { color?: string }) {
  const s = useRef(new Animated.Value(0)).current;
  useEffect(() => { Animated.spring(s, { toValue: 1, speed: 20, bounciness: 10, useNativeDriver: true }).start(); }, []);
  return (
    <Animated.View style={{ transform: [{ scale: s }] }}>
      <Ionicons name="checkmark-circle" size={15} color={color} />
    </Animated.View>
  );
}

/** A tool step — running or finished. Terminal-family tools render a real console block. */
function StepCard({ tool, arg, result, done, defaultOpen }: {
  tool: string; arg: string; result?: string; done: boolean; defaultOpen?: boolean;
}) {
  const meta = TOOL_LABELS[tool] || { icon: 'tools', label: tool };
  const isConsole = CONSOLE_TOOLS.has(tool);
  const [open, setOpen] = useState(!!defaultOpen || (isConsole && done));
  const mount = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    Animated.timing(mount, { toValue: 1, duration: 260, useNativeDriver: true }).start();
  }, []);
  useEffect(() => { if (isConsole && done) setOpen(true); }, [done, isConsole]);

  return (
    <Animated.View style={{
      opacity: mount, transform: [{ translateY: mount.interpolate({ inputRange: [0, 1], outputRange: [8, 0] }) }],
    }}>
      <TouchableOpacity
        activeOpacity={0.75}
        style={[styles.stepRow, isConsole && styles.stepRowConsole]}
        onPress={() => setOpen(o => !o)}
      >
        <MaterialCommunityIcons name={meta.icon as any} size={15} color={isConsole ? C.accentB : C.accent} />
        <Text style={styles.stepLabel} numberOfLines={1}>{meta.label}</Text>
        {!!arg && (
          <Text style={[styles.stepArg, isConsole && styles.stepArgMono]} numberOfLines={1}>
            {isConsole ? arg.split('\n')[0] : arg}
          </Text>
        )}
        <View style={{ marginLeft: 'auto' }}>{done ? <DonePop /> : <ThinkingDots />}</View>
        {!!result && (
          <Ionicons name={open ? 'chevron-up' : 'chevron-down'} size={14} color={C.textDim} style={{ marginStart: 2 }} />
        )}
      </TouchableOpacity>

      {open && !!result && (
        isConsole ? (
          <View style={styles.consoleBlock}>
            <View style={styles.consoleHeader}>
              <View style={styles.consoleDot} /><View style={styles.consoleDot} /><View style={styles.consoleDot} />
              <Text style={styles.consoleHeaderText}>{tool === 'python' ? 'python3' : tool === 'pkg_install' ? 'apk' : 'شل'}</Text>
            </View>
            {!!arg && <Text style={styles.consoleCmd} selectable>$ {arg}</Text>}
            <Text style={styles.consoleOut} selectable>
              {result.length > 1600 ? `${result.slice(0, 1600)}\n… (القص؛ التفاصيل كاملة في الترمنال)` : result}
            </Text>
          </View>
        ) : (
          <View style={styles.stepResultBox}>
            <Text style={styles.stepResultText} selectable>
              {result.length > 1200 ? `${result.slice(0, 1200)}…` : result}
            </Text>
          </View>
        )
      )}
    </Animated.View>
  );
}

export interface LiveStep { id: string; tool: string; arg: string; result?: string; done: boolean }

/** The "thinking" trace shown live while generating: pulsing header + steps appearing one by one. */
function LiveTrace({ thought, steps }: { thought: string; steps: LiveStep[] }) {
  const shimmer = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(shimmer, { toValue: 1, duration: 1100, useNativeDriver: true }),
        Animated.timing(shimmer, { toValue: 0, duration: 1100, useNativeDriver: true }),
      ]),
    );
    loop.start();
    return () => loop.stop();
  }, []);

  return (
    <View style={styles.liveTrace}>
      <View style={styles.liveTraceHeader}>
        <Animated.View style={{ opacity: shimmer.interpolate({ inputRange: [0, 1], outputRange: [0.5, 1] }) }}>
          <MaterialCommunityIcons name="atom-variant" size={15} color={C.accent} />
        </Animated.View>
        <Text style={styles.liveTraceThought} numberOfLines={1}>{thought || 'يفكر...'}</Text>
        <ThinkingDots />
      </View>
      {steps.map(st => (
        <StepCard key={st.id} tool={st.tool} arg={st.arg} result={st.result} done={st.done} />
      ))}
    </View>
  );
}

/** Finished steps attached to a sent message — collapsed by default unless a console tool ran. */
function ToolTrace({ steps, elapsed }: { steps?: AgentStep[]; elapsed?: number }) {
  const hasConsole = !!steps?.some(s => CONSOLE_TOOLS.has(s.tool));
  const [open, setOpen] = useState(hasConsole);
  if (!steps || steps.length === 0) return null;
  return (
    <View style={styles.toolTraceWrap}>
      <TouchableOpacity style={styles.toolTraceHeader} onPress={() => setOpen(o => !o)} activeOpacity={0.7}>
        <MaterialCommunityIcons name="layers-outline" size={13} color={C.textDim} />
        <Text style={styles.toolTraceHeaderText}>
          {steps.length} {steps.length === 1 ? 'أداة' : 'أدوات'}{elapsed ? ` · ${elapsed}s` : ''}
        </Text>
        <Ionicons name={open ? 'chevron-up' : 'chevron-down'} size={13} color={C.textDim} />
      </TouchableOpacity>
      {open && steps.map(st => (
        <StepCard key={st.id} tool={st.tool} arg={st.arg} result={st.result} done defaultOpen={CONSOLE_TOOLS.has(st.tool)} />
      ))}
    </View>
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
  toolsUsed?: string[]; steps?: AgentStep[];
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
                          style={[StyleSheet.absoluteFill, { borderRadius: 18, borderBottomRightRadius: 4 }]} />
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

        {!isUser && <ToolTrace steps={msg.steps} elapsed={msg.elapsed} />}

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
                          style={[StyleSheet.absoluteFill, { borderRadius: 36 }]} />
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
  const [liveThought, setLiveThought] = useState('');
  const [liveSteps, setLiveSteps] = useState<LiveStep[]>([]);
  const [modelReady, setModelReady] = useState(false);
  const [visionReady, setVisionReadyState] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [pendingAttachment, setPendingAttachment] = useState<PreparedAttachment | null>(null);
  const [attaching, setAttaching] = useState(false);
  const [localModels, setLocalModels] = useState<ModelInfo[]>([]);
  const [modelLoading, setModelLoading] = useState(false);
  const [modelLoadPct, setModelLoadPct] = useState(0);
  const [perms, setPerms] = useState<Partial<Record<PermKey, PermStatus>>>({});
  const [showTerminal, setShowTerminal] = useState(false);
  const [termLines, setTermLines] = useState<{ cmd: string; out: string }[]>([]);
  const [termInput, setTermInput] = useState('');
  const [termBusy, setTermBusy] = useState(false);
  const [termMode, setTermMode] = useState<'alpine' | 'android'>('android');
  const termScroll = useRef<ScrollView>(null);
  const [dl, setDl] = useState<{ id: string; pct: number } | null>(null);
  const [linuxReady, setLinuxReady] = useState(false);
  const [linuxBusy, setLinuxBusy] = useState('');

  const showProfileWarnings = () => {
    const w = getModelProfile()?.warnings ?? [];
    if (w.length) Alert.alert('تنبيه عن النموذج', w.join('\n\n'));
  };

  const listRef = useRef<FlatList>(null);

  useEffect(() => {
    (async () => {
      try {
        const h = await AsyncStorage.getItem('gvr_msgs');
        if (h) setMsgs(JSON.parse(h));
      } catch { /* corrupt history — start clean */ }
      try {
        setLocalModels(await listLocalModels());
      } catch (e: any) {
        Alert.alert('خطأ في قراءة النماذج', e.message);
      }
      try {
        setModelLoading(true);
        const r = await autoLoadLastModel((pct) => setModelLoadPct(pct));
        if (r.status === 'skipped_after_crash') {
          Alert.alert('تنبيه', 'آخر تحميل للنموذج وقف التطبيق (غالباً الذاكرة). جرّب نموذج أصغر أو تكميم أقل من الإعدادات.');
        }
      } catch (e: any) {
        Alert.alert('فشل تحميل النموذج الأخير', e.message);
      } finally {
        setModelLoading(false);
        setModelLoadPct(0);
        setModelReady(isModelLoaded());
        setVisionReadyState(isVisionReady());
        if (isModelLoaded()) showProfileWarnings();
      }
    })();
  }, []);

  // Side-effect tools (terminal, writing files, ...) ask the user first.
  useEffect(() => {
    setToolConfirmHandler((tool, arg) => new Promise<'once' | 'always' | 'deny'>((resolve) => {
      const label = TOOL_LABELS[tool]?.label || tool;
      Alert.alert(
        `GVR عايز: ${label}`,
        arg.length > 400 ? arg.slice(0, 400) + '…' : (arg || '(بدون وسيط)'),
        [
          { text: 'رفض', style: 'cancel', onPress: () => resolve('deny') },
          { text: 'دايماً (الجلسة)', onPress: () => resolve('always') },
          { text: 'نفّذ', onPress: () => resolve('once') },
        ],
        { cancelable: true, onDismiss: () => resolve('deny') },
      );
    }));
    return () => setToolConfirmHandler(null);
  }, []);

  useEffect(() => {
    if (showSettings) getPermissionsStatus().then(setPerms).catch(() => {});
  }, [showSettings]);

  useEffect(() => {
    setOpenTerminalHandler(() => setShowTerminal(true));
    refreshLinuxState().then(setLinuxReady).catch(() => {});
    return () => setOpenTerminalHandler(null);
  }, []);

  useEffect(() => {
    if (showTerminal) Terminal.mode().then(setTermMode).catch(() => {});
  }, [showTerminal]);

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
    setLiveThought('يفكر...');
    setLiveSteps([]);

    try {
      const res = await runWithAttachment(
        msg || 'صف/حلل المرفق ده',
        attachment,
        (e) => {
          if (e.type === 'thought') setLiveThought(e.text);
          else if (e.type === 'tool_call') {
            setLiveSteps(prev => [...prev, { id: e.id, tool: e.tool, arg: e.arg, done: false }]);
          } else if (e.type === 'tool_result') {
            setLiveSteps(prev => prev.map(st => (st.id === e.id ? { ...st, result: e.result, done: true } : st)));
          }
        },
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
        steps: res.steps.length ? res.steps : undefined,
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
      setLiveThought('');
      setLiveSteps([]);
      setTimeout(() => listRef.current?.scrollToEnd({ animated: true }), 100);
    }
  }, [input, msgs, loading, modelReady, pendingAttachment]);

  const clearAll = () => Alert.alert('مسح المحادثة', 'هتتمسح كل الرسائل؟', [
    { text: 'إلغاء', style: 'cancel' },
    { text: 'مسح', style: 'destructive', onPress: async () => {
      setMsgs([]); await AsyncStorage.removeItem('gvr_msgs');
      resetConversation(); resetToolApprovals(); resetTerminalState();
    }},
  ]);

  const afterLoad = () => {
    setModelReady(isModelLoaded());
    setVisionReadyState(isVisionReady());
    const note = getVisionNote();
    if (note) Alert.alert('الرؤية', note);
    showProfileWarnings();
  };

  const handleImportModel = async () => {
    try {
      setModelLoading(true);
      const model = await importModelFromDevice();
      if (!model) return;
      setLocalModels(await listLocalModels());
      if (model.isProjector) {
        Alert.alert('تم', 'ملف الرؤية (mmproj) اتحفظ. حمّل النموذج الأساسي (أو أعد تحميله) عشان يتربط بيه.');
        return;
      }
      await loadModel(model, {}, (pct) => setModelLoadPct(pct));
      afterLoad();
    } catch (e: any) {
      Alert.alert('فشل استيراد/تحميل النموذج', e.message);
    } finally {
      setModelLoading(false);
      setModelLoadPct(0);
      setModelReady(isModelLoaded());
    }
  };

  const handleSelectModel = async (model: ModelInfo) => {
    try {
      setModelLoading(true);
      await loadModel(model, {}, (pct) => setModelLoadPct(pct));
      afterLoad();
      setShowSettings(false);
    } catch (e: any) {
      Alert.alert('فشل تحميل النموذج', e.message);
    } finally {
      setModelLoading(false);
      setModelLoadPct(0);
      setModelReady(isModelLoaded());
    }
  };

  const handleDeleteModel = (model: ModelInfo) => {
    Alert.alert('مسح الملف', `هتمسح "${model.name}" من الجهاز؟`, [
      { text: 'إلغاء', style: 'cancel' },
      { text: 'مسح', style: 'destructive', onPress: async () => {
        try {
          await deleteModel(model);
          setLocalModels(await listLocalModels());
          setModelReady(isModelLoaded());
          setVisionReadyState(isVisionReady());
        } catch (e: any) {
          Alert.alert('فشل المسح', e.message);
        }
      }},
    ]);
  };

  const handlePermission = async (k: PermKey) => {
    const r = await requestPermission(k);
    setPerms(await getPermissionsStatus());
    if (r.status !== 'granted') Alert.alert(PERMISSION_LABELS[k], r.message);
  };

  const handleDownloadModel = async (rec: RecommendedModel) => {
    if (dl) return;
    setDl({ id: rec.id, pct: 0 });
    try {
      const m = await downloadRecommendedModel(rec, (f) => setDl({ id: rec.id, pct: f }));
      setLocalModels(await listLocalModels());
      Alert.alert('تم التنزيل', `${rec.title} جاهز. تحمّله دلوقتي؟`, [
        { text: 'لاحقاً', style: 'cancel' },
        { text: 'حمّله', onPress: () => handleSelectModel(m) },
      ]);
    } catch (e: any) {
      Alert.alert('فشل التنزيل', e.message);
    } finally {
      setDl(null);
    }
  };

  const pushTerminal = (cmd: string, out: string) => {
    setTermLines((l) => [...l, { cmd, out }]);
    setTimeout(() => termScroll.current?.scrollToEnd({ animated: true }), 50);
  };

  const runTerminal = async () => {
    const cmd = termInput.trim();
    if (!cmd || termBusy) return;
    setTermInput('');
    setTermBusy(true);
    try {
      const out = await toolTerminal(cmd);
      pushTerminal(cmd, out);
      setTermMode(await Terminal.mode());
    } catch (e: any) {
      pushTerminal(cmd, `خطأ: ${e?.message || e}`);
    } finally {
      setTermBusy(false);
    }
  };

  const handleInstallLinux = async () => {
    if (linuxBusy) return;
    setLinuxBusy('بنبدأ...');
    try {
      await Terminal.installAlpine((msg, frac) =>
        setLinuxBusy(frac !== undefined ? `${msg} ${Math.round(frac * 100)}%` : msg));
      setLinuxReady(await refreshLinuxState());
      setTermMode(await Terminal.mode());
      Alert.alert('تم', 'Alpine Linux اتثبّت. دلوقتي ثبّت python وgit وnode من الزرار اللي تحت.');
    } catch (e: any) {
      Alert.alert('فشل تثبيت Linux', e.message);
    } finally {
      setLinuxBusy('');
    }
  };

  const handleInstallDevTools = async () => {
    if (linuxBusy) return;
    setLinuxBusy('بننزّل python وgit وnode (ممكن ياخد كام دقيقة)...');
    try {
      const out = await Terminal.installPackages('python3 py3-pip git nodejs npm curl');
      setShowSettings(false);
      setShowTerminal(true);
      pushTerminal('apk add python3 py3-pip git nodejs npm curl', out.slice(-1500));
    } catch (e: any) {
      Alert.alert('فشل تثبيت الأدوات', e.message);
    } finally {
      setLinuxBusy('');
    }
  };

  const handleSelfTest = async () => {
    if (linuxBusy) return;
    setLinuxBusy('بيفحص...');
    try {
      const out = await Terminal.selfTest();
      setShowSettings(false);
      setShowTerminal(true);
      pushTerminal('self-test', out);
    } finally {
      setLinuxBusy('');
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
                  {modelLoading ? 'جارٍ تحميل النموذج…' : modelReady ? `${visionReady ? 'نص + رؤية' : 'نص فقط'}${getModelProfile()?.warnings.length ? ' ⚠' : ''}` : 'مفيش نموذج'}
                </Text>
              </View>
            </View>
          </View>
          <View style={styles.headerRight}>
            <TouchableOpacity onPress={clearAll} style={styles.hBtn}>
              <Ionicons name="trash-outline" size={19} color={C.textDim} />
            </TouchableOpacity>
            <TouchableOpacity onPress={() => setShowTerminal(true)} style={styles.hBtn}>
              <MaterialCommunityIcons name="console" size={20} color={C.textDim} />
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
        {loading && liveSteps.length > 0 && (() => {
          setTimeout(() => listRef.current?.scrollToEnd({ animated: true }), 0);
          return null;
        })()}

        {loading && (
          <View style={styles.loadingRow}>
            <AIAvatar size={28} />
            <View style={styles.liveStatusWrap}>
              <LiveTrace thought={liveThought} steps={liveSteps} />
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
                style={[styles.sendBtn, (!loading && ((!input.trim() && !pendingAttachment) || !modelReady)) && styles.sendBtnOff]}
                onPress={() => (loading ? stopGeneration() : send())}
                disabled={!loading && ((!input.trim() && !pendingAttachment) || !modelReady)}
              >
                <LinearGradient colors={[C.user1, C.glow2]} start={{x:0,y:0}} end={{x:1,y:1}}
                                style={[StyleSheet.absoluteFill, { borderRadius: 22 }]} />
                {loading
                  ? <Ionicons name="stop" size={18} color="#fff" />
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
            <ScrollView style={{ maxHeight: H * 0.82 }} showsVerticalScrollIndicator={false}
                        contentContainerStyle={{ paddingBottom: 8 }}>
            <Text style={styles.modalTitle}>النماذج</Text>

            <ScrollView style={{ maxHeight: H * 0.3 }}>
              {localModels.length === 0 && (
                <Text style={styles.noModelsText}>مفيش نماذج محمّلة بعد</Text>
              )}
              {localModels.map((m) => {
                const active = getLoadedModel()?.uri === m.uri;
                return (
                  <TouchableOpacity key={m.uri} style={styles.modelRow}
                    onPress={() => m.isProjector
                      ? Alert.alert('ملف رؤية', 'ده ملف mmproj للرؤية — بيتربط تلقائياً مع النموذج الأساسي لما تحمّله.')
                      : handleSelectModel(m)}>
                    <MaterialCommunityIcons name={m.isProjector ? 'eye-outline' : 'cube-outline'}
                      size={20} color={active ? C.green : C.accent} />
                    <View style={{ flex: 1 }}>
                      <Text style={styles.modelName} numberOfLines={1}>{m.name}</Text>
                      <Text style={styles.modelSize}>
                        {m.sizeGB} GB{m.isProjector ? ' · رؤية' : ''}{active ? ' · محمّل' : ''}
                      </Text>
                    </View>
                    <TouchableOpacity onPress={() => handleDeleteModel(m)} hitSlop={8}>
                      <Ionicons name="trash-outline" size={18} color={C.textDim} />
                    </TouchableOpacity>
                  </TouchableOpacity>
                );
              })}
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

            {!!getModelProfile()?.warnings.length && (
              <TouchableOpacity style={styles.modelWarnBox} onPress={showProfileWarnings}>
                <Ionicons name="warning-outline" size={16} color="#f59e0b" />
                <Text style={styles.modelWarnText}>{getModelProfile()!.warnings[0]}</Text>
              </TouchableOpacity>
            )}

            <Text style={[styles.modalTitle, { marginTop: 18, fontSize: 16, marginBottom: 8 }]}>نماذج مقترحة (تنزيل مباشر)</Text>
            {RECOMMENDED_MODELS.map((rec) => {
              const have = localModels.some((m) => m.name === rec.file);
              const busy = dl?.id === rec.id;
              return (
                <View key={rec.id} style={styles.recRow}>
                  <View style={{ flex: 1 }}>
                    <Text style={styles.modelName}>{rec.title}</Text>
                    <Text style={styles.modelSize}>{rec.sizeGB} GB · {rec.note}</Text>
                    {busy && <Text style={styles.progressText}>تنزيل... {Math.round((dl?.pct ?? 0) * 100)}%</Text>}
                  </View>
                  {busy ? (
                    <TouchableOpacity style={styles.recBtn} onPress={() => cancelDownload()}>
                      <Text style={styles.recBtnText}>إلغاء</Text>
                    </TouchableOpacity>
                  ) : (
                    <TouchableOpacity style={[styles.recBtn, (have || !!dl) && { opacity: 0.45 }]}
                      disabled={have || !!dl} onPress={() => handleDownloadModel(rec)}>
                      <Text style={styles.recBtnText}>{have ? 'موجود' : 'نزّل'}</Text>
                    </TouchableOpacity>
                  )}
                </View>
              );
            })}

            <Text style={[styles.modalTitle, { marginTop: 18, fontSize: 16, marginBottom: 8 }]}>Linux (ترمنال قوي)</Text>
            <Text style={styles.infoText}>
              {linuxReady
                ? 'Alpine Linux شغّال: python وapk وgit وnode متاحين في الترمنال.'
                : 'بيثبّت Alpine Linux (حوالي 3MB) جوه التطبيق، وبعدها تقدر تثبّت python وgit وnode بـ apk.'}
            </Text>
            {!!linuxBusy && (
              <View style={styles.progressBox}>
                <ActivityIndicator size="small" color={C.accent} />
                <Text style={styles.progressText}>{linuxBusy}</Text>
              </View>
            )}
            <View style={styles.permWrap}>
              {!linuxReady && (
                <TouchableOpacity style={styles.recBtn} onPress={handleInstallLinux} disabled={!!linuxBusy}>
                  <Text style={styles.recBtnText}>تثبيت Alpine</Text>
                </TouchableOpacity>
              )}
              {linuxReady && (
                <TouchableOpacity style={styles.recBtn} onPress={handleInstallDevTools} disabled={!!linuxBusy}>
                  <Text style={styles.recBtnText}>تثبيت python + git + node</Text>
                </TouchableOpacity>
              )}
              <TouchableOpacity style={styles.recBtn} onPress={handleSelfTest} disabled={!!linuxBusy}>
                <Text style={styles.recBtnText}>اختبار ذاتي</Text>
              </TouchableOpacity>
            </View>

            <Text style={[styles.modalTitle, { marginTop: 18, fontSize: 16, marginBottom: 8 }]}>الصلاحيات</Text>
            <View style={styles.permWrap}>
              {PERM_KEYS.map((k) => (
                <TouchableOpacity key={k} style={styles.permChip} onPress={() => handlePermission(k)}>
                  <View style={[styles.permDot, {
                    backgroundColor: perms[k] === 'granted' ? C.green : perms[k] === 'denied' ? C.error : C.textDim,
                  }]} />
                  <Text style={styles.permText}>{PERMISSION_LABELS[k]}</Text>
                </TouchableOpacity>
              ))}
            </View>
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
            </ScrollView>
          </BlurView>
        </Modal>

        <Modal visible={showTerminal} animationType="slide" onRequestClose={() => setShowTerminal(false)}>
          <SafeAreaView style={styles.termRoot}>
            <View style={styles.termHeader}>
              <TouchableOpacity onPress={() => setShowTerminal(false)} style={styles.hBtn}>
                <Ionicons name="close" size={22} color="#e2e8f0" />
              </TouchableOpacity>
              <Text style={styles.termTitle}>الترمنال</Text>
              <View style={styles.termBadge}>
                <Text style={styles.termBadgeText}>{termMode === 'alpine' ? 'Alpine Linux' : 'Android shell'}</Text>
              </View>
              <TouchableOpacity onPress={() => { setTermLines([]); resetTerminalState(); }} style={styles.hBtn}>
                <Ionicons name="trash-outline" size={19} color={C.textDim} />
              </TouchableOpacity>
            </View>
            <ScrollView ref={termScroll} style={styles.termBody} contentContainerStyle={{ padding: 12 }}
                        keyboardShouldPersistTaps="handled">
              {termLines.length === 0 && (
                <Text style={styles.termHint}>
                  اكتب أمر واضغط ▶. الفولدر اللي بتعمله cd بيتحفظ.{'\n'}
                  {termMode === 'alpine' ? 'جرّب: apk add python3  |  python3 -V' : 'جرّب: ls /sdcard  |  df -h  |  ps'}
                </Text>
              )}
              {termLines.map((l, i) => (
                <View key={i} style={{ marginBottom: 10 }}>
                  <Text style={styles.termCmd} selectable>$ {l.cmd}</Text>
                  <Text style={styles.termOut} selectable>{l.out}</Text>
                </View>
              ))}
              {termBusy && <ActivityIndicator size="small" color={C.accent} />}
            </ScrollView>
            <View style={styles.termInputRow}>
              <TextInput
                style={styles.termInput} value={termInput} onChangeText={setTermInput}
                placeholder="اكتب الأمر..." placeholderTextColor="#64748b"
                autoCapitalize="none" autoCorrect={false} multiline
                onSubmitEditing={runTerminal} editable={!termBusy}
              />
              <TouchableOpacity style={[styles.termRun, termBusy && { opacity: 0.5 }]} onPress={runTerminal} disabled={termBusy}>
                <Ionicons name="play" size={18} color="#fff" />
              </TouchableOpacity>
            </View>
          </SafeAreaView>
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
  permWrap:        { flexDirection:'row', flexWrap:'wrap', gap:8 },
  permChip:        { flexDirection:'row', alignItems:'center', gap:6, paddingHorizontal:10, paddingVertical:7,
                     borderRadius:12, borderWidth:1, borderColor: C.border, backgroundColor:'rgba(255,255,255,0.04)' },
  permDot:         { width:7, height:7, borderRadius:4 },
  permText:        { color: C.text, fontSize:12 },
  modelWarnBox:         { flexDirection:'row', gap:8, alignItems:'flex-start', marginTop:12, padding:10, borderRadius:12,
                     backgroundColor:'rgba(245,158,11,0.12)', borderWidth:1, borderColor:'rgba(245,158,11,0.35)' },
  modelWarnText:        { flex:1, color:'#fcd34d', fontSize:12, lineHeight:18 },
  recRow:          { flexDirection:'row', alignItems:'center', gap:10, padding:10, marginBottom:8, borderRadius:12,
                     backgroundColor:'rgba(255,255,255,0.04)', borderWidth:1, borderColor: C.border },
  recBtn:          { paddingHorizontal:14, paddingVertical:9, borderRadius:12, backgroundColor:'rgba(124,58,237,0.35)',
                     borderWidth:1, borderColor:'rgba(167,139,250,0.5)', marginTop:6 },
  recBtnText:      { color:'#e9d5ff', fontSize:12, fontWeight:'700' },
  termRoot:        { flex:1, backgroundColor:'#050509' },
  termHeader:      { flexDirection:'row', alignItems:'center', gap:8, paddingHorizontal:8, paddingVertical:6,
                     borderBottomWidth:1, borderBottomColor:'rgba(255,255,255,0.08)' },
  termTitle:       { flex:1, color:'#f1f5f9', fontSize:17, fontWeight:'800' },
  termBadge:       { paddingHorizontal:10, paddingVertical:4, borderRadius:10, backgroundColor:'rgba(16,185,129,0.15)' },
  termBadgeText:   { color:'#6ee7b7', fontSize:11, fontWeight:'700' },
  termBody:        { flex:1 },
  termHint:        { color:'#64748b', fontSize:13, lineHeight:20 },
  termCmd:         { color:'#67e8f9', fontSize:13, fontFamily: Platform.OS === 'android' ? 'monospace' : 'Courier' },
  termOut:         { color:'#e2e8f0', fontSize:12.5, lineHeight:18, marginTop:2,
                     fontFamily: Platform.OS === 'android' ? 'monospace' : 'Courier' },
  termInputRow:    { flexDirection:'row', alignItems:'flex-end', gap:8, padding:10,
                     borderTopWidth:1, borderTopColor:'rgba(255,255,255,0.08)' },
  termInput:       { flex:1, maxHeight:110, minHeight:42, color:'#e2e8f0', backgroundColor:'rgba(255,255,255,0.06)',
                     borderRadius:12, paddingHorizontal:12, paddingVertical:8, fontSize:14,
                     fontFamily: Platform.OS === 'android' ? 'monospace' : 'Courier' },
  termRun:         { width:44, height:44, borderRadius:22, backgroundColor:'#7c3aed', alignItems:'center', justifyContent:'center' },

  // live "thinking" trace (shown while generating, above the input bar)
  liveTrace:        { gap:6, paddingVertical:2 },
  liveTraceHeader:   { flexDirection:'row', alignItems:'center', gap:7 },
  liveTraceThought:  { flex:1, color:C.accentB, fontSize:12.5, fontWeight:'600' },

  // one tool step (live or finished) — compact card, expands on tap
  stepRow:          { flexDirection:'row', alignItems:'center', gap:7, paddingHorizontal:10, paddingVertical:8,
                      borderRadius:12, backgroundColor:'rgba(255,255,255,0.035)', borderWidth:1, borderColor:C.border },
  stepRowConsole:   { backgroundColor:'rgba(6,182,212,0.07)', borderColor:'rgba(6,182,212,0.25)' },
  stepLabel:        { color:C.text, fontSize:12.5, fontWeight:'600' },
  stepArg:          { flex:1, color:C.textDim, fontSize:11.5 },
  stepArgMono:      { fontFamily: Platform.OS === 'android' ? 'monospace' : 'Courier', color:'#67e8f9' },
  stepResultBox:    { marginTop:-4, marginBottom:2, padding:10, borderRadius:10,
                      backgroundColor:'rgba(255,255,255,0.03)', borderWidth:1, borderColor:C.border, borderTopWidth:0,
                      borderTopLeftRadius:0, borderTopRightRadius:0 },
  stepResultText:   { color:'#cbd5e1', fontSize:12, lineHeight:18 },

  // terminal-style console block (used for terminal/python/pkg_install steps)
  consoleBlock:     { marginTop:-4, marginBottom:2, borderRadius:10, borderTopLeftRadius:0, borderTopRightRadius:0,
                      backgroundColor:'#0a0e14', borderWidth:1, borderColor:'rgba(6,182,212,0.25)', borderTopWidth:0,
                      overflow:'hidden', padding:10 },
  consoleHeader:    { flexDirection:'row', alignItems:'center', gap:5, marginBottom:7 },
  consoleDot:       { width:7, height:7, borderRadius:4, backgroundColor:'rgba(255,255,255,0.15)' },
  consoleHeaderText:{ color:'#64748b', fontSize:10.5, marginStart:4, fontWeight:'700', letterSpacing:0.5 },
  consoleCmd:       { color:'#67e8f9', fontSize:12.5, marginBottom:5,
                      fontFamily: Platform.OS === 'android' ? 'monospace' : 'Courier' },
  consoleOut:       { color:'#d1d9e0', fontSize:12, lineHeight:17.5,
                      fontFamily: Platform.OS === 'android' ? 'monospace' : 'Courier' },

  // collapsible "N tools · X.Xs" header attached to a finished assistant message
  toolTraceWrap:       { gap:6, marginBottom:8 },
  toolTraceHeader:     { flexDirection:'row', alignItems:'center', gap:5, alignSelf:'flex-start',
                        paddingHorizontal:9, paddingVertical:5, borderRadius:10,
                        backgroundColor:'rgba(255,255,255,0.03)' },
  toolTraceHeaderText: { color:C.textDim, fontSize:11, fontWeight:'600' },
});
