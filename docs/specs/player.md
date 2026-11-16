# Plex player

## Purpose
Play Plex films and episodes with Plex-style controls, subtitles and audio choice.

## Behaviour
- Controls: logo art and episode line top left, clock top right; seek bar with elapsed time,
  "Ends at" and remaining time; buttons back and forward (lengths from the settings, default
  10 s / 30 s), play/pause; pills for Subtitles, Audio and Settings. Auto-hide after 5 s.
- "Ends at" = current time + remaining runtime; clock and end time tick every second, also
  while paused. While seeking they use the target position.
- Subtitles panel: Off, the file's tracks (embedded and external), "Search for subtitles…":
  language (device language, German, English, Turkish), Plex's online search (OpenSubtitles
  through the Plex server), download, wait until Plex lists the new stream, reload at the same
  position. Choices are saved to Plex.
- Audio panel: the file's audio tracks; choice saved to Plex.
- Starts with the subtitle and audio stream Plex has selected.
- Subtitle look: bold DM Sans; colour, background, size and position from the settings.
  Defaults: white, no box with a black outline, 21 dp (100 %), 7 % above the bottom.
- Settings panel (pill "Settings"): one line per setting with its current value; OK opens its
  choices, picking one applies it at once (subtitles change on screen) and returns to the list,
  Back returns without change. Stored on the device for all titles:
  - Subtitle size: 50, 75, 100, 125, 150 % (of 21 dp).
  - Subtitle colour: White, Yellow, Light grey, Cyan.
  - Subtitle background: None (black outline), Half-transparent box, Solid box.
  - Subtitle position: Low (3 %), Normal (7 %), High (15 %), Higher (25 % above the bottom).
  - Skip back: 5, 10, 15, 30 s. Skip forward: 10, 15, 30, 60 s. Used by the buttons, Left/Right
    with hidden controls and the rewind / fast-forward keys.
- Requests audio focus: other apps (e.g. YouTube) pause; Masal pauses when another app takes it.
- The screen stays on only while playing or buffering; paused or failed, the TV may dim and sleep.

## Remote keys
- Controls hidden: OK, Up or Down show them; Left/Right skip back / forward (settings) and show the seek bar.
- On the seek bar: Left/Right seek, holding speeds up 10 s → 30 s → 60 s; seek applied after 600 ms.
- Play/pause key toggles playback.
- Back: in a settings choice returns to the settings list; otherwise closes the panel, then the
  controls, then leaves the player.

## Checks
- Pause for a minute: "Ends at" moves on.
- Subtitle search for a film without subtitles: download and switch without restarting.
- Settings, subtitle colour Yellow: subtitles turn yellow at once; next title still yellow.
- Skip forward 15 s: the button shows 15 and Right with hidden controls jumps 15 s.

## Open points
- Not yet verified on the TV: track mapping with many embedded tracks, subtitle download and
  reload, audio switching, whether play/pause arrives over CEC.
