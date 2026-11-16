# Live TV

## Purpose
IPTV channels (Xtream or M3U, XMLTV guide) in a TV tab and a full-screen live player.

## Behaviour
- TV tab: categories, then the channels of a category with logo, name and current programme;
  favourites are marked. A small live preview plays the focused channel; before its picture
  starts it shows only a gradient (no "Tuning in" text).
- Live player: full-screen channel with an overlay for the channel list.
- Previews stop before full-screen playback (providers often allow one connection); leaving the
  live player releases the stream at once.
- A dropped stream reconnects by itself, up to five times with growing pauses (2 to 10 s).
- Channels with the same name (main and backup stream) are told apart by their stream address.
- The guide covers the next 24 h; after more than 3 h in the background channels and guide reload.

## Remote keys (live player)
- Up or Right: next channel. Down or Left: previous channel.
- OK: channel list. Back: leave.

## Checks
- Switching channels with ◀ ▶ ▲ ▼ in the live player.
