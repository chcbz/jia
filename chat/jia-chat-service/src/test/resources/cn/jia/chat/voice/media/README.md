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

## Fragmented browser-container remediation fixtures

`mediarecorder-chromium-timesliced-multicluster.webm` is byte-exact output from
`Chromium 133.0.6943.141 Fedora Project` using the committed
`capture-mediarecorder.html` script with actual recorder MIME
`audio/webm;codecs=opus`, `durationMs=33000`, and `timesliceMs=400`. The capture
used an `AudioContext` oscillator routed to `MediaStreamDestination`; it
contains no speech. Chromium emitted 34 non-empty `dataavailable` chunks. The
concatenated 84,957-byte output has SHA-256
`d6659aff33917d981280c37702fcb970c421a6e0fc64ec8e19459555c11febad` and the
bounded inspector reports 32,969 ms from the final Opus packet end.

The exact browser launch was:

```
/usr/bin/chromium-browser --headless --no-sandbox --disable-gpu \
  --autoplay-policy=no-user-gesture-required --user-data-dir="$PROFILE" \
  --remote-debugging-port=0 \
  "file://$PWD/capture-mediarecorder.html?mime=audio/webm%3Bcodecs%3Dopus&durationMs=33000&timesliceMs=400"
```

A bounded CDP `Runtime.evaluate` poll read `document.body.textContent` only
after its `DONE|...|base64` sentinel, then decoded the final field byte-for-byte.
The structural probe found one unknown-sized Segment and two Clusters at byte
offsets 146 and 78,095; both Clusters use the eight-byte unknown-size VINT
`01ffffffffffffff`. Tracks declares audio track 1 with `A_OPUS`. The
`nested-id`, `unknown-child`, `truncated`, and `overlong` WebM files are
single-purpose deterministic byte mutations. The overlong mutation changes
only the second Cluster Timecode from 30,023 ms to 47,000 ms.

This Chromium build reported `MediaRecorder.isTypeSupported('audio/mp4') ==
true`, but `audio/mp4;codecs=mp4a.40.2 == false`; constructing `audio/mp4`
selected actual MIME `audio/mp4;codecs=opus`. It therefore cannot provide an
AAC fixture and is not represented as doing so.

`mediarecorder-chromium-fragmented-opus.mp4` preserves that actual 3.2-second,
400 ms-timesliced Chromium result as a negative codec fixture. It is 6,984
bytes with SHA-256
`91bd2f4269403cc11d3fd69ca2831e20410db3e4a0b2b8b614af13594358b213`.
Its structural probe reports `mvex=1`, `trex=1`, and three each of
`moof/traf/tfhd/tfdt/trun/mdat`, with `Opus+dOps` and no `mp4a/esds`; the
inspector must reject it as invalid MP4/AAC rather than trusting the MIME.

`mediarecorder-fragmented-valid.mp4` and
`mediarecorder-fragmented-explicit-base.mp4` are genuine playable fragmented
ISO-BMFF/AAC-LC files generated with the same FFmpeg 6.0 static binary and
binary digest documented above. The exact commands were:

```
ffmpeg -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=3.2' \
  -c:a aac -profile:a aac_low -b:a 64k -ac 1 -ar 48000 \
  -movflags +empty_moov+default_base_moof -frag_duration 400000 \
  -y mediarecorder-fragmented-valid.mp4

ffmpeg -f lavfi -i 'sine=frequency=523.25:sample_rate=48000:duration=1.1' \
  -c:a aac -profile:a aac_low -b:a 64k -ac 1 -ar 48000 \
  -movflags +empty_moov -frag_duration 400000 \
  -y mediarecorder-fragmented-explicit-base.mp4
```

The 28,387-byte default-base fixture has SHA-256
`87c33fc5bcaa57a2d95559c6da051612c00652b13118111334130823cc79f928`;
its structural probe reports `mvex=1`, `trex=1`, `moof=8`, `traf=8`,
`tfhd=8`, `tfdt=8`, `trun=8`, `mdat=8`, `mp4a=1`, and `esds=1`. The
10,590-byte explicit-base fixture has SHA-256
`21bcd98786f809b92df57f211e6f9ffb89c8c3246edc9f555a365ece6adc7d6e`
and uses `base-data-offset-present` plus per-sample durations in its final run.
Both decode to mono 48 kHz PCM with the documented FFmpeg binary.

`mediarecorder-fragmented-overlong.mp4` was generated identically with a
46.1-second sine, 250 ms fragments, and has SHA-256
`1df6f0ab7167216bbf1ab4218e1e870116df092a60363aee1421b7c4c03f2b38`
(`moof=181`, `mdat=181`). The remaining fragmented negatives independently
cover missing effective duration defaults, negative/out-of-range data offsets,
backward decode time, unsupported versions/flags, excessive box/fragment/sample counts,
absent media,
missing `esds`, truncation, and version-specific short version-0/version-1
`mdhd`/`mvhd` boxes.
They are mutations only and are not expected to decode.
