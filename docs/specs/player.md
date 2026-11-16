# Plex player

## Purpose
Play Plex films and episodes with Plex-style controls, subtitles and audio choice.

## Behaviour
- Controls: logo art and episode line top left, clock top right; seek bar with elapsed time,
  "Ends at" and remaining time; buttons back 10 s, play/pause, forward 30 s; pills for
  Subtitles and Audio. Auto-hide after 5 s.
- "Ends at" = current time + remaining runtime; clock and end time tick every second, also
  while paused. While seeking they use the target position.
- Subtitles panel: Off, the file's tracks (embedded and external), "Search for subtitles…":
  language (device language, German, English, Turkish), Plex's online search (OpenSubtitles
  through the Plex server), download, wait until Plex lists the new stream, reload at the same
  position. Choices are saved to Plex.
- Audio panel: the file's audio tracks; choice saved to Plex.
- Starts with the subtitle and audio stream Plex has selected.
- Subtitle look: white, bold DM Sans, black outline, no box, 21 dp, 7 % above the bottom.
- Requests audio focus: other apps (e.g. YouTube) pause; Masal pauses when another app takes it.
- The screen stays on only while playing or buffering; paused or failed, the TV may dim and sleep.

## Remote keys
- Controls hidden: OK, Up or Down show them; Left/Right skip -10 s / +30 s and show the seek bar.
- On the seek bar: Left/Right seek, holding speeds up 10 s → 30 s → 60 s; seek applied after 600 ms.
- Play/pause key toggles playback.
- Back: closes the panel, then the controls, then leaves the player.

## Checks
- Pause for a minute: "Ends at" moves on.
- Subtitle search for a film without subtitles: download and switch without restarting.

## Open points
- Not yet verified on the TV: track mapping with many embedded tracks, subtitle download and
  reload, audio switching, whether play/pause arrives over CEC.
