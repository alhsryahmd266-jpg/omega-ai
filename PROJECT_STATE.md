# GVR-Chat Project State (آخر تحديث: 2026-10-06)

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
