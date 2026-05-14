# KEEB LocalAI Fork Notices

This fork is based on AnySoftKeyboard and keeps the upstream Apache-2.0 license,
source notices, and attribution files intact.

## Imported KEEB LocalAI Source

- Source copied from: `/Users/am.will/Applications/keeb/core/model-manager`
- Source copied from: `/Users/am.will/Applications/keeb/core/voice`
- Integration target: `ime/localai`
- Purpose: on-device Whisper model management, microphone recording, voice activity
  detection, native whisper.cpp bridge, and push-to-talk transcription.

## whisper.cpp

- Source copied from: `/Users/am.will/Applications/keeb/third_party/whisper.cpp`
- Upstream: `https://github.com/ggml-org/whisper.cpp`
- Pinned source noted by the preserved KEEB reference: `3e9b7d0fef3528ee2208da3cdb873a2c53d2ae2f`
- Local path in this fork: `third_party/whisper.cpp`
- License: MIT, preserved at `third_party/whisper.cpp/LICENSE`

## Whisper Model Artifacts

Model binaries are not bundled in this repository. The app downloads verified
ggml model files from the model catalog into app-private storage at
`files/whisper-models`, reusing existing files only after checksum validation.
