# تدريب نموذج الكشف لـ GameBot

الهدف: نموذج صغير (YOLO nano) بيشوف الأعداء على شاشة **لعبتك** ويطلع Box لكل واحد.

## 1) اجمع صور
في التطبيق فعّل "سجّل لقطات للتدريب" وشغّل البوت (أو العب إنت). اللقطات بتتحفظ في
`Android/data/com.ahmedxg.gamebot/files/frames/` (JPEG). اجمع كام ألف لقطة متنوعة (خرائط، إضاءة، مسافات، أسلحة).

## 2) صنّف
- **الأفضل للعبتك:** خلّي اللعبة (نسخة QA) تسجّل مع كل لقطة JSON بمواقع اللاعبين على الشاشة، وحوّلها تلقائي:
  `python autolabel_from_game_json.py --frames frames/ --json labels_json/ --out dataset/labels`
  شكل الـ JSON: `{"frame":"f_123.jpg","width":854,"height":480,"entities":[{"cls":"enemy","x1":10,"y1":20,"x2":60,"y2":150}]}`
- أو يدوي بأي أداة (CVAT / LabelImg / Roboflow) بصيغة YOLO.
الكلاسات: `0 = enemy` (إجباري) و`1 = enemy_head` و`2 = ally` (اختياريين، ally = صديق عشان البوت مايضربوش). حافظ على نفس الترتيب في `labels` جوّه التطبيق.

## 3) رتّب الداتا
```
dataset/images/train  dataset/images/val
dataset/labels/train  dataset/labels/val
```
انسخ `data.example.yaml` إلى `data.yaml` وعدّل المسار.

## 4) درّب على كمبيوتر (GPU أفضل)
```
pip install ultralytics
python train_detector.py --data data.yaml --imgsz 416 --epochs 100
```
بيطلع ملف `.tflite` (float). الموديل ممكن يكون `yolo11n.pt` أو `yolo26n.pt`، والتطبيق بيقرا الاتنين. لو عايز أسرع: `--int8` (محتاج بيانات تمثيلية من data.yaml).

## 5) على الهاتف
استورد الـ `.tflite` من التطبيق، اضغط "قياس سرعة النموذج"، وجرّب CPU مقابل GPU. اختار أسرع واحد يحقق زمن قرار أقل من ~30ms.

## الطريق كله من الهاتف (من غير كمبيوتر)
1. في GameBot فعّل **"سجّل لقطات للتدريب"** وخلّي دقة الالتقاط 640، والعب أو شغّل البوت 10–20 دقيقة (لقطات متنوعة).
2. افتح **"تصنيف اللقطات على الهاتف"**: اختار الكلاس (enemy / enemy_head / ally)، اسحب مربع حوالين كل عنصر، واضغط ▶ للصورة اللي بعدها. الصور اللي مفيهاش عناصر برضه مفيدة. كفاية 300–800 صورة تبدأ بيهم.
3. اضغط **تصدير**: بيطلع `Download/gamebot_dataset.zip`.
4. على **Kaggle** من متصفح الهاتف: Create ← Dataset ← ارفع الملف. بعدين افتح `kaggle_train_detector.ipynb` (File ← Import notebook من الريبو)، Add Input ← الـ Dataset بتاعك، Accelerator ← GPU، وRun All.
5. لما يخلص: تبويب **Output** ← `detector.tflite` ← Download (بينزل في Downloads).
6. في GameBot: **"استيراد نموذج .tflite"** ← اختار الملف ← **"قياس سرعة النموذج"** ← الوضع AI ← تشغيل.
(لو عايز النموذج جوّه الـ APK: ارفعه في `app/src/main/assets/` باسم `detector.tflite`.)

## نصايح للجودة
- دقة التدريب `imgsz` = اللي هتشغّل بيها (416 أو 320 أسرع).
- لو بيغلط في ناس صاحبة/عدو: ضيف كلاس `ally` وصنّفه (البوت بيتجاهله).
- زوّد بيانات الحالات الصعبة (بعيد، جزئي ورا غطا، إضاءة ضعيفة).
- ابدأ بمستوى Easy/Normal وراجع اللقطات قبل ما ترفع لـ Pro/Max.
