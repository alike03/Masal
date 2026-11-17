# Setup page

## Purpose
Enter Plex and IPTV logins with the phone instead of the remote.

## Behaviour
- Settings shows a QR code for `http://<tv-ip>:8765/<random key>`. The web server runs only
  while the Settings screen is open and the app is in the foreground, answers only under the
  random path, and gets a new key after every return to the app.
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
- Changes apply on the TV at once. The page shows the Masal favicon.
## Settings screen on the TV
- Left: the QR code and address of the phone page, for logins, sources and the Plex server.
- Right, a list moved with the remote:
  - Plex: status and link code, "Link Plex again", "Android settings" (the device's own settings).
    "Profiles" (once Plex is linked) adds and removes members of the Plex Home (profiles.md).
  - Language (after Plex): "Language" shows the current choice and opens a list (Device language,
    English, Deutsch, Türkçe); OK picks and redraws the app in it, Back returns without change.
  - Live TV row on Home: "Channel category" opens a list: "None", "Favourites", then the loaded categories;
    OK picks, Back returns without change. "Row title" opens a text field with the TV keyboard;
    Done saves, Back cancels.
  - TV tab: "Category order" (value: as the provider lists them, or own order) opens the
    categories numbered in their order. OK picks one up, Up/Down move it (stored at once), OK or
    Back put it down; Back again returns. "Back to the provider's order" (only with an own order)
    clears it. New categories from the provider appear after the ordered ones.
  - Home rows: one entry per profile (the main account first) with how many rows are on; OK opens
    its rows, one line per Plex row and collection grouped by library, with a check mark when
    shown; OK switches it, Back returns (profiles.md).
  - Live TV sources: names only; adding and removing happens on the phone page.
- Changes apply at once and are the same settings as on the phone page.
- Logins stay in the app's private storage, excluded from cloud backup and device transfer;
  never hardcode, log or print them.

## Checks
- Scan the QR code, save Plex settings, Home shows Continue Watching.
- On the TV, switch a collection on: it is checked on the phone page after a reload, and on Home.
- Pick Deutsch on the phone page: the page is German at once; reopen Settings on the TV: German.
- Pick a category on the TV, Back: the setting is unchanged; OK: the live TV row shows it.
- Move a category up in "Category order": the TV tab and the live player's list show it there.
