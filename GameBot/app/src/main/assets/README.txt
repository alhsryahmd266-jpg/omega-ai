Put YOUR trained detector here to embed it in the APK (no import step):
  detector.tflite   (YOLO11 / YOLOv8 raw export, or YOLO26 end-to-end export, float32 or int8)
  labels.txt        (one class per line, same order as training: enemy, enemy_head, ally)
Then push; the CI build ships it inside the app and GameBot loads it automatically on first run.
No generic model is bundled: a detector has to be trained on frames of your own game (see training/).
Note: models trained with Ultralytics are covered by AGPL-3.0 (publish the source of what you distribute, or get an Enterprise license). Private use is fine.
