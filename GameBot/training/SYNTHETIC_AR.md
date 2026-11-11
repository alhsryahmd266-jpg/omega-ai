# داتا تدريب قريبة من رسومات لعبتك (Synthetic data من أصول لعبتك)

أقرب رسومات للعبتك هي **أصول لعبتك نفسها**: نفس الشخصيات والأسلحة والخرائط. بدل ما تصنّف يدوي، خلّي المحرك يرسم مشاهد كتير متنوعة ويطلّع الصناديق (boxes) لوحده. ده مش تعديل في اللعبة: بتعمل **مشهد اختبار منفصل** في مشروعك.

## Unity (Perception package)
1. Window ← Package Manager ← "+" ← Add package by name: `com.unity.perception`.
2. على الكاميرا ضيف **Perception Camera**، وضيف **Bounding Box 2D Labeler**، واعمل **IdLabelConfig** فيه الكلاسات: `enemy` و`enemy_head` و`ally`.
3. على كل prefab شخصية ضيف **Labeling** وحط الكلاس (الجسم enemy، وخلّي الرأس كائن فرعي عليه enemy_head، والفريق ally).
4. ضيف **Fixed Length Scenario** وRandomizers: إضاءة ووقت اليوم، مكان الشخصيات والمسافة (قريب/متوسط/بعيد)، زاوية الكاميرا وFOV، أوضاع وحركة الشخصيات، خلفيات الخرائط، تغطية جزئية (شجر/حيطان).
5. شغّل 3000–10000 إطار. الـ output بصيغة **SOLO** (فولدر فيه `sequence.N`).
6. حوّل لصيغة YOLO:
   `python perception_solo_to_yolo.py --src <فولدر dataset> --out dataset_yolo --classes enemy enemy_head ally`
7. اعمل zip لـ `dataset_yolo` وارفعه على Kaggle كـ Dataset، وشغّل `kaggle_train_detector.ipynb`، وحمّل `detector.tflite` واستورده في GameBot.
(أسماء المكوّنات ممكن تختلف شوية حسب نسخة الباكدج.)

## محرك تاني (Unreal / Godot / غيره)
خلّي مشهد الاختبار يكتب لكل إطار ملف JSON بصناديق الشاشة، وحوّله بـ `autolabel_from_game_json.py`.

## بعد كده
- اخلط الداتا الصناعية مع لقطات حقيقية من اللعبة (سجّلها بـ GameBot وصنّفها على الهاتف)، وكمّل تدريب (fine-tune) عليها.
- الأوزان الجاهزة بتاعة YOLO11/YOLO26 (COCO) بتبدأ منها وبتسرّع التدريب، بس النموذج لازم يتدرّب على رسومات **لعبتك**.
