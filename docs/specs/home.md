# Home

## Purpose
Start screen: what to watch next, live TV and the pinned apps, in Plex's layout.

## Behaviour
- Top: the info block (header) of the focused item; below it the focused row; the next row
  only peeks in at the bottom.
- Rows open like an accordion: moving down, the previous row slides straight up and out (no
  fade, no tilt) while its header closes, and the next row's header opens above its title.
- Rows, in this default order (the order can be changed in Settings, see "Row order" below):
  1. "Get started" card, only when neither IPTV nor Plex is set up. Always first, not sortable.
  2. Apps: only the apps pinned on Home, in the user's order, then an "Edit" card.
  3. Continue Watching (Plex on deck), then each profile's Continue Watching when switched on
     (profiles.md).
  4. Live TV row: the channels of one IPTV category, or the favourite channels, with its own title,
     both set on the setup page (`Settings.homeLiveGroup` / `homeLiveTitle`, title defaults to
     "Live TV"); hidden while no category is set. Favourites show in channel list order and follow
     every change of a favourite. The category is matched without accents and provider prefix such as
     "[XX] ". No category subtitle under the cards; under each channel name the current programme,
     one full line (the row is a little taller than the Apps row for it).
  5. Plex rows, chosen in Settings (see "Home rows" below). A collection row says next to its
     title whether it holds movies or TV shows ("Movies" / "TV shows"). Without hubs (older servers):
     one "Recently Added" row. Then each profile's other rows, named after the profile.
  6. Seerr rows switched on in Settings (seerr.md, "Discover rows on Home"), in Seerr's order.
- Home rows: every Plex Home row (Recently Added Movies/TV and the like) and every collection of
  every movie and TV library can be shown on Home. By default Home shows what Plex shows on its own
  Home (its rows and the collections promoted there). Choices are stored as differences from that
  default (`home_rows_on`, `home_rows_off`), so a collection newly promoted in Plex still appears
  unless it was switched off. Order: Plex's own order, then extra collections in library order.
  Extra collections show their first 20 titles. Changing rows does not reload IPTV.
- Row order: Settings, Home rows, "Order" lists every row currently on Home (Apps, Continue
  Watching and each profile's, the live TV row, Plex rows, collections, profile rows, Seerr rows)
  in Home's order. OK picks a row up (it is marked as moving), Up/Down move it, OK or Back puts
  it down; the order is saved at once and Home follows without reloading.
  - Stored as a list of row ids (`home_order`): "apps", "deck", "tv", "hub:<id>" (Plex rows,
    collections, profile rows and decks, as today), "seerr:<slider id>".
  - A row that is not in the stored list yet (a collection newly promoted in Plex, a Seerr row
    just switched on) goes right after the row that precedes it in the default order, or first
    if none does. Switching a row off and on again keeps its place.
  - Rows that are switched off, empty or not loaded are skipped; the others keep their order.
  - "Reset order" above the list returns to the default order.
  - Home starts on the first card of the first row in this order.
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
- Standby (screen off): Masal closes all open pages and goes to Home's first row, first card,
  so the TV wakes up on the start of Home with the side menu closed. Started on a sleeping TV
  (after an install), Home takes focus once the screen is on, not the side menu.
- Back from a page or the player, Home returns to the card it left; an earlier "back to the
  top" from the side menu is not repeated.
- It starts with the device, often before the network is up: when a network becomes available,
  IPTV, guide, Plex and the backdrop photo are loaded again if they failed or never loaded.
- The device settings are reachable through the "Android settings" app tile and the button on
  the Settings screen.

## Restart after a crash
- As the default home screen, Masal comes back by itself when its process dies while it is on
  screen (Home, a page or the player): a Java crash, a native crash, or "Close app" in Android's
  "not responding" dialog. It opens on Home's first row, first card, like after standby.
  Without this, Android brings the app that was open before Masal to the front instead.
- Only when Masal was on screen: when it dies in the background (another app in front, the
  system freeing memory), nothing happens; the Home key starts it as usual.
- Not after a force stop (Android settings, an install, `adb shell am force-stop`): that stops
  every Masal process, the guard included.
- Not as a normal app (another launcher is the home screen).
- Needs "display over other apps" (`SYSTEM_ALERT_WINDOW`), granted once over ADB (README, "Home
  screen"): from Android 10 on, Android blocks activity starts from the background, also for the
  home screen, unless the app has this permission. Masal never draws over other apps with it.
  Without it the guard only logs that it could not restart.
- At most 3 restarts within 10 minutes; after that Masal stays closed until it is started
  again, so a crash at start-up cannot loop.
- How: a small guard service in its own process (`:guard`). While a Masal activity is started,
  the app process is bound to it and hands it a binder; the guard watches that binder and,
  when it dies, starts MainActivity. Leaving Masal normally unbinds first, so it is no restart.
  Every restart (and every one skipped, with the reason) is logged under tag `MasalGuard`.
- Incident 2026-11-26: the device's audio service hung, opening the player blocked in
  `AudioManager.getDevices` (Media3 `DefaultAudioSink`), the "not responding" dialog was closed,
  and the TV showed the YouTube app that had been open before.

## Remote keys
- D-pad moves; OK opens a title, starts a channel or opens an app.
- On an app: play/pause (or Menu) starts moving it, ◀ ▶ move, OK/Back/play/pause ends.

## Checks
- Down from the apps row: apps slide up and out, Continue Watching's header opens.
- Focus on "Edit": no video starts, background stays the app photo, no text above the rows.
- Switch a collection on in Settings: it appears on Home at once, without reloading channels.
- Order: move Live TV above Apps: Home starts with Live TV; restart Masal: still first.
- Switch on a new collection after reordering: it appears after its default neighbour, the
  rest of the order is unchanged. "Reset order": default order again.
- Flicking through the live TV row: gradient behind, picture fades in, no logo placeholder.
- Home key on the TV tab or a title page: Home tab, first focus restored, pages closed.
- With Masal as default home screen: Back in the menu on Home goes to the first app; as a normal
  app it leaves.
- Start the device with the network cable out, plug it in: channels and Plex rows appear.
- Restart: with Masal on screen, crash only the app process (`adb shell 'am crash $(pidof
  com.alike.masal)'`; the shell may not `kill` app processes, and `am crash` with the package
  name may hit the guard instead): Masal is back on Home within a few seconds. Same with another
  app in front: nothing opens. Four crashes within 10 minutes: the fourth stays closed.

## Open points
- None at the moment.
