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
- Plex: server URL and token, or linking through plex.tv/link with a PIN.
- IPTV: M3U playlist (with optional XMLTV guide) or Xtream login; several sources possible.
- Home live TV row: channel category and row title.
- Changes apply on the TV at once. The page shows the Masal favicon.
- Logins stay in the app's private storage, excluded from cloud backup and device transfer;
  never hardcode, log or print them.

## Checks
- Scan the QR code, save Plex settings, Home shows Continue Watching.
