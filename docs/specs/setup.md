# Setup page

## Purpose
Enter Plex and IPTV logins with the phone or a computer instead of the remote.

## Behaviour
- The page is at a fixed address, `http://<tv-ip>:8765/`, and runs while Masal runs (started
  with the app, no background service; idle it is one thread waiting on the port, no measurable
  load). If Android has ended Masal, it is back as soon as Masal starts again.
- Sign-in with a six-digit PIN, shown in Settings on the TV under the QR code. The QR code
  carries the PIN (`/?pin=…`), so scanning signs in at once; the address bar then shows only
  the address. A signed-in device keeps a cookie for a year (HttpOnly, SameSite=Strict); the
  TV keeps only a hash of it, at most 20 devices.
- After 5 wrong PINs in a row every PIN is refused for a minute.
- Settings, "Phone page", "PIN": OK makes a new PIN, which signs every device out.
- Logo, favicon and fonts are served without sign-in; everything else needs it, forms included.
- The page is plain HTTP on the home network: someone on the same network could read the PIN
  or the cookie in transit. Logins are still never shown on the page.
- Saved sources show only the server host (and two letters of an Xtream user name), never the
  full playlist address, which often contains the login.
- Plex link: the token is saved as soon as plex.tv confirms the code; a failing server lookup
  only asks for the server URL instead of losing the link.
- Look: Masal's style (logo, "Masal" in Fraunces, DM Sans, amber on dark, cards). Logo and fonts are
  served by the TV itself, so the page loads nothing from the internet.
- Language: a card at the top with a select (Device language, English, Deutsch, Türkçe), the same
  choice as on the TV (`docs/specs/language.md`). The page and its notices switch at once; the TV
  follows the next time Settings opens (the page cannot redraw the TV).
- Live TV sources: list with type badge, name and host, Remove per source. "+ Add source" opens a
  dialog: first the type (Xtream login or M3U playlist), then only that type's fields.
  Several sources are possible.
- Home live TV row: a select of "None" (hides the row), "Favourites" and the channel categories from
  the loaded sources (in provider order) and the row title. A saved category that is not in the loaded list
  stays selected and marked, so a late load never clears it.
- Home rows: checkboxes for Plex's Home rows and every collection, grouped by library (so two
  collections with the same name are told apart), folded per profile with its count, with "Save rows".
  Same choice as on the TV. The Plex card names the added profiles; they are added on the TV.
- Plex: status chip (Connected / Not connected) with the server host; the hint to use the code on
  the TV at plex.tv/link; manual server URL and token folded away below.
- Seerr (optional, seerr.md): status chip, then address and API key. "Save Seerr" checks both
  with Seerr first and stores them only when Seerr accepts the key ("Connected as <user>");
  a refused key or an unreachable Seerr says so. The key field never shows the stored key.
  "Remove Seerr" forgets address, key, user choices and the cache.
- Changes apply on the TV at once. The page shows the Masal favicon.
## Settings screen on the TV
Two levels: a start page with six sections, each opening a page with that section's settings;
some entries open a third level (a choice list, a text field, an order list). Back always goes
one level up and puts the focus on the entry that was opened.

- Start page, right: the sections, each with a short value of its current state:
  1. Home screen (how many rows are on Home).
  2. Live TV (how many sources, or "No sources").
  3. Player (subtitle size and skip lengths, e.g. "100 %, 10 / 30 s").
  4. Accounts (Plex connected or not, Seerr; while a link code is waiting: the code).
  5. Phone page (the address).
  6. General (the language).
- Start page, left: one or two sentences on the focused section. While neither Plex nor IPTV is
  set up, the left side shows the phone page's QR code, address and PIN instead (first setup).
- Inside a section the left side names the section and says what it covers; on "Phone page" it
  shows the QR code, address and PIN.
- Home screen:
  - "Rows: <profile>" per profile (the main account first) and "Rows: Seerr" (with Seerr set
    up), each with how many rows are on; OK opens the rows with check marks (profiles.md,
    seerr.md).
  - "Order" (own order or default) opens the row order (home.md).
  - "Live TV row" (the category, "Favourites" or "None") opens the list: "None",
    "Favourites", then the loaded categories; OK picks, Back returns without change.
  - "Live TV row title" opens a text field with the TV keyboard; Done saves, Back cancels.
- Live TV:
  - "Category order" (as the provider lists them, or own order) opens the categories numbered
    in their order. OK picks one up, Up/Down move it (stored at once), OK or Back put it down;
    "Back to the provider's order" (only with an own order) clears it. New categories from the
    provider appear after the ordered ones.
  - The sources by name; adding and removing happens on the phone page (a note says so).
- Player: subtitle size, colour, background, position, skip back, skip forward, each with its
  value; OK opens its choices (the same as the player's gear panel, player.md), picking one
  stores it and returns. The player uses them from the next title on.
- Accounts:
  - Plex: status and link code, "Link Plex again" (or "New code").
  - "Profiles" (once Plex is linked) adds and removes members of the Plex Home (profiles.md).
  - "Seerr" shows "Connected as <user>" or "Not set up" and opens the Seerr page: per profile
    the Seerr user its requests go out as, and "Clear cache" (seerr.md).
- Phone page: QR code, address and PIN on the left; right: "PIN" (OK makes a new PIN, every
  device signs in again) and the note what the page is for.
- General: "Language" (current choice) opens the list (Device language, English, Deutsch,
  Türkçe); OK picks and redraws the app in it. "Android settings" opens the device's own
  settings (network, display, apps; needed when Masal is the home screen).
- Changes apply at once and are the same settings as on the phone page.
- Logins stay in the app's private storage, excluded from cloud backup and device transfer;
  never hardcode, log or print them.

## Checks
- Scan the QR code, save Plex settings, Home shows Continue Watching.
- Open the address on a computer: the PIN page; the PIN from the TV opens the settings, and
  they open without PIN after a reload. A wrong PIN five times: locked for a minute.
- "PIN" in Settings: new PIN on the TV, the computer asks for it again.
- Settings: Back from a choice list returns to its section with the focus on the entry, Back
  again to the start page with the focus on the section.
- Player section: subtitle colour Yellow, start a film: yellow subtitles.
- On the TV, switch a collection on: it is checked on the phone page after a reload, and on Home.
- Pick Deutsch on the phone page: the page is German at once; reopen Settings on the TV: German.
- Pick a category on the TV, Back: the setting is unchanged; OK: the live TV row shows it.
- Move a category up in "Category order": the TV tab and the live player's list show it there.
