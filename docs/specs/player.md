# Plex player

## Purpose
Play Plex films and episodes with Plex-style controls, subtitles and audio choice.

## Behaviour
- Controls: logo art and episode line top left, clock top right; seek bar with elapsed time,
  "Ends at" and remaining time; buttons back and forward (lengths from the settings, default
  10 s / 30 s), play/pause; pills for Subtitles and Audio, then two round icon buttons: "Open in
  Plex" (a square with an arrow out) and Settings (a gear). Auto-hide after 5 s.
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
- Settings panel (gear button): one line per setting with its current value; OK opens its
  choices, picking one applies it at once (subtitles change on screen) and returns to the list,
  Back returns without change. Stored on the device for all titles:
  - Subtitle size: 50, 75, 100, 125, 150 % (of 21 dp).
  - Subtitle colour: White, Yellow, Light grey, Cyan.
  - Subtitle background: None (black outline), Half-transparent box, Solid box.
  - Subtitle position: Low (3 %), Normal (7 %), High (15 %), Higher (25 % above the bottom).
  - Skip back: 5, 10, 15, 30 s. Skip forward: 10, 15, 30, 60 s. Used by the buttons, Left/Right
    with hidden controls and the rewind / fast-forward keys.
- Open in Plex: hands the title (the film, or the episode playing) to the Plex app with the
  link `plex://server://<server id>/com.plexapp.plugins.library/library/metadata/<ratingKey>`
  (server id from `/identity`) and no autoplay, then closes this player; leaving reports the
  position as usual, so Plex offers to resume there. Without the Plex app or without the server
  a short message says so and the player stays, paused.
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
- Open in Plex during a film: Plex opens on that film, Masal's player is gone; Back in Plex
  returns to Masal.

## Open points
- Not yet verified on the TV: track mapping with many embedded tracks, subtitle download and
  reload, audio switching, whether play/pause arrives over CEC.
- Open in Plex: the link format is not documented by Plex. Forum reports say the link plays
  reliably, while the app's info page via link broke in 2025 Plex releases. Check on the TV
  whether Plex 2026.22 shows the title's page, starts playback or only opens its Home.
