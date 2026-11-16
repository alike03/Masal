# Search

## Purpose
Find a film, show or channel quickly with the remote.

## Behaviour
- Top entry in the side menu.
- Searches Plex (all libraries, Plex's `/hubs/search`: movies, shows, seasons, episodes) and
  the IPTV channel names. Case, accents and the Turkish ı/İ are ignored ("cafe" finds Café,
  "istanbul" finds İSTANBUL).
- Results appear while typing (Plex after a 400 ms pause); the previous results stay until
  new ones arrive. One row per kind, channels last as "Live TV" with favourite dot and current
  programme.
- The last Plex result is kept, so Back from a title shows it at once and focus returns to
  the card.
- Resting on a result sets the background (trailer for titles, live preview for channels).

## Remote keys
- OK on the field opens the TV keyboard (voice on the Shield keyboard).
- Search key on the keyboard: search at once, move to the first result.
- Down from the field goes to the results; Left on an empty field goes to the menu.
- Long press on a channel toggles favourite (may not arrive over CEC).

## Checks
- Type part of a channel name: matching channels listed with their current programme.
- Open a film from the results, Back: focus on the same card.

## Open points
- Actor results are not shown (Plex returns them as separate actor hubs).
- Not yet verified on the TV: keyboard opening with OK, Plex search results.
