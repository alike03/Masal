# Language

## Purpose
Masal in English, German or Turkish, chosen in Settings.

## Behaviour
- Choices: "Device language" (default), English, Deutsch, Türkçe. "Device language" follows the
  TV's system language and falls back to English for languages Masal has no texts for. Each
  choice is shown in its own language.
- Set on the TV (Settings, "Language") or on the phone page; applies at once: the screen is
  redrawn in the new language, data stays loaded.
- Covers every text of the TV app (menu, rows, settings, player, live TV, messages) and the phone
  setup page.
- Not translated: content from Plex (titles, summaries, genres, cast), channel and category names
  from the IPTV provider, the user's own row title, the name "Masal".
- Plex's own texts (row titles such as "Recently Added Movies") are requested in the chosen
  language (`X-Plex-Language`), but servers send them in English anyway. So Masal translates the
  known ones itself (`PlexNames`): row titles by their hub identifier (the name inside, e.g. the
  library in "Recently Added in Movies", is kept or translated too), and the default library names
  "Movies" / "TV Shows" (menu, pages, Settings). Collections, own library names, genres and people
  stay as Plex sends them. IPTV channels without a category land in "Other" in the app's language.
- German uses Swiss spelling (ss, never ß) and addresses the user with "du"; Turkish uses "sen".
  Times are 24-hour everywhere.
- Texts live in `res/values*/strings_*.xml` (one file per area, keys prefixed by area);
  `Language.kt` applies the choice to every activity and the application.

## Checks
- Settings, Language, Deutsch: menu, Home rows, player controls and the phone page are German.
- Language back to "Device language" on an English TV: English again.
- JVM tests run in English (the default resources).
