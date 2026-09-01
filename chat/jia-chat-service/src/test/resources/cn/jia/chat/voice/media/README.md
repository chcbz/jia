# Voice media fixtures

`mediarecorder-chromium-unmodified.webm` is the byte-exact output of Chromium
133.0.6943.141 `MediaRecorder` using `audio/webm;codecs=opus`, a fake browser
audio device, 100 ms data slices, and a 1.3 second recording. Chromium leaves
`Info/Duration` absent; the fixture SHA-256 is
`efeab25e2ba0fcf53be304d723a10dd5c4bc97b99f74e540ce1231d6dec63213`.
It contains no intelligible speech and was not metadata-rewritten.

`mediarecorder-valid.webm` is the earlier Chromium-shaped WebM/Opus sample with
finalized bounded `Duration`. `mediarecorder-missing-duration.webm` is a
single-timestamp raw sample whose duration cannot be derived safely and must
fail closed. The derived-overlong, forged-track and wrong-codec WebM variants
are deterministic mutations used only as negative fixtures.

`mediarecorder-valid.mp4` and `mediarecorder-overlong.mp4` are genuine,
playable ISO-BMFF/AAC-LC files generated locally with the Linux x86-64 FFmpeg
6.0 binary distributed by `ffmpeg-static@5.2.0` (binary SHA-256
`ed652b2f32e0851d1946894fb8333f5b677c1b2ce6b9d187910a67f8b99da028`).
They use a browser-compatible mono 48 kHz AAC-LC stream and fast-start layout:

```
DURATION=1.2 # use 46.1 for the overlong fixture
ffmpeg -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=${DURATION}" \
  -c:a aac -profile:a aac_low -b:a 64k -ac 1 -ar 48000 \
  -movflags +faststart -y mediarecorder-valid.mp4
```

The positive is 1.2 seconds and has SHA-256
`9a485ead7b23ad51457567e9d9a27a759a6ba0ac63d7256e3b944e174b523458`.
The 46.1-second overlong negative has SHA-256
`bac1ae316e95c8d032473837b81ca41177e9c5c1aa13192a27c1bfd33703597b`.
These are reproducible browser-compatible encoder fixtures, not claimed as
byte-exact browser `MediaRecorder` output.

The forged `mp4a` AudioSpecificConfig, missing `esds`, missing `mdat`, and
out-of-range sample extent negatives have SHA-256 values
`5f33aeb35c43f60827ca18a537f43877a0e783ff1654d6197cf749f3acdb35c4`,
`86a86d03d05a7d0a8a444d1e1ccc52f8accaaf88a444821097c35c8f70278a29`,
`1445ffcee14d9883cf848c4d6f0c306b7746738301da5928abb239177893c620`, and
`3413c0783593ef28e85acf698ce899a769229f2c1e4d382bf579673e6a718a49`.
The earlier forged-handler and wrong-codec slices remain negative-only probes.
No fixture contains intelligible speech.
