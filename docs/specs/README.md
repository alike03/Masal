# Specs

One file per feature, describing how Masal behaves on the TV with the remote. They are the
reference for changes: a larger change starts by updating the spec, the owner confirms it,
then it is built, reviewed and checked against the spec. Small fixes (a label, a colour) can
go straight into the code; update the spec afterwards if the behaviour changed.

| File | Covers |
|---|---|
| [look.md](look.md) | Name, logo assets, fonts, colours, background, motion |
| [home.md](home.md) | Home screen: info block, accordion rows, apps, Continue Watching, live TV row |
| [libraries.md](libraries.md) | Movies and TV Shows: Recommended, Browse, Collections, Categories, detail pages |
| [search.md](search.md) | Search over Plex and IPTV channels |
| [live-tv.md](live-tv.md) | TV tab, live previews, live player |
| [player.md](player.md) | Plex player: controls, time, subtitles, audio |
| [apps.md](apps.md) | Apps page and the Home apps row |
| [setup.md](setup.md) | QR setup page for Plex and IPTV |
| [people.md](people.md) | Cast while paused, cast row, person pages with filmography and status |
| [seerr.md](seerr.md) | Seerr: setup, search outside the library, requests per profile |
| [profiles.md](profiles.md) | Plex Home members as profiles: their rows on Home, watched as them |
| [language.md](language.md) | App language: English, German, Turkish |

Each spec has: purpose, behaviour, remote keys, checks (what to verify on the TV or in the
JVM tests) and open points. The remote is a TV remote over HDMI-CEC: D-pad, OK, Back and
play/pause only.
