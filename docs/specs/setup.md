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
- Live TV sources: list with type badge, name and host, Remove per source. "+ Add source" opens a
  dialog: first the type (Xtream login or M3U playlist), then only that type's fields.
  Several sources are possible.
- Home live TV row: a select of the channel categories from the loaded sources (in provider order,
  plus "None" to hide the row) and the row title. A saved category that is not in the loaded list
  stays selected and marked, so a late load never clears it.
- Plex: status chip (Connected / Not connected) with the server host; the hint to use the code on
  the TV at plex.tv/link; manual server URL and token folded away below.
- Changes apply on the TV at once. The page shows the Masal favicon.
- The Settings screen on the TV also has an "Android settings" button for the device's own settings.
- Logins stay in the app's private storage, excluded from cloud backup and device transfer;
  never hardcode, log or print them.

## Checks
- Scan the QR code, save Plex settings, Home shows Continue Watching.
