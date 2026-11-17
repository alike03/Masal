# Home

## Purpose
Start screen: what to watch next, live TV and the pinned apps, in Plex's layout.

## Behaviour
- Top: the info block (header) of the focused item; below it the focused row; the next row
  only peeks in at the bottom.
- Rows open like an accordion: moving down, the previous row slides straight up and out (no
  fade, no tilt) while its header closes, and the next row's header opens above its title.
- Rows, in order:
  1. "Get started" card, only when neither IPTV nor Plex is set up.
  2. Apps: only the apps pinned on Home, in the user's order, then an "Edit" card.
  3. Continue Watching (Plex on deck).
  4. Live TV row: the channels of one IPTV category with its own title, both set on the setup
     page (`Settings.homeLiveGroup` / `homeLiveTitle`, title defaults to "Live TV"); hidden while
     no category is set. The category is matched without accents and provider prefix such as
     "[XX] ". No category subtitle under the cards.
  5. Plex rows, chosen in Settings (see "Home rows" below). A collection row says next to its
     title whether it holds movies or TV shows ("Movies" / "TV shows"). Without hubs (older servers):
     one "Recently Added" row.
- Home rows: every Plex Home row (Recently Added Movies/TV and the like) and every collection of
  every movie and TV library can be shown on Home. By default Home shows what Plex shows on its own
  Home (its rows and the collections promoted there). Choices are stored as differences from that
  default (`home_rows_on`, `home_rows_off`), so a collection newly promoted in Plex still appears
  unless it was switched off. Order: Plex's own order, then extra collections in library order.
  Extra collections show their first 20 titles. Changing rows does not reload IPTV.
- Info block per item:
  - Plex title: logo art (or title text), episode title, facts line (S1 • E5, date, runtime,
    rating), two-line summary, top three cast. Smaller and lower, like Plex.
  - Channel: logo, full name (wraps, no LIVE badge), current programme with time and progress,
    description, next programme.
    The channel plays in the picture frame (see look.md); OK zooms it to full screen (live-tv.md).
  - App and "Edit" card: only the time (HH:mm, large) top left, and the photo behind; no app name,
    no photo credit. Moving along the apps the time stays put (no animation). This is the only
    clock in Masal outside the player; the side menu has none.
- The background picture behaves the same: on another row it changes at once without showing
  the previous row's picture (look.md).
- The info block follows the focus after 90 ms within a row. Moving to another row it changes at
  once and the old text disappears without fading: the new row's slot opens right away and must
  never show the previous row's text.
- Background: see look.md. Resting 2.5 s on a Plex title plays its trailer if Plex has one;
  the title itself never plays from Home.
- Back from the player, another app or standby, the preview that was showing does not start
  again by itself; it plays again once the focus has moved (no sudden sound on return).
  Exception: a live channel zoomed back from the player keeps playing in its frame (live-tv.md).

## As the device's home screen
- Masal can replace the stock launcher (it declares a HOME activity, `singleTask`). Whether it
  is the home screen is decided on the device, never by the app.
- When it is the default home screen, Back on Home with the side menu focused goes to the top of
  Home: the first row opens and its first card gets focus (there is nothing to leave to). As a
  normal app, Back there leaves the app.
- The Home key closes all open pages and returns to the Home tab, focus restored there. Pressing
  it in another app or the player brings Masal back the same way.
- It starts with the device, often before the network is up: when a network becomes available,
  IPTV, guide, Plex and the backdrop photo are loaded again if they failed or never loaded.
- The device settings are reachable through the "Android settings" app tile and the button on
  the Settings screen.

## Remote keys
- D-pad moves; OK opens a title, starts a channel or opens an app.
- On an app: play/pause (or Menu) starts moving it, ◀ ▶ move, OK/Back/play/pause ends.

## Checks
- Down from the apps row: apps slide up and out, Continue Watching's header opens.
- Focus on "Edit": no video starts, background stays the app photo, no text above the rows.
- Switch a collection on in Settings: it appears on Home at once, without reloading channels.
- Flicking through the live TV row: gradient behind, picture fades in, no logo placeholder.
- Home key on the TV tab or a title page: Home tab, first focus restored, pages closed.
- With Masal as default home screen: Back in the menu on Home goes to the first app; as a normal
  app it leaves.
- Start the device with the network cable out, plug it in: channels and Plex rows appear.

## Open points
- None at the moment.
