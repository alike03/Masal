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
  5. Plex's own Home hubs (Recently Added Movies/TV, promoted collections). Without hubs:
     one "Recently Added" row.
- Info block per item:
  - Plex title: logo art (or title text), episode title, facts line (S1 • E5, date, runtime,
    rating), two-line summary, top three cast. Smaller and lower, like Plex.
  - Channel: logo, LIVE chip, name, current programme with time and progress, next programme.
  - App: app name and the photo credit. "Edit" card: "Edit Home apps".
- Background: see look.md. Resting 2.5 s on a Plex title plays its trailer if Plex has one;
  the title itself never plays from Home.

## Remote keys
- D-pad moves; OK opens a title, starts a channel or opens an app.
- On an app: play/pause (or Menu) starts moving it, ◀ ▶ move, OK/Back/play/pause ends.

## Checks
- Down from the apps row: apps slide up and out, Continue Watching's header opens.
- Focus on "Edit": no video starts, background stays the app photo.
- Flicking through the live TV row: gradient behind, picture fades in, no logo placeholder.

## Open points
- Plex shows the clock top right; Masal shows it in the side menu (not changed yet).
