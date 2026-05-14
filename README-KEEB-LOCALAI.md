# KEEB LocalAI Keyboard

This sibling fork turns AnySoftKeyboard into the new KEEB product while keeping
the original `/Users/am.will/Applications/keeb` source tree preserved as a
reference.

## Product Identity

- App id: `com.amwill.keeb`
- App/IME label: `KEEB`
- Keyboard foundation: AnySoftKeyboard layouts, gesture typing, suggestions,
  themes, emoji, settings, language packs, and input behavior.

## LocalAI Voice

- LocalAI source lives in `ime/localai`.
- whisper.cpp lives in `third_party/whisper.cpp`.
- Models are stored in app-private `files/whisper-models`.
- The ASK voice key no longer starts Google/system cloud voice input. It starts
  local push-to-talk recording, shows Stop/Cancel, transcribes with whisper.cpp,
  and commits the transcript through the active input connection.
- Model management is available from Settings > LocalAI Voice.

See `NOTICE-LOCALAI.md` for attribution and model artifact notes.
