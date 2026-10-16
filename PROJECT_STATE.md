# GVR-Chat Project State (آخر تحديث: 2026-10-13)



## جلسة 2026-10-13: proot اشتغل ✅ + تحسينات الـ agent

### نتيجة التجربة على الجهاز (nubia NX669J / Android 12 / kernel 5.4)
- `proot` شغّال (باينريات Termux بدون تعديل + symlink لـ `libtalloc.so.2`). `proroot` كان بيرفض `-b /dev` (لازم host:guest) ← اتصلّح (مش مستخدم افتراضياً)
- فيديو التطبيق: الأدوات شغّالة (datetime ✓، search ✓، terminal جوّه Alpine ✓) لكن النموذج المتحمّل كان tiny/base (وضع "weak") فبيرد كلام غلط. ومشكلتين في الـ router: query البحث كان "علي الانترنت" بدل الموضوع، و"افتح تريمنال" بيشغّل probe جاهز بس

### اللي اتعمل
- `router.ts`: استخراج كلمة البحث لما الفعل يجي آخر الجملة ("X ابحثلي على النت")، "كم الساعه"، وأسئلة "حاليا/الان" بتتبحث تلقائياً. 23 اختبار (node) عدّت كلها
- `gvrEngine.ts`: **native tool calling** للنماذج القادرة (مش tiny/base): `tools` + `tool_choice:'auto'` عن طريق llama.rn (Jinja + grammar على JSON الأداة) مع fallback تلقائي للطريقة القديمة (`<tool>` tags). جولات الأدوات بدون DRY وبـ penalty خفيف
- تحذير سرعة لو التوليد < 5 توكن/ث (عشان نقيس 14B على الجهاز فعلاً)
- `tools.ts`: schemas لـ 15 أداة، وحارس للأوامر الخطيرة (rm -rf / dd / mkfs / find -delete ...) بيطلب تأكيد حتى لو اخترت "always"
- `TerminalModule.kt`: bind format لـ proroot

### تصحيحات على الخطة اللي اتنسخت من ذكاء تاني
- Structured outputs بتضمن **شكل** الرد (JSON صالح) مش **صحة** المحتوى. النموذج لسه ممكن يختار أداة/أمر غلط ← عشان كده التأكيد على الأوامر الخطيرة
- حلقة الأدوات (`gvrEngine.ts`) والذاكرة (`mem_save/mem_get` في AsyncStorage) **موجودين أصلاً**. SQLite تحسين لاحق مش أولوية
- 14B على SD888 (CPU): تقدير مني (مش قياس) 2–3 توكن/ث للتوليد وprompt بطيء ← للـ agent الأنسب 7–9B، و14B للمهام الثقيلة المفردة. القياس الفعلي هيظهر في تحذير السرعة
- PaddleOCR مش مناسب لـ Alpine/musl (حزم Python الجاهزة glibc) ← الأنسب Tesseract (`apk add tesseract-ocr tesseract-ocr-data-ara`)
- Whisper: الأنسب `whisper.rn` (نفس مطوّر llama.rn) بدل بناء whisper.cpp جوّه Alpine

### المرحلة 2 (الفيديو) — لسه ما اتعملتش
1. ffmpeg من Alpine (`apk add ffmpeg`): الصوت (`-vn -ac 1 -ar 16000`) + الفريمات. وحدة `expo-video-processor` بتطلّع فريمات بس (الصوت غير منفّذ)
2. `whisper.rn` + نموذج ggml (large-v3-turbo-q5_0 ≈ 570MB، أو small للسرعة) ← تفريغ الصوت
3. Tesseract لقراءة نص الفريمات (عربي + إنجليزي)
4. أداة `analyze_video` واحدة بتجمّع الثلاثة وترجّع نص للنموذج


### تكملة 2026-10-13: ليه كان بيفضل "جاري التفكير" + أي حاجة مش عارفها يجيبها من النت (apk-61)
- **السبب الأرجح للتجمّد:** نموذج كبير (14B) على CPU + prompt تقيل: قائمة الأدوات (~1100 توكن) + **الوقت بالثواني جوّه الـ system prompt**، فأول الـ prompt بيتغيّر كل رسالة وllama.cpp مش بيقدر يعيد استخدام الـ KV cache ويقرا كل حاجة من الأول كل مرة. ومفيش عرض للتقدم فكان شكله متجمّد
- **اتصلّح:** system prompt ثابت (التاريخ بس)، قائمة أدوات خفيفة للنماذج ≥8B (5 أدوات)، ردود أقصر (480 توكن)، نتايج بحث أقصر، عرض حيّ للتقدم (ثواني القراءة + آخر الكلام + توكن/ث)، watchdog بيوقف التوليد بعد 5 دقايق (10 للنماذج الكبيرة) ويقول السبب، ورسالة "بانتظار موافقتك" لما أداة محتاجة تأكيد، وتحذير عند تحميل نموذج ≥8B
- **أي معلومة مش عارفها يجيبها من النت لوحده:** (1) النماذج القادرة: الـ system prompt بيقول search الأول لأي معلومة مش متأكد منها، (2) لو الرد "مش عارف/عذراً/محتاج معلومات" بيبحث تلقائياً ويعيد الإجابة من النتايج، (3) النماذج الضعيفة (tiny/base): أي سؤال معلومات بيتبحث تلقائياً والنتايج بتتعرض مباشرة بدل ما النموذج يهلوس
- **الوقت والحساب:** إجابة فورية من التطبيق من غير نموذج (`instant.ts`)
- **`install_tool`:** بينزّل أي أداة من Alpine (apk) ويرجّع بطاقة (الـ executables + --version + --help) ويحفظها في الذاكرة كـ `tool:NAME` عشان يعرف يشغّلها بعدين. "نزل ffmpeg" بيروح عليها مباشرة. لو الأداة مش في apk بيقترح بدائل (pip / npm / binary)
- **اختبارات:** router (30+ حالة)، helpers، ومحاكاة كاملة لمحرك الـ agent بنموذج وهمي (22 سيناريو: weak/capable/native/fallback/big model/...) — كلها بتعدّي. **لسه مش متجرّبة على جهاز حقيقي**

## جلسة 2026-10-12: سبب crash الـ proot (فرضية قوية) + إصلاح SWARM

### اللي اتكشف
- `AION-SWARM` كان فاشل من يونيو (exit code 2): `swarm.yml` بيبعت `--max-steps` والسكريبت بقى بياخد `--max-minutes` بس → اتصلّح في PR #1 (`--max-minutes 40` + concurrency) والـ run خلص ناجح بالكامل (4 shards + تجميع + release)
- `proot --version` بيطلع SIGSEGV (139) حتى من غير ptrace → المشكلة في الباينري نفسه مش في ptrace/sysvipc. فحص ELF لـ `libproot.so`: الـ PHDR والمحاذاة (16KB) سليمين (فرضية AT_PHDR/16KB اتنفت)، **لكن الملف معدّل بـ patchelf**: الـ workflow القديم كان بيعمل `--replace-needed` و`--set-rpath`، ولقينا segment جديد في آخر الملف فيه INTERP/NOTE (offset 0x40000). ده شكل patchelf ≥0.17 المعروف إنه بيطلّع executables بتعمل segfault وقت التشغيل (NixOS/patchelf#446)
- `libproroot.so` (مش معدّل) تركيبه سليم

### اللي اتعمل
- `fetch_proot_runtime.yml`: شلنا patchelf تماماً؛ بنشحن ملفات Termux كما هي (byte-identical) + sha256 وتقرير readelf
- `TerminalModule.kt`: `libtalloc.so.2` بقى symlink بيتعمل وقت التشغيل في `filesDir/proot-libs/` يشاور على `nativeLibraryDir/libtalloc.so` ويتحط أول واحد في `LD_LIBRARY_PATH`
- الـ self-test بيطبع دلوقتي: kernel/model + `proot --version` مباشر + عن طريق `/system/bin/linker64`

### ✅ اتجرّب على الجهاز (2026-10-13)
- `proot --version: exit=0` (مباشر وعن طريق linker64) و`alpine (via proot): exit=0 ... guest_ok` ← تشخيص patchelf اتأكد والحل نجح (راجع جلسة 2026-10-13 تحت)

## جلسة 2026-10-06: مراجعة فيديو التجربة على الجهاز (Android 12) وإصلاحات

### اللي اتكشف من الفيديو + فحص الـ APK
- النموذج اللي كان محمّل: **Qwen2.5-0.5B (Base, Q8_0)** — صغير جداً ومش Instruct. ده سبب: عدم تنفيذ الأدوات، الردود العشوائية، وتكرار "تود مساعدتك..." بلا نهاية. الحل الحقيقي نموذج Instruct ≥3B (Qwen2.5-3B/7B-Instruct)
- المرفق APK كان بيطلع `File type not supported` (التطبيق كان بيقبل أنواع محددة بس)
- proot القديم **ماكانش ممكن يشتغل أصلاً**: `libproot.so` محتاج `libtalloc.so.2` و`libandroid-shmem.so` وكانوا مش في الـ APK، والـ loader كمان ناقص. و`extractNativeLibs=false` معناه إن المكتبات مش بتتفك على الديسك
- الـ APK كان فيه 4 معماريات (185MB) — دلوقتي arm64 فقط

### اللي اتعمل
- workflow `fetch_proot_runtime.yml` (يدوي): بينزّل proot و libtalloc و libandroid-shmem من مستودع Termux الرسمي، بيعدّل NEEDED/RUNPATH بـ patchelf، وبيعمل commit في jniLibs (التقرير في native_binaries/proot2/readelf.txt)
- app.json: `targetSdkVersion 28` (عشان التنفيذ من مجلد بيانات التطبيق مسموح على أندرويد 10+) + `useLegacyPackaging` + الـ workflow بيفرض `expo.useLegacyPackaging=true` و `reactNativeArchitectures=arm64-v8a` وبيعطّل lint abort
- الترمنال: Alpine Linux عبر proot (تنزيل minirootfs + فحص SHA-256 + فك tar.gz بـ Kotlin + `apk add`)، مع fallback تلقائي لـ Android shell لو proot فشل. زرار "اختبار ذاتي" في الإعدادات بيطبع حالة كل حاجة
- شاشة ترمنال حقيقية في التطبيق (أيقونة في الهيدر)، والأداة `open_terminal`
- src/router.ts: بيشغّل الأدوات الواضحة تلقائياً قبل النموذج (افتح ترمنال، ابحث، رابط، أمر، ثبّت python...) فتشتغل حتى مع نموذج ضعيف
- localLLM: كشف Base/صغير + تحذيرات عربي، sampling ضد التكرار (penalty_repeat + DRY)، stop tokens، كاشف حلقات تكرار بيوقّف التوليد، وتنزيل Qwen2.5-3B/7B-Instruct من داخل التطبيق
- src/fileInspect.ts: قراءة أي ملف — APK (manifest, صلاحيات, مكونات, dex, توقيع) متطابق مع androguard، ZIP/Office/PDF (Flate)، وأي ملف ثنائي. بيشتغل بقراءة أجزاء صغيرة (APK 185MB في ثانية). بيفحص ولا بيفكّ لـ source ولا بيعدّل
- أدوات جديدة: inspect_file, open_terminal, python, pkg_install (الاتنين بيظهروا لما Alpine يتثبّت)

### ⚠️ لسه محتاج تجربة على الجهاز (مقدرناش نجرّب هنا — مفيش arm64 ولا أندرويد):
- اختبار ذاتي من الإعدادات: هل `exec from app data dir` = ALLOWED؟ وهل `proot --version` بيشتغل؟
- تثبيت Alpine ثم `apk add python3`
- أندرويد 12: ممكن يقتل العمليات الطويلة (Phantom Process Killer) — لو حصل: `adb shell settings put global settings_enable_monitor_phantom_procs false`
- جودة الأدوات تعتمد على النموذج: استخدم Instruct ≥3B

## جلسة 2026-10-07: تجربة حقيقية على الجهاز (فيديو + سكرين شوت) — Alpine شغّال فعلاً!

### تأكيد من الجهاز الحقيقي (Android 12، Redmagic)
- ✅ الـ APK اتثبّت (targetSdk 28 عدّى)
- ✅ Alpine Linux اتثبّت والشارة الخضرا "Alpine Linux" ظاهرة فعلاً في شاشة الترمنال = proot شغّال
- ✅ `search` و`fetch_url` شغّالين ممتاز على الإنترنت الحقيقي (طقس، أغاني، نتائج بالعربي ومصادر حقيقية)
- ❌ `apk add python3 py3-pip git nodejs npm curl` → **exit code 139 (SIGSEGV)** فوراً

### السبب (اتأكد من مصدرين مستقلين عبر البحث، منهم مشروع مشابه بالظبط - Cinder: Claude Code على أندرويد بنفس stack الـ proot+Alpine)
كيرنلات أندرويد (خصوصاً Qualcomm) بتتبني من غير `CONFIG_SYSVIPC`. و`apk-tools v3` بيقفل قاعدة بياناته بـ SysV semaphore وقت أي تثبيت/تحديث. الكيرنل مارفضش بس، بيخلي apk يعمل segfault. الحل: فلاج `--sysvipc` في proot (بيحاكي الـ SysV IPC في الـ userspace) — ده بالظبط اللي بتعمله Termux's proot-distro افتراضياً مع Alpine.

### الإصلاح
- `TerminalModule.kt`: ضفت `--sysvipc` لقائمة فلاجز proot في `alpineProotCommand()`
- `terminal/src/index.ts`: `formatResult()` دلوقتي بيفسّر أكواد الخروج بإشارة تلقائياً (`exit code 139 — SIGSEGV (crashed / segfault)` بدل رقم غامض) — أي كراش شبيه في المستقبل هيفهم سببه على طول

### واجهة جديدة: الترمنال بقى جوه الشات نفسه (مش شاشة منفصلة)
بناءً على طلب المستخدم ("زي Gemini، تفكير بصري حديث، مش شاشة لوحدها"):
- `gvrEngine.ts`: كل `StepEvent`/`AgentStep` بقاله `id` بيربط بداية التنفيذ بنهايته
- `router.ts`: "افتح الترمنال" دلوقتي بيشغّل أمر فحص فوري (`TERMINAL_PROBE`) كأداة `terminal` عادية بدل ما يفتح شاشة — يعني بيظهر كارت ترمنال حقيقي جوه الشات على طول
- `App.tsx`: مكونات جديدة — `ThinkingDots` (نقط نابضة متحركة)، `StepCard` (كارت لكل أداة، بيعرض كونسول حقيقي أسود مع بادج للأوامر terminal/python/apk)، `LiveTrace` (التفكير الحي وقت التوليد)، `ToolTrace` (سجل قابل للطي تحت كل رد خلص). أيقونة الترمنال في الهيدر لسه موجودة لمين عايز شاشة كاملة يدوي
- 95 اختبار (منطق + TypeScript + Kotlin syntax check) عدّوا قبل الـ build

### ⚠️ لسه محتاج تأكيد من الجهاز:
- `apk add python3 py3-pip git nodejs npm curl` بعد إضافة `--sysvipc` — المفروض يشتغل، لازم تجربة فعلية
- شكل الواجهة الجديدة (الكروت والتفكير الحي) على الجهاز

## جلسة 2026-10-08: Foreground Service + الحافظة + مراجعة قايمة أدوات

### Foreground Service (حماية الأوامر الطويلة)
- مشكلة حقيقية: أندرويد 12+ بيقفل العمليات الطويلة (apk add، pip install) لو قفلت الشاشة أو بدّلت تطبيق (phantom process killer / Doze)
- الحل: `GvrJobService.kt` — خدمة foreground حقيقية، بس بتظهر لو الأمر فعلاً أخد أكتر من 1.5 ثانية (تأخير + إلغاء تلقائي، فأوامر زي `ls` السريعة مش بتظهرلها إشعار أبداً)
- `targetSdk 28` معناه مش محتاجين نعلن `foregroundServiceType` (ده بقى إجباري بس من targetSdk 34) — تأكدت من ده عبر توثيق أندرويد الرسمي قبل التنفيذ
- التسجيل في المانيفست عن طريق `modules/terminal/android/src/main/AndroidManifest.xml` (ملف manifest بيتضم تلقائياً مع كل build، عكس `android/` اللي بيتمسح ويتبنى من جديد كل مرة بـ `expo prebuild --clean`)
- صلاحية `FOREGROUND_SERVICE` مضافة في app.json

### الحافظة (نسخ/لصق)
- أداتين: `copy_clipboard` و`read_clipboard` (+ aliases: copy/paste)
- زرار "نسخ الناتج" حقيقي على كل كارت ترمنال/بايثون/apk في الشات — نسخ فوري للـ clipboard

### مراجعة قايمة أدوات (ملف HTML من المستخدم فيه أدوات بيئة Claude نفسها)
فحصت فعلياً (مش تخمين) إمكانية إضافة كل أداة لـ Alpine على الموبايل:
- 🟢 آمن (hecho الآن أو قريب): ffmpeg (~30-50MB على arm64، مضاف لزرار التثبيت الافتراضي)، numpy/pandas/scipy/pillow (لها wheels جاهزة لـ musllinux+aarch64 — اتأكد من PyPI مباشرة، يدوي عند الحاجة، مش افتراضي)
- 🔴 خطر/غير مناسب: opencv-python و scikit-learn (معندهومش musllinux wheel، هيحاولوا يتكومبايلوا من المصدر على الموبايل)، playwright+متصفح (متصفح جوه proot = خطر كراش عالي زي مشكلة apk)، libreoffice/java الكاملة (كبار جداً)، tesseract (بديل أفضل موجود: OCR عبر نموذج الرؤية المحمّل بالفعل، بدون تحميل إضافي)
- apk-tools v3 مؤكد في فروع Alpine الحالية (فحصت pkgs.alpinelinux.org) — بيأكد تشخيص الـ sysvipc

### ⚠️ لسه محتاج تأكيد من الجهاز:
- `apk add python3 py3-pip git nodejs npm curl ffmpeg` بعد إصلاح sysvipc — هل نجح فعلاً؟
- هل الـ Foreground Service بيظهر إشعار ومنع القتل فعلاً وقت تثبيت حزمة كبيرة والشاشة مقفولة؟
- زرار نسخ الناتج شغال؟

## جلسة 2026-10-11: proot بيعمل crash حتى مع --version — جرّبنا بديل (proroot)

### الاكتشاف الحرج (من فيديو حقيقي + self-test)
`apk add` فشل تاني بعد إصلاح --sysvipc. فحصنا self-test مفصّل:
- `exec from app data dir: ALLOWED` ✅ (إصلاح targetSdk 28 شغال)
- `proot --version: exit=139` ❌ — proot نفسه بيعمل crash حتى من غير apk، من غير sysvipc، من غير أي تفاعل مع Alpine. ده معناه تشخيص sysvipc ماكانش كافي
- فحصنا الملفات الثنائية نفسها (ELF, readelf, nm): الربط سليم 100%، المكتبات كاملة، الاستهداف صحيح (NDK, API 24). مفيش دليل على خطأ في البناء
- أقوى احتمال (مش مؤكد 100% بدون adb logcat): نظام أندرويد على الجهاز بيرفض `ptrace()` للتطبيقات العادية (SELinux/seccomp). proot كله مبني على ptrace

### proroot: بديل تجريبي (ضُمّ، مش استبدل)
بعد بحث عميق ومراجعة (وتصحيح غلطتين مني: SDK 36 حقيقي، وQwen3.6/Gemma4 نماذج حقيقية — معرفتي بتوقف يناير 2026):
- `github.com/coderredlab/proroot` — إعادة بناء من الصفر بدون ptrace خالص (custom guest linker بدل)
- **مصدره مغلق.** فحصنا الملفات الثنائية الأربعة بالكامل: SHA-256 مطابق للمنشور، مفيش روابط/IP مدمجة، مفيش بيانات مشفّرة مخبأة، النصوص الداخلية متسقة مع هندسة حقيقية (قراءة سياق SELinux، التعامل مع seccomp)
- **القرار:** اتضاف **جنب** proot مش بدل منه. الـ self-test دلوقتي بيجرّب الاتنين ويعرض النتيجة جنب بعض للمقارنة الصادقة
- ملفات جديدة: `libproroot.so`, `libproroot-runtime.so`, `libproroot-bridge.so`, `libproroot-linker.so` في jniLibs
- `TerminalModule.kt`: `prorootCommand()`, `applyProrootEnv()`, `execProroot()`, `prorootSmokeTest()` + AsyncFunction `runProroot`
- `terminal/src/index.ts`: `Terminal.runProroot()` — **متاح لكن مش الافتراضي**. الاستخدام التلقائي (`run()`) لسه بيجرّب proot الأول، لحد ما نتأكد proroot شغال فعلاً على الجهاز

### ⚠️ الخطوة الجاية (قرار المستخدم، بالترتيب):
1. تجربة الـ self-test الجديد على الجهاز — المفروض يطلّع نتيجة proroot جنب proot
2. لو proroot اشتغل: نربطه كخيار افتراضي بدل proot المكسور
3. **لو عروروت فشلت برضو → "الحل المضمون" (آخر حل، اتفقنا عليه):** مش محتاج أي sandbox/ptrace خالص — نفس الوصفة اللي نجحت مع proot نفسه (تحميل حزم Termux + patchelf) لكن لـ python3/git/nodejs **الأصليين لـ Bionic** (مش proot-distro، دول حزم Termux العادية اللي أصلاً بتشتغل من غير أي proot). المشكلة الوحيدة المعروفة: مسارات Termux مرمّزة (`/data/data/com.termux/...`) محتاجة patchelf لتغيير RPATH، بالظبط زي ما عملنا مع libproot.so بنجاح. ده "مضمون" لأنه مش معتمد على ptrace يشتغل أصلاً

## الحالة الحالية: الـ APK بيتبني وبيتنشر — محتاج تجربة على الجهاز

### ✅ اتعمل وتأكد منه (بالـ build + اختبارات منطق في Node + TypeScript):
- build_apk.yml بيعدّي كامل: Gradle ← رفع على HuggingFace ← GitHub Release (apk-N)
- سبب فشل الـ build القديم: Expo SDK 51 بيتجاهل `newArchEnabled` في app.json، فـ llama.rn 0.12.x (محتاج New Architecture) ماكانش بيلاقي NativeRNLlamaSpec. الحل: خطوة في الـ workflow بتفرض `newArchEnabled=true` في android/gradle.properties بعد الـ prebuild
- الـ workflow محتاج `permissions: contents: write` عشان خطوة الـ Release
- src/localLLM.ts (مكتوب من جديد على llama.rn): استيراد GGUF من الجهاز (مع فحص الـ header والمساحة)، قائمة النماذج، تحميل (يجرّب context 8192 ← 4096 ← 2048 لو الذاكرة ما كفتش)، ربط ملف mmproj تلقائياً للرؤية، مسح نموذج، إعادة تحميل آخر نموذج عند الفتح مع حماية من crash-loop
- src/gvrEngine.ts: حلقة الوكيل بتاجات `<tool name="X">arg</tool>` + ذاكرة محادثة + مرفقات (الصورة بتروح للنموذج مباشرة)
- src/tools.ts: 19 أداة (search بـ4 محركات احتياطية، fetch_url بإعادة محاولة وredirect وJSON، download_file، terminal، javascript، calc، datetime، read/write/list/delete_file، device_info، open_url، share_text، permission، mem_*). الأدوات اللي لها أثر جانبي (terminal, javascript, write/delete/download, open_url, share) بتطلب موافقة المستخدم
- src/permissions.ts + قسم "الصلاحيات" في الإعدادات
- الترمنال الافتراضي بقى Android sh (toybox) عبر `runShell` — الفولدر الحالي بيتحفظ بين الأوامر. إصلاح deadlock كان بيحصل لما الـ output يعدّي 64KB
- app.json: صلاحيات (ملفات، وسائط، كاميرا، مايك، موقع، إشعارات، إدارة كل الملفات) + usesCleartextTraffic
- package.json: llama.rn مثبّت 0.12.9، وإصدارات Expo متطابقة مع SDK 51

### ⚠️ غير متأكد منه (لازم تجربة على الجهاز الحقيقي):
- تحميل نموذج GGUF حقيقي وسرعة التوليد على الجهاز
- الرؤية (نموذج + mmproj مطابق)
- مسار proot+bash القديم (`Terminal.runProot`): مفيهوش coreutils و /system مش مربوط جواه، غالباً builtins بس. مش مستخدم افتراضياً
- محركات البحث بتتحلل بالـ HTML بتاعها وممكن تتغيّر؛ لو كلها فشلت الأداة بترجّع الأخطاء بوضوح
- مفيش python ولا package manager

### الخطوة الجاية:
- تثبيت الـ APK وتجربة: استيراد نموذج، دردشة، أداة terminal، search، fetch_url
- لو في crash: `adb logcat | grep -i -E "gvr|llama|AndroidRuntime"`
- أدوات native إضافية (كاميرا، موقع، حافظة، نطق، keep-awake) تتضاف في build منفصل عشان ما نكسرش الـ build

### ملاحظات تقنية مهمة (متعرفش تتنسى):
1. أي ملف على GitHub أكبر من 1MB لازم يتقرأ عبر Git Blobs API مش Contents API العادي
2. termux/proot repo مفهوش أي releases — proot اتبنى من المصدر عبر Docker
3. termux-exec-system-linker-exec هو shell script مش ELF binary — متستخدموش كـ jniLib
4. الـ workflows التانية (Kaggle training) بتعمل commits باستمرار على main — أي commit جديد لازم يستخدم retry+rebase
5. أي تعديل على App.tsx أو src/*.ts: شغّل `tsc --noEmit` الأول — الـ build بيعدّي حتى لو في دوال مش موجودة (Metro مبيتحققش من الـ exports)، والغلط بيظهر وقت التشغيل بس
6. لوج الـ Actions الكامل مش متاح لو الشبكة محدودة؛ الـ workflow بيكتب gradle.log على الريبو

### أوامر مفيدة:
```
تشغيل الـ build: POST /repos/alhsryahmd266-jpg/omega-ai/actions/workflows/build_apk.yml/dispatches  {"ref":"main"}
قراءة آخر نتيجة: GET /repos/alhsryahmd266-jpg/omega-ai/contents/gradle.log
```


## حالة الريبو — 2026-10-16 (آخر APK: apk-80، الفرع: feature/browser-tool)

### اللي اتبنى ومتجرّبش على الجهاز بعد
- **المتصفح:** `browser` (يعرض النص والروابط والعناصر المرقمة)، `click_link`، `browser_click`، `browser_type`، `tool_evaluator`. الكود في `browserBridge.tsx` وWebView مخفي.
- **المهارات:** `skill` + `skills.ts` (github-install-run، python-project-setup)، و`tool_search`.
- **الأسرار:** `request_secret` (خانة مشفّرة بـ expo-secure-store)، و`github_api` (التوكن ما يظهرش للنموذج).
- **الحلقة:** `PLAYBOOK` بيتحط في تعليمات النموذج، وراوتر بيفتح الأدوات مباشرة لبعض الأوامر (الساعة، الخانة المشفرة، فتح التطبيقات، صفحة إمكانية الوصول، التحديث).
- **التذكير والمنبه والملاحظات:** `set_alarm`، `remind`، `note_add`، `note_list`.
- **التحكم في الشاشة:** خدمة Accessibility (`GvrAccessibilityService.kt`): `screen_read`، `screen_tap`، `screen_type`، `screen_back`، `screen_allow` (تأكيد)، `screen_enable`. البنوك والدفع محجوبة بقائمة في الكود.
- **فتح التطبيقات:** `open_app` (بالـ package)، و`find_app` (بالاسم العربي أو الإنجليزي، ويقرأ الشاشة بعد الفتح). الأسماء الشائعة في `APP_ALIASES`.
- **الفقاعة:** `bubble_show` و`bubble_hide`: خدمة مثبتة بإشعار، ضغطة = إيقاف/تشغيل الـ agent، ضغطة مطولة = قفل. بترجع بعد الريستارت لو كانت شغالة (`GvrBootReceiver`).
- **الجلسات:** `session_start` و`session_stop` و`session_status`: هدف + حد رسايل + حد وقت، تأكيد مرة في البداية، أول رسالة لازم تقول إنه ذكاء صناعي، وكلمات الفلوس والباسورد والبنوك ممنوعة.
- **النماذج:** `modelSelector.ts` بيحسب الذاكرة المطلوبة ويختار النموذج حسب الرام (`model_pick`). الروابط لسه `null` لحد ما تتحقق من الـ build.
- **التحديث:** `app_update` بيفتح أحدث ملف APK من الـ Releases، والتثبيت بتأكيدك.
- **الكلام:** `speak` و`speak_stop` (صوت الجهاز، أوفلاين).

### ملاحظات حرجة
- **الاختبار على الجهاز صفر تقريباً.** الـ build بينجح، لكن أغلب الأدوات لسه ما اتجربتش على الهاتف. أول اختبار لازم يكون: فتح خدمة الوصول، وقراءة الشاشة، والفقاعة.
- **خدمة الوصول:** على Android 12 ما ظهرتش في الإعدادات عند المستخدم. السبب غير مؤكد، والتشخيص الجاي هيحدده.
- **الفيديو:** استخراج الإطارات والنص والصوت ممكن، لكن الفهم البصري لازم نموذج رؤية، وده مش في المشروع.
- **النموذج 9B:** بيوقف بدري، بيرجّع ردود فاضية أحياناً، وبيخلط اللغات.
- **التوكن:** اتكتب في سجل الشات أكتر من مرة، والأفضل إلغاؤه.

### قرارات مفتوحة
1. **التعرف على الكلام:** خدمات جوجل (أدق وأسهل) ولا whisper أوفلاين (أبطأ، أدق أقل، والكلام ما بيخرجش من الجهاز).
2. **النموذج الافتراضي:** Qwen2.5-7B-Instruct لاستدعاء الأدوات، ولا Qwen2.5-Coder-14B للمهام الصعبة.
3. **دمج الفرع في main:** لازم Pull Request ومراجعة قبل الدمج.

### خطة التنظيف (لم تُنفّذ بعد، تحتاج موافقة)
- حذف ملفات الـ build المؤقتة اللي اتضافت أثناء التجارب، ومجلدات التجارب القديمة اللي مش بتتبني.
- مراجعة `BUILD_TRIGGER.txt` وإلغاء اللي مش مستخدم.
- توحيد أسماء المهارات والتعليقات بالعربي والإنجليزي.
- حذف أي ملف فيه أسرار (لو اتكتب بالخطأ).

### الخطوة الجاية المقترحة
1. تشخيص (`diagnose`): يعرض حالة خدمة الوصول والظهور فوق التطبيقات والتطبيقات المسموح بيها والنموذج المحمّل.
2. اختبار على الجهاز: كل أداة من الصفر بالترتيب.
3. قرارات الكلام والنموذج.
