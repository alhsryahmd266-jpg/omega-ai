# GVR-Chat Project State (آخر تحديث: 2026-10-05)

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
