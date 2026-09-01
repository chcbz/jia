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

The MP4 fixtures are deterministic ISO-BMFF metadata slices with an `soun`
handler and `mp4a` audio sample entry. The forged-handler and wrong-codec
variants prove that `mvhd` duration and declared MIME alone are insufficient.
No fixture contains intelligible speech.
