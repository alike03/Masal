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
- Settings, Accounts, Seerr, "Language": the language of Seerr's titles, summaries and posters:
  "App language" (default) or English, Deutsch, Français, Italiano, Español, Türkçe. It is
  sent as `language` on search, title, person and filmography pages and on the Trending,
  studio and network rows. Seerr's popular, upcoming, keyword, genre and streaming lists ignore
  it and always use the display language of Seerr's own user (Seerr, Settings, Users); the
  setting says so. Cached pages are kept per language, so a change loads them anew. Plex's
  online trailers are asked for in this language too (`X-Plex-Language`), where Plex has a
  dubbed version; Seerr's YouTube trailer follows the title page's language.
- Like all logins, the key stays in the app's private storage, is excluded from backup and is
  never logged or shown again; the setup page shows only that a key is set.
- The TV's Settings shows Seerr as "Connected as <user>" or "Not set up", with "Clear cache"
  and the request user per profile (below).

### Who requests
- Requests go out with Seerr's API key and the header `X-API-User`, so Seerr files them under a
  Seerr user (quota, notifications and "requested by" as for that user).
- "Request" first asks "Who is asking?": Seerr's users (`GET /user`), the request goes out as
  the one picked. Focused first is the profile's own Seerr user: the one set in Settings, Seerr (or on the phone page)
  for the profile the page belongs to (a title or person opened from a profile's row, otherwise
  the main account), by default the Seerr user linked to the same Plex account (Seerr's Plex
  users). Back or "Cancel" leaves without requesting. When Seerr's users cannot be loaded the
  list says so and only "Cancel" is left.

### Search
- Search (`search.md`) gets two rows from Seerr after the existing ones: "Not in your library"
  (films and series from TMDB that Seerr does not have as available) and "People". Plex's own
  results stay first; a title in Plex is not repeated in the Seerr row.
- Seerr is asked after the same 400 ms pause as Plex, in the app language.
- OK on a title opens the Seerr title page, OK on a person the person page (`people.md`).

### Seerr title page
- Same layout as Masal's detail page: backdrop, poster, title, year, runtime or seasons,
  genres, summary, cast row (`people.md`), and the status badge. A title still to come shows
  its release day instead of the year ("Out on 12 December 2026"); the badge then says "Soon"
  and the request line "Coming soon, requested on ...".
- Button by status:
  - Not requested: "Request".
  - Requested / Processing: the state and the request date, no button.
  - In Plex: "Open" goes to Masal's detail page; for a film "Play" comes first and starts it
    (resuming). Inside the player there is no "Play" (it would leave the current title).
- Film: "Request", then OK on a person sends `POST /api/v1/request` with `mediaType movie` and
  the TMDB id; there is no further question.
- Series: "Request", then OK on a person opens a list of seasons with their state; seasons
  already in Plex or requested are marked and cannot be picked. "All missing seasons" is
  preselected; OK toggles a season, "Request" sends them (`mediaType tv`, `seasons`).
- The trailer starts as soon as the page opens, in its own full-screen window over everything
  (also the side rail's strip). The window opens at once, black with the loading bar at the
  bottom, until the trailer's first frame is on screen; when it turns out there is no trailer
  (or the page fails to load), it closes without animation and the page shows. Source, in this order:
  1. Plex's online trailer: Plex's catalogue (`metadata.provider.plex.tv`, account token) finds
     the title by `tmdb://<id>` (`library/metadata/matches?type=1|2&guid=`), then its first
     trailer extra, an MP4 address played in Masal's own player. Also for titles not in the
     library; kept for the session.
  2. Else the YouTube trailer (a trailer, else a teaser) Seerr names: Masal finds its HLS stream
     itself (see "YouTube streams" below) and plays it in its own player, like Plex's. Also when
     Plex's trailer fails to play.
  When the stream cannot be found or fails, the window closes and the page shows the button
  "Open in YouTube" and "The trailer could not be loaded."; it
  opens the video in the first installed of TizenTube, SmartTube, YouTube for Android TV, else
  in whatever opens YouTube links. There is no other trailer button.
  OK or play/pause pauses and plays. Down or Back hide it (paused): the trailer slides up out of
  view while the page slides in from below (450 ms, like scrolling down), from its top; Up from
  the buttons slides it back and it plays on. Back on the page leaves it. When the trailer ends,
  it slides away the same way and only the page stays. Masal's own previews stop meanwhile. Not inside
  the player.
- YouTube streams: Masal asks YouTube itself with NewPipe Extractor (v0.27.1, from JitPack),
  which uses YouTube's visionOS client and returns its HLS playlist; no login, no token, no
  server of the household. NewPipe Extractor is GPL-3.0, so Masal is GPL-3.0-or-later (MIT up
  to commit 8fbed54). Only valid video ids (11 characters) are looked up, a stream address is
  kept for 50 minutes (they expire). The addresses are bound to the internet address that asked,
  which is the TV's own. The playlist holds H.264 up to 1080p and VP9 up to 4K with sound, no
  AV1; Masal's player picks by the connection. Lookups and player errors are logged under tag
  `MasalTrailer` (no addresses). When YouTube changes its side, trailers fall back to "Open in
  YouTube" until a Masal update with a newer NewPipe Extractor. This bypasses YouTube's player,
  against YouTube's terms of use; the household chose it knowingly (2026-11-22, in the app since
  2026-11-23, replacing the household's own yt-dlp service).
- Seerr's defaults decide server, quality profile and folder; 4K is not offered.
- After sending: a short message "Requested" (or Seerr's error text, e.g. quota reached) and
  the badge changes at once.

### Discover rows on Home
- With Seerr set up, Settings, Home rows has an entry "Seerr" listing Seerr's Discover sliders
  that hold titles, in Seerr's order (`GET /settings/discover`), each with a check mark like the
  Plex rows; all off by default. Stored as `home_rows_on` entries "seerr:<slider id>".
- Offered slider types and what they load (as Seerr's own Discover page does, first page only,
  about 20 titles). `language` (see Setup) goes along only on trending, studio, network
  and search: on `discover/movies` and `discover/tv` Seerr reads it as the titles' original
  language and would filter the list, so there Seerr's own language setting applies:
  - Trending (4): `discover/trending`.
  - Popular Movies (5): `discover/movies`. Popular Series (9): `discover/tv`.
  - Upcoming Movies (7): `discover/movies?primaryReleaseDateGte=<today>`.
    Upcoming Series (11): `discover/tv?firstAirDateGte=<today>`.
  - Custom sliders with their own title: TMDB keyword (13 movies, 15 series:
    `discover/movies|tv?keywords=<data>`), genre (14, 16: `discover/movies|tv?genre=<data>`),
    studio (18: `discover/movies/studio/<data>`), network (19: `discover/tv/network/<data>`),
    search (17: `search?query=<data>`), streaming services (20, 21:
    `discover/movies|tv?watchRegion=<data before ,>&watchProviders=<data after ,>`).
  - Not offered: Recently Added (1) and Recent Requests (2) (Plex's own rows cover them), Plex
    Watchlist (3, see Open points), and the genre, studio and network overview sliders (6, 8, 10,
    12), which show category tiles, not titles.
  - Built-in sliders have no title in Seerr; Masal names them in the app language.
- Sliders switched off in Seerr itself are still offered; Masal's choice is its own.
- On Home a Seerr row looks like a Plex row, with "Seerr" in small dim letters next to its
  title (like a profile's name on its rows): posters with the status badge from `people.md`
  ("In Plex", "Requested" / "Processing", none). The info block shows title, year and the
  two-line summary; the background is the title's backdrop. After the focus rests 2.5 s the
  trailer plays behind, like the Plex rows: the library's for a title in Plex, else Plex's
  online trailer (see "Seerr title page"); without one only the backdrop.
- OK: a film in Plex starts playing (resuming where it was left), a series in Plex opens
  Masal's detail page, any other title the Seerr title page ("Request"). Requests go out as the
  main account's Seerr user.
- Each row's list is cached for 12 hours (in `SeerrCache`) and loaded again in the background
  when Home's rows are rebuilt after that; the status badges follow a request from Masal at once.
- Seerr not set up: no "Seerr" entry, no Seerr rows (choices are kept). Seerr unreachable:
  the rows are left out, no error box. A slider deleted in Seerr disappears from Settings and
  Home.

## Remote keys
- Seerr title page: Down from the top to the button row, then the cast row; Back leaves.
- "Who is asking?": Up/Down move, OK picks, Back cancels without requesting.
- Season list: Up/Down move, OK toggles, Back cancels without requesting.

## Checks
- Setup with a wrong key: the page says the key was refused; with the right key it shows the
  Seerr user.
- Search a film not in the library: it appears under "Not in your library"; Request, then
  Seerr shows the request under the right user.
- "Request" on a page opened in a profile's row: "Who is asking?" focuses that profile's Seerr
  user; picking another person, Seerr shows the request as that person.
- Series with season 1 in Plex: season 1 is marked and cannot be picked, the others are
  preselected.
- Seerr not reachable: search shows the Plex results and no Seerr rows, no error box.
- Switch on "Trending" under Settings, Home rows, Seerr: a Trending row appears on Home; a film
  in Plex has the badge and plays on OK, a series in Plex opens its detail page, another title
  opens the Seerr page.
- A custom keyword slider made in Seerr appears in Settings with its own title.
- JVM tests with a mock Seerr: auth check, search mapping and de-duplication against Plex,
  request bodies for film and seasons, `X-API-User` per profile, error text passed on; the
  YouTube stream lookup (stream address, unknown video, only https, invalid id not looked up,
  kept for the session; YouTube itself is replaced in tests) and the title page looking up the
  YouTube trailer when Plex has none.
- A title without Plex trailer: the YouTube trailer plays full screen with picture. With no
  internet access to YouTube the page shows "Open in YouTube".

## Seerr calls
`/api/v1` with header `X-Api-Key`; `X-API-User` on requests. `auth/me`, `user` (users with
their Plex ids), `search?query=&language=`, `movie/<tmdbId>`, `tv/<tmdbId>` (seasons with
state), `person/<id>`, `person/<id>/combined_credits` (each title with `mediaInfo.status` and
`ratingKey`), `relatedVideos` in `movie/<id>` and `tv/<id>` (YouTube trailers), `request` (POST), `settings/discover` (sliders: `id`, `type`, `title`, `data`,
`enabled`), the `discover/...` lists above (`results` with the same title fields as search). Status values: 1 unknown, 2 pending, 3 processing,
4 partly available, 5 available, 6 deleted. Checked against Seerr 3.5.2's API description.

## Open points
- YouTube's embedded player (WebView) was dropped: on the Shield (Android 11, WebView 157) sound
  played and YouTube's controls showed, but the picture stayed black, with H.264 and VP9 decoded
  by the hardware at full rate and no own video layer in SurfaceFlinger (2026-11-22). The same
  HLS streams (then from the household's yt-dlp service) play with picture in Masal's player on
  the TV (2026-11-22). Lookup time on the TV not yet measured (1.4 to 3.7 s on the household's
  server). Not yet known whether Plex's online
  trailers need Plex Pass. Not yet known: whether ads appear before trailers, memory
  while it plays.
- Seerr's Plex Watchlist slider is left out for now: it is per Seerr user, and whether
  `X-API-User` selects whose watchlist is returned is not checked yet.
- Slider type numbers come from Seerr 3.5.2's code (`DiscoverSliderType`); the API description
  gives them only as numbers.
- Not yet verified on the TV. A Plex Home member without an own plex.tv login may not exist as a
  Seerr user at all; such a profile requests as the key's user until one is picked in Settings.
