/** M5 — video (the user's own screen recording): probe, frames, clip, audio, GIF, contact sheet — with a failing codec first. */
import { assert, fs, path, W, OUT, mission, ask, call, full, lastFull, toolsUsed, attachmentFor, sh, resetMission, toolOutcome, Sharing } from '../missions.test';

mission('M5', 'video: probe, frames, trim+compress, audio, GIF, contact sheet, delivery', async () => {
  await resetMission();
  fs.mkdirSync(path.join(W, 'uploads'), { recursive: true });
  fs.copyFileSync('/home/claude/rec.mp4', path.join(W, 'uploads', 'screen.mp4'));
  const IN = '/workspace/uploads/screen.mp4';

  const { res } = await ask('عندي تسجيل شاشة: هاتلي معلوماته، واستخرج 6 لقطات، وقص 10 ثواني من تاني 27 وصغّر حجمه، واستخرج الصوت، واعمل GIF ولوحة لقطات، وسلّمني المقطع', [
    call('terminal', `ffprobe -v error -show_entries format=duration,size:stream=codec_name,width,height -of default=nw=1 ${IN}`),
    call('terminal', `mkdir -p /workspace/out && ffmpeg -v error -y -i ${IN} -vf "fps=6/52.7,scale=360:-2" /workspace/out/vf_%02d.png && ls /workspace/out | wc -l`),
    // a codec the machine does not have (very common on phones) → the model must read the error and switch
    call('terminal', `ffmpeg -v error -y -ss 27 -t 10 -i ${IN} -vf scale=-2:640 -c:v libx264 -crf 28 -preset veryfast -c:a libfdk_aac -b:a 64k /workspace/out/clip.mp4`),
    (_p, rest) => {
      if (toolOutcome(lastFull()) !== 'error') return 'الأمر اشتغل من أول مرة.';
      assert.match(lastFull(), /Unknown encoder|libfdk_aac/i);
      rest.unshift(call('terminal', `ffprobe -v error -show_entries format=duration -show_entries stream=codec_type,height -of default=nw=1 /workspace/out/clip.mp4`));
      return call('terminal', `ffmpeg -v error -y -ss 27 -t 10 -i ${IN} -vf scale=-2:640 -c:v libx264 -crf 28 -preset veryfast -c:a aac -b:a 64k /workspace/out/clip.mp4 && echo clip-ok`);
    },
    call('terminal', `ffmpeg -v error -y -i ${IN} -vn -c:a copy /workspace/out/audio.m4a && ls -la /workspace/out/audio.m4a`),
    call('terminal', `ffmpeg -v error -y -ss 27 -t 4 -i ${IN} -vf "fps=8,scale=240:-1:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse" /workspace/out/preview.gif && echo gif-ok`),
    call('terminal', `ffmpeg -v error -y -i ${IN} -vf "fps=1/4,scale=180:-2,tile=4x3" -frames:v 1 /workspace/out/sheet.png && echo sheet-ok`),
    call('view_image', '/workspace/out/sheet.png'),
    call('deliver_file', '/workspace/out/clip.mp4'),
    'خلصت: المعلومات، 6 لقطات، مقطع 10 ثواني مضغوط، الصوت، GIF ولوحة لقطات، وسلّمتلك المقطع.',
  ], attachmentFor('screen.mp4', 'video'));

  // the failing codec was recognised and replaced
  const failed = full.findIndex(r => /Unknown encoder|libfdk_aac/i.test(r));
  assert.ok(failed >= 0, 'the missing codec should have been reported');
  assert.equal(toolOutcome(full[failed]), 'error');
  assert.ok(full.some(r => /clip-ok/.test(r)), 'the alternative (aac) must have worked');

  // independent checks with ffprobe / PIL
  assert.match(full[0], /duration=52\.7/);
  assert.match(full[0], /width=720\s+height=1600|height=1600/);
  assert.equal(fs.readdirSync(path.join(W, 'out')).filter(f => /^vf_\d+\.png$/.test(f)).length, 6);
  const clip = sh(`ffprobe -v error -show_entries format=duration:stream=codec_type,height -of default=nw=1 out/clip.mp4`);
  const dur = Number(/duration=([\d.]+)/.exec(clip)![1]);
  assert.ok(dur > 9.4 && dur < 10.8, `clip duration ${dur}`);
  assert.match(clip, /height=640/);
  assert.match(clip, /codec_type=audio/, 'the clip must keep an audio track');
  assert.ok(fs.statSync(path.join(W, 'out/clip.mp4')).size < fs.statSync(path.join(W, 'uploads/screen.mp4')).size, 'compressed clip must be smaller');
  const audioDur = Number(/duration=([\d.]+)/.exec(sh(`ffprobe -v error -show_entries format=duration -of default=nw=1 out/audio.m4a`))![1]);
  assert.ok(audioDur > 52 && audioDur < 53.5, `audio duration ${audioDur}`);
  assert.match(sh(`ffprobe -v error -count_frames -select_streams v:0 -show_entries stream=nb_read_frames,width -of default=nw=1 out/preview.gif`), /nb_read_frames=(\d{2,})/);
  assert.equal(sh(`python3 -c "from PIL import Image; print(Image.open('out/sheet.png').size)"`), '(720, 1200)');

  const ocr = full[toolsUsed.lastIndexOf('view_image')];
  assert.match(ocr, /OCR only/);
  assert.ok(fs.existsSync(path.join(OUT, 'clip.mp4')), 'delivered copy in Downloads/GVR');
  assert.equal(Sharing.calls.at(-1)?.opts.mimeType, 'video/mp4');
  assert.match(res.answer, /خلصت/);
});
