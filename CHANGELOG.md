# Changelog

What changed in each release of Masal, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow [Semantic Versioning](https://semver.org/).
The release pipeline publishes each version's section as its GitHub release notes.

## [Unreleased]

## [1.5.0] - 2026-11-23

### Added
- Seerr Discover rows on Home (Trending, Popular, Upcoming and Seerr's custom sliders), with "In Plex", "Requested" and release-date badges; choose them in Settings, Home rows.
- Home's row order can be changed, on the TV and on the phone page.
- Seerr title pages play the trailer full screen as soon as they open, from Plex's online catalogue or else from YouTube, and slide to the details with Down. Upcoming titles show their release day.
- YouTube trailers play in Masal's own player (found with NewPipe Extractor); when that fails, "Open in YouTube" opens them in a YouTube app.
- "Request" asks "Who is asking?" and files the request in Seerr under the person picked; for a series the seasons follow.
- Seerr language setting for titles, summaries and posters.
- Settings on the TV open on a start page with one page per section.
- Phone setup page: a fixed address with PIN sign-in, always running, laid out in the same six sections as the TV's Settings. New on the phone: Home row order, live TV category order, player settings, Seerr language and request users, "New PIN".

### Changed
- Licence: Masal is now GPL-3.0-or-later (was MIT), because NewPipe Extractor is GPL-3.0.
- Player: the cast row opens with Down below the buttons instead of on pause; the hint is a small chevron.
- Seerr: popular and upcoming lists are no longer filtered by language; posters are not dimmed; clearer German and Turkish word for a title being added.

### Fixed
- The QR code on the TV fills in the PIN on the phone; signing in straight from the code failed in some camera apps.
- Home no longer jumps to the top after Back and starts at the top after standby.
- "Open in Plex" lands on the title's page in the Plex app.

### Removed
- YouTube's embedded player: on some Android TV devices it played sound but showed only a black picture.
- The setting for an own trailer service; Masal finds YouTube streams itself.

## [1.4.0] - 2026-11-20

### Added
- Profiles: Plex Home members, each with their own rows on Home.
- Home rows per profile in Settings; Favourites as the live TV row.
- Cast row and person pages; Seerr search results and requests.
- Player: "Open in Plex" button.

### Changed
- Player settings sit behind a gear button.
- Performance: player buffer capped at 100 MB, preview players built only when needed, channel list read as a stream.

## [1.3.0] - 2026-11-17

### Changed
- Side menu as a floating card, as tall as its entries, with the mark and name above it.
- Picture and colour fill move as one unit.
- Plex's row and library names are translated.

### Fixed
- Background no longer grows from a corner.
- Search results start at the top.

## [1.2.0] - 2026-11-17

### Added
- TV tab laid out like Home, with your own category order.
- Loading bar while switching channels; collection type shown on Home.

### Changed
- Live TV switches channels without black: the old channel plays until the new one starts.
- Version code comes from the commit count, so newer builds always install over older ones.

### Fixed
- Masal opens after an install also when the TV is in standby.

## [1.1.0] - 2026-11-17

### Added
- Masal can be the device's home screen.
- Choose Home rows and collections; Settings on the TV.
- Live channels play in the picture frame and zoom to full screen without reconnecting.
- Player settings: subtitle size, colour, background and position, skip lengths.
- English, German and Turkish.

### Fixed
- No line between the picture and the colour fill.

## [1.0.0] - 2026-11-16

### Added
- First release: Home with Plex rows, IPTV live TV and pinned apps, Plex player, setup page on the phone, signed releases on GitHub.

[Unreleased]: https://github.com/alike03/Masal/compare/v1.5.0...HEAD
[1.5.0]: https://github.com/alike03/Masal/compare/v1.4.0...v1.5.0
[1.4.0]: https://github.com/alike03/Masal/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/alike03/Masal/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/alike03/Masal/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/alike03/Masal/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/alike03/Masal/releases/tag/v1.0.0
