# Cast and people

## Purpose
See who plays in a title, also while it is paused, and open a person's page with their films
and series: the ones in the library to play, the others to request through Seerr
(`seerr.md`).

## Behaviour
### Cast while paused (player)
- Pausing does not open the cast by itself. While paused, a small chevron (⌄, no text) sits
  below the control buttons (in the bar's bottom margin, so nothing moves). Down from any
  button opens a row below the buttons and pushes the bar up: the title's main cast with round
  photo, name and role ("as …"), at most 12 in Plex's billing order, then the directors.
  It always opens on the first person. Not offered while playing; playing again closes it.
- The list is the whole title's cast from Plex (for an episode: the episode's cast, else the
  show's). It does not know who is on screen in the paused picture; see Open points.
- Up or Back in the row closes it and returns to the buttons. OK on a person opens their page
  over the paused player; Back returns to the player, still paused at the same place, with the
  row open on that person.

### Cast on the detail page
- The detail page of a film or show has a row "Cast & Crew" below the existing content: the
  same round photos with name and role; directors, writers and producers follow the actors with
  their job ("Director"). OK opens the person page.

### Person page
- Top: photo, name, known for (acting, directing), birth date and place, and the biography
  (in the app language where TMDB has it, else English; long text is cut with "More", which
  shows all of it).
- Below: a filter "All" / "In Plex" and a poster grid of the person's films and series, newest
  first, each title once (an actor who also directed shows it once, with both jobs in the
  caption). Caption: year and role or job.
- Every poster shows its status as a small badge in the corner, from Seerr:
  - "In Plex" (available, also partly available series): full colour, accent badge.
  - "Requested" / "Processing": dimmed poster, badge with the state.
  - Nothing: dimmed poster, no badge.
- OK on a title in Plex opens Masal's detail page of that title (Seerr gives its Plex id). OK
  on any other title opens the Seerr title page (`seerr.md`) with "Request".
- Opened from the player, OK on a title in Plex asks first, because playing it leaves the
  current one ("Leave the player?" Yes / No); the current position is saved as usual.
- Without Seerr set up, the page still shows the photo and name from Plex and only the titles
  in the library (Plex's filter by actor or director), without biography, filter or badges.
- People in a profile's title (`profiles.md`) open the same page; titles from it open as
  that profile.

### Data and cache
- Plex gives each title's cast with name, role, photo and Plex's own person id, but no TMDB
  person id. Seerr is asked by the title's TMDB id (in Plex's metadata) for the cast with TMDB
  person ids, so a person is matched by id, not by name.
- Person details and the filmography are cached on the device for 7 days. A page opens from
  the cache at once; each time it opens, the status badges are fetched again in the
  background and the page updates without moving focus. After a request from Masal the
  title's badge changes at once.
- The cache keeps at most 600 entries (about 300 people with details and filmography); the
  oldest are dropped first. Settings, Seerr, "Clear cache" empties it.
- Credits without a poster are left out unless the title is in Plex: talk shows and award
  ceremonies list hundreds of guest appearances.

## Remote keys
- Player, paused: Down from the buttons opens the cast row on its first person, Left/Right
  move, OK opens the person, Up or Back close the row and return to the buttons.
- Person page: Down from the top to the filter, then the grid; OK on "More" shows the full
  biography, Back closes it; Back leaves the page.

## Checks
- Pause a film: only the chevron shows; Down opens the row on the first person; Up closes it;
  play: the chevron and the row hide.
- Person page of an actor with titles in the library and outside it: badges right, OK on an
  "In Plex" title opens its detail page, OK on another opens the Seerr page.
- Request a title from the person page: badge becomes "Requested" without reopening.
- Second visit within 7 days opens without waiting; an added film gets its badge after reopening.
- Without Seerr: the page shows only the library titles, no badges or filter.
- JVM tests against a mock Seerr and Plex: cast order (actors, then directors), one entry per
  title with combined jobs, status mapping (5 → In Plex, 4 → In Plex, 2/3 → Requested /
  Processing), cache age and size limits, fallback without Seerr.

## Open points
- Who is on screen right now (like Amazon's X-Ray) needs face matching on the paused picture
  against the cast photos. First a test on the TV whether the Shield delivers a usable still
  from the HDR video surface; only then a separate spec.
- Seerr's status comes from its own Plex sync; a title just added to Plex may show without
  "In Plex" until Seerr's next scan.
