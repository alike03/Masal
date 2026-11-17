# Profiles

## Purpose
Plex works out some rows per user: Continue Watching, and smart collections with a filter such as
"unwatched". Loaded with the main account, another person's rows show the main account's state.
Profiles let Home show rows of other Plex Home members, loaded as that member, next to the main
account's rows.

## Behaviour
- The main account is the one linked in Settings (plex.tv/link). It is always there, has no name
  on Home and everything outside the profile rows uses it: libraries, search, the TV tab.
- Further profiles are members of the main account's Plex Home (the people under "Switch user" in
  the Plex app). Only Home members can be added; accounts the server is merely shared with cannot.
- Adding (Settings on the TV, "Profiles"): Masal lists the Home members that are not added yet
  (the main account's own entry left out). OK on a member without a PIN adds it at once. A member
  with a PIN opens a number field with the TV keyboard; the PIN is used once to sign in as the
  member and is never stored. A wrong PIN says so and keeps the field open.
- Masal stores per profile: the member's id, name and the server token Plex hands out for that
  member. Like all logins it stays in the app's private storage, is excluded from backup and is
  never logged or shown.
- Removing: OK on an added profile, then "Remove". Its token and its row choices are deleted.
- When a profile's token stops working (removed from Plex Home, password or PIN changed), its rows
  disappear from Home and Settings shows "Sign in again" on the profile, which asks for the PIN
  again where there is one.

## Home rows per profile
- Settings "Home rows" on the TV has one entry per profile: the main account first (named after the
  linked Plex account, "Your account" until its name is known), then each added profile, each with
  how many of its rows are on ("8 of 34 on"). OK opens that profile's rows, grouped as Plex and
  then by library; OK switches a row, Back returns to the entry. A profile's rows are its own
  Continue Watching, Plex's Home rows as Plex shows them for that member, and every collection.
- The phone page folds the rows the same way: one block per profile with the count, the main
  account's open.
- Defaults for an added profile: Continue Watching on, everything else off (the main account's
  rows already show the shared ones). Choices are stored per profile.
- The same collection can be on twice, once for the main account and once for a profile: each
  shows its own titles (a member's "unwatched" collection leaves out what that member watched).
- Order on Home: Get started, Apps, Continue Watching of the main account, then Continue Watching
  of each profile (in the order they were added), Live TV, the main account's Plex rows, then each
  profile's other rows.
- A profile's rows show the profile's name next to the row title, in the same dim style as the
  "Movies" / "TV shows" label, before that label, with a thin vertical line between the two:
  "Watchlist   Tester | Movies".
- Progress bars and watched marks in a profile's row are that member's.

## Watching from a profile's row
- A title opened from a profile's row stays with that profile: its detail page (seasons, next
  episode, watched marks) is loaded as the member, and the player reports progress and "watched"
  to the member's Plex profile. So the member's Continue Watching moves on and an "unwatched"
  collection drops the title after the next reload of Home.
- Titles opened anywhere else (libraries, search, the main account's rows) use the main account.
- Subtitle search and download in the player stay with the main account: they change the library,
  which Home members may not. Pictures and trailers on Home also load as the main account.
- Back from the player reloads the rows as today, the profile rows included.

## Remote keys
- Settings, Profiles: Up/Down move, OK adds, opens or removes; Back returns.
- PIN field: the TV keyboard's numbers, Done signs in, Back cancels.

## Checks
- Add a member with a PIN: wrong PIN is refused, right PIN adds the profile; Home shows its
  Continue Watching with the name label.
- Switch on the member's "unwatched" collection: Home shows it with the name; the same collection of
  the main account shows different titles.
- Play a title from the member's row to the end, return: it leaves the member's unwatched row and
  stays unchanged in the main account's rows; Plex shows it as watched for the member only.
- Pause halfway from the member's row: the member's Continue Watching shows it after Back, the main
  account's does not.
- Remove the profile: its rows leave Home, its token is gone from storage.
- JVM tests: row grouping and defaults per profile, row order on Home, label text; the plex.tv calls
  against a mock server (member list, PIN refused, token stored, token rejected).

## Plex calls
- Members: `GET plex.tv/api/v2/home/users` as the main account (`admin` marks the main account,
  `protected` a PIN).
- Signing in: `POST plex.tv/api/v2/home/users/<uuid>/switch[?pin=]` as the main account gives the
  member's account token (401/403: wrong PIN); `GET plex.tv/api/v2/resources` with it gives the
  member's `accessToken` for the server whose connection matches the server URL (falls back to the
  account token). A server request answered with 401 marks the profile as signed out.

## Open points
- Adding a profile on the phone page (with the PIN typed on the phone). For now only the TV adds;
  the phone page names the profiles and lists their Home rows.
- The member list was checked against a real Plex Home; switching and the server token are checked
  on the TV.
