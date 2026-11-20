# Seerr

## Purpose
Find and request films and series that are not in the library, from the TV, through the
household's Seerr (the request manager for Plex, formerly Overseerr / Jellyseerr). Seerr also
supplies the person data and title status for `people.md`, so Masal needs no TMDB key of its own.

## Behaviour
### Setup
- Optional, on the phone setup page (`setup.md`) under Plex: Seerr address and the API key
  (Seerr, Settings, General, "API Key"). "Save" checks both with `GET /api/v1/auth/me` and
  shows the Seerr user the key belongs to, or why it failed.
- Like all logins, the key stays in the app's private storage, is excluded from backup and is
  never logged or shown again; the setup page shows only that a key is set.
- The TV's Settings shows Seerr as "Connected as <user>" or "Not set up", with "Clear cache"
  and the request user per profile (below).

### Who requests
- Requests go out with Seerr's API key and the header `X-API-User`, so Seerr files them under a
  Seerr user (quota, notifications and "requested by" as for that user).
- Per Masal profile a Seerr user: by default the Seerr user linked to the same Plex account
  (Seerr's Plex users), else the key's own user. Settings, Seerr lists each profile with its
  Seerr user; OK picks another one from Seerr's users.
- Which profile requests: the one the page belongs to (a title or person opened from a
  profile's row), otherwise the main account.

### Search
- Search (`search.md`) gets two rows from Seerr after the existing ones: "Not in your library"
  (films and series from TMDB that Seerr does not have as available) and "People". Plex's own
  results stay first; a title in Plex is not repeated in the Seerr row.
- Seerr is asked after the same 400 ms pause as Plex, in the app language.
- OK on a title opens the Seerr title page, OK on a person the person page (`people.md`).

### Seerr title page
- Same layout as Masal's detail page: backdrop, poster, title, year, runtime or seasons,
  genres, summary, cast row (`people.md`), and the status badge.
- Button by status:
  - Not requested: "Request".
  - Requested / Processing: the state and the request date, no button.
  - In Plex (reached from an old cache entry): "Open" goes to Masal's detail page.
- Film: "Request" asks once ("Request <title>?" Request / Cancel) and sends
  `POST /api/v1/request` with `mediaType movie` and the TMDB id.
- Series: "Request" opens a list of seasons with their state; seasons already in Plex or
  requested are marked and cannot be picked. "All missing seasons" is preselected; OK toggles
  a season, "Request" sends them (`mediaType tv`, `seasons`).
- Seerr's defaults decide server, quality profile and folder; 4K is not offered.
- After sending: a short message "Requested" (or Seerr's error text, e.g. quota reached) and
  the badge changes at once.

## Remote keys
- Seerr title page: Down from the top to the button row, then the cast row; Back leaves.
- Season list: Up/Down move, OK toggles, Back cancels without requesting.

## Checks
- Setup with a wrong key: the page says the key was refused; with the right key it shows the
  Seerr user.
- Search a film not in the library: it appears under "Not in your library"; Request, then
  Seerr shows the request under the right user.
- Request from a page opened in a profile's row: Seerr shows it as that profile's user.
- Series with season 1 in Plex: season 1 is marked and cannot be picked, the others are
  preselected.
- Seerr not reachable: search shows the Plex results and no Seerr rows, no error box.
- JVM tests with a mock Seerr: auth check, search mapping and de-duplication against Plex,
  request bodies for film and seasons, `X-API-User` per profile, error text passed on.

## Seerr calls
`/api/v1` with header `X-Api-Key`; `X-API-User` on requests. `auth/me`, `user` (users with
their Plex ids), `search?query=&language=`, `movie/<tmdbId>`, `tv/<tmdbId>` (seasons with
state), `person/<id>`, `person/<id>/combined_credits` (each title with `mediaInfo.status` and
`ratingKey`), `request` (POST). Status values: 1 unknown, 2 pending, 3 processing,
4 partly available, 5 available, 6 deleted. Checked against Seerr 3.5.2's API description.

## Open points
- Seerr's watchlist and "Discover" rows on Home are left out for now.
- Not yet verified on the TV. A Plex Home member without an own plex.tv login may not exist as a
  Seerr user at all; such a profile requests as the key's user until one is picked in Settings.
