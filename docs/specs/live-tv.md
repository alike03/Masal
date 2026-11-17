# Live TV

## Purpose
IPTV channels (Xtream or M3U, XMLTV guide) in a TV tab and a full-screen live player.

## Behaviour
- TV tab, laid out like Home: the focused channel plays in the top-right picture frame with its
  colours around it (look.md); top left its info (category, full name, now with time and progress,
  description, next), or, before a channel has focus, the category's name and channel count.
  Below, on a dark band over the lower edge of the picture: categories on the left, the channels
  of the selected one on the right (number, logo, full name, current programme, time, next; the
  star button marks favourites). No key hints, no LIVE badge anywhere in Masal: the context is
  clear. Channel names are never cut off; a long name wraps.
- Moving through the categories keeps the focused channel playing in the frame; only another
  channel replaces it.
- Category order: "Last watched" and "Favourites" first, then the categories in the user's order
  (Settings, see setup.md), categories not placed by the user after them in the provider's order.
  The live player's channel list uses the same order.
- Live player: full-screen channel with an overlay for the channel list. The info banner shows
  logo, number, full name (wraps, no LIVE badge), now and next on the left and, on the right, the current programme's
  description from the guide, or "No guide information for this channel." / "No description for
  this programme." No key hints.
- Opening a channel whose preview is already running hands that stream to the player: no
  restart, no new connection. From Home and the TV tab the picture zooms from its frame to full
  screen; leaving the player (Back) zooms it back into the frame, and when the channel is still the
  one opened, it keeps playing there. From search the player opens and closes normally (still
  without restart when the preview was running).
- Previews stop before full-screen playback (providers often allow one connection); leaving the
  live player releases the stream at once. Back from the player, the preview of the channel just
  watched waits until the focus moves (see home.md).
- Switching channels: the old channel keeps playing (picture and sound) until the new one shows
  its first picture, then the picture changes in one step. The new channel loads muted in a
  second player underneath; there are never more than two live players. A single press tunes at
  once; while the user presses quickly (less than 400 ms apart) the stream opens only 300 ms
  after the last press, so flicking through channels does not open a stream per channel. The
  info banner follows every press at once. The channel list, the last-channel key and digits
  switch the same way. Until the chosen channel's picture starts, a thin amber loading bar runs
  along the bottom edge of the screen (also when the banner has gone).
- Fallback to one stream: if the new channel fails as a second stream, or shows no picture within
  8 s, it is opened the old way (old stream stops, black with a buffering spinner until the new one
  starts). Many providers (Xtream in particular) allow one connection per account, and a device
  may not decode two streams at once. When a channel fails as a second stream but then plays on
  its own, the rest of the session switches the old way. A provider that cuts the old stream when
  the new one connects needs no fallback: the old picture stays until the new one starts.
- Live streams start after about 1 s of buffer (Plex keeps ExoPlayer's 2.5 s); after a stall they
  still wait for 5 s, so a weak stream does not stutter.
- A dropped stream reconnects by itself, up to five times with growing pauses (2 to 10 s).
- Channels with the same name (main and backup stream) are told apart by their stream address.
- The guide covers the next 24 h; after more than 3 h in the background channels and guide reload.

## Remote keys (live player)
- Up or Right: next channel. Down or Left: previous channel.
- OK: channel list. Back: leave.

## Checks
- Switching channels with ◀ ▶ ▲ ▼ in the live player: no black and no spinner between channels,
  the old channel plays on until the new one appears. Holding or quickly pressing ▲: the banner
  runs through the channels, a stream opens only when the presses stop.
- With a one-connection account or a channel the device cannot decode next to another one:
  switching still works, with a short black picture (`adb logcat -s MasalLive` shows the fallback).
- Home: rest on a channel until it plays, OK: it zooms to full screen without black or buffering.
  Back: it zooms back into the frame and keeps playing. Zap first, then Back: zoom out, preview
  of the channel on Home waits for the focus to move.
