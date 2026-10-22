/** M4 — images: read the text in a screenshot (OCR), build a collage with captions, export JPEG + WebP, deliver it. */
import { assert, fs, path, W, OUT, mission, ask, call, full, toolsUsed, attachmentFor, sh, resetMission, Sharing } from '../missions.test';

mission('M4', 'images: OCR + collage + JPEG/WebP export + delivery', async () => {
  await resetMission();
  fs.mkdirSync(path.join(W, 'uploads'), { recursive: true });
  for (let i = 1; i <= 4; i++) fs.copyFileSync(`/home/claude/frames/f_0${i}.png`, path.join(W, 'uploads', `frame${i}.png`));

  const COLLAGE = `python3 - <<'PYEOF'
import os
from PIL import Image, ImageDraw, ImageFont
W, H, BAR = 360, 800, 90
try: font = ImageFont.truetype('DejaVuSans-Bold.ttf', 64)
except Exception: font = ImageFont.load_default(64)
canvas = Image.new('RGB', (W * 2, (H + BAR) * 2), 'black')
d = ImageDraw.Draw(canvas)
for i in range(4):
    im = Image.open(f'/workspace/uploads/frame{i + 1}.png').convert('RGB').resize((W, H))
    x, y = (i % 2) * W, (i // 2) * (H + BAR)
    canvas.paste(im, (x, y + BAR))
    d.text((x + 20, y + 8), f'FRAME {i + 1}', fill='white', font=font)
os.makedirs('/workspace/out', exist_ok=True)
canvas.save('/workspace/out/collage.jpg', quality=85)
canvas.save('/workspace/out/collage.webp', quality=80)
for f in ('collage.jpg', 'collage.webp'):
    print(f, Image.open('/workspace/out/' + f).size, os.path.getsize('/workspace/out/' + f), 'bytes')
PYEOF`;

  // The user attaches one screenshot and asks what is written in it: with no vision model the router OCRs it.
  const { res } = await ask('اقرا اللي مكتوب في الصورة دي، وبعدين اعمل كولاج 2x2 من الأربع صور بعنوان لكل واحدة وصدّره JPEG و WebP وسلّمهولي', [
    call('view_image', '/workspace/uploads/frame2.png\nwhat text is visible?'),
    call('terminal', COLLAGE),
    call('view_image', '/workspace/out/collage.jpg'),
    call('deliver_file', '/workspace/out/collage.webp'),
    'قريت النص من الصورة، وعملت الكولاج بصيغتين وسلّمتلك الـ WebP.',
  ], attachmentFor('frame1.png', 'image'));

  assert.equal(toolsUsed[0], 'view_image', 'the router should OCR the attached screenshot by itself');
  const ocr1 = full[0];
  assert.match(ocr1, /OCR only/);
  assert.match(ocr1, /GVR|Agent|app-release|unzip/i, `OCR found nothing useful:\n${ocr1.slice(0, 300)}`);
  assert.match(ocr1, /Arabic OCR data is not installed/, 'missing Arabic data must be reported, not silently dropped');

  const coll = full[toolsUsed.indexOf('terminal')];
  assert.match(coll, /collage\.jpg \(720, 1780\)/);
  assert.match(coll, /collage\.webp \(720, 1780\)/);
  const identify = sh(`python3 -c "from PIL import Image; a=Image.open('out/collage.jpg'); b=Image.open('out/collage.webp'); print(a.format, a.size, b.format, b.size)"`);
  assert.equal(identify, "JPEG (720, 1780) WEBP (720, 1780)");

  const ocr2 = full[toolsUsed.lastIndexOf('view_image')];
  assert.match(ocr2, /FRAME/i, `captions should be readable by OCR:\n${ocr2.slice(0, 300)}`);

  assert.ok(fs.existsSync(path.join(OUT, 'collage.webp')), 'delivered copy in Downloads/GVR');
  assert.equal(Sharing.calls.at(-1)?.opts.mimeType, 'image/webp');
  assert.match(res.answer, /الكولاج/);
});
