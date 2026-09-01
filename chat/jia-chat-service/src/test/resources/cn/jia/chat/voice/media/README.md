# Voice media fixtures

`mediarecorder-valid.webm` is a Chromium MediaRecorder WebM/Opus capture with finalized bounded Duration metadata added to its Info element. The missing-duration variant preserves the raw Chromium output.

The MP4 fixtures are deterministic ISO-BMFF metadata slices with an `mp4a` audio sample entry, used to exercise the same `moov/mvhd` shape emitted by MP4/AAC MediaRecorder implementations without storing user speech. No fixture contains intelligible speech.
