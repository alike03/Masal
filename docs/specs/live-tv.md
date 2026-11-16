# Live TV

## Purpose
IPTV channels (Xtream or M3U, XMLTV guide) in a TV tab and a full-screen live player.

## Behaviour
- TV tab: categories, then the channels of a category with logo, name and current programme;
  favourites are marked. A small live preview plays the focused channel; before its picture
  starts it shows only a gradient (no "Tuning in" text).
- Live player: full-screen channel with an overlay for the channel list. The info banner shows
  logo, number, name, now and next on the left and, on the right, the current programme's
  description from the guide, or "No guide information for this channel." / "No description for
  this programme." No key hints.
- Opening a channel whose preview is already running hands that stream to the player: no
  restart, no new connection. From Home the picture zooms from its frame to full screen; leaving
  the player (Back) zooms it back into the frame on Home, and when the channel is still the one
  opened, it keeps playing there. From the TV tab or search the player opens and closes normally
  (still without restart when the preview was running).
- Previews stop before full-screen playback (providers often allow one connection); leaving the
  live player releases the stream at once. Back from the player, the preview of the channel just
  watched waits until the focus moves (see home.md).
- A dropped stream reconnects by itself, up to five times with growing pauses (2 to 10 s).
- Channels with the same name (main and backup stream) are told apart by their stream address.
- The guide covers the next 24 h; after more than 3 h in the background channels and guide reload.

## Remote keys (live player)
- Up or Right: next channel. Down or Left: previous channel.
- OK: channel list. Back: leave.

## Checks
- Switching channels with ◀ ▶ ▲ ▼ in the live player.
- Home: rest on a channel until it plays, OK: it zooms to full screen without black or buffering.
  Back: it zooms back into the frame and keeps playing. Zap first, then Back: zoom out, preview
  of the channel on Home waits for the focus to move.
