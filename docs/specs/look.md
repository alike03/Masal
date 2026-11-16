# Look and motion

## Name and assets
- Shown name: **Masal**. Package `com.alike.masal`; code and folder names stay "Masal".
- Logo: open book with moon, star and play triangle on #1D1916.
  - App icon: `res/drawable-nodpi/ic_launcher_logo.png` (adaptive icon, background `@color/icon_bg`).
  - TV banner: `res/drawable-xhdpi/banner.png`, 640 x 360, logo + "Masal" in Fraunces soft.
  - Side menu: `res/drawable-nodpi/masal_mark.png` next to the wordmark.
  - Setup page favicon: `res/raw/favicon.png`.
- The banner is rendered with ImageMagick + Pango from the logo and Fraunces
  `@wght=600,SOFT=100,opsz=144` (Pango can set variable font axes).

## Fonts
- DM Sans for the whole interface (`MasalFont`).
- Fraunces, soft cut, only for the "Masal" wordmark (`MasalWordmarkFont`).
- Both OFL, licences in `licenses/`. Plex's own PlexCircular is licensed: never bundle it.

## Colours
Background #121110, panel #1E1C1A, focused panel #2C2824, line #36322E, amber accent #E0A458,
text #F2EEE8, dim text #A8A29A. Focus frame on cards is amber.

## Background (Plex layout)
- The picture sits in a top-right 16:9 frame, 62 % of the screen width.
- The rest of the screen is filled with the picture's colours: Plex's UltraBlurColors when
  present, otherwise the four image quarters, darkened. Corners are blended bilinearly (top edge
  top-left to top-right, bottom edge bottom-left to bottom-right), so next to the picture the
  fill has the picture's own edge colour and the fade has no dark band.
- The fill is drawn once with a soft hole for the picture; trailers play in the same frame.
- Apps use one Bing photo of the day for all apps, never movie artwork.
- Live channels show only a calm gradient until their picture starts: no logo placeholder.

## Motion
- Rows and lazy lists move with a critically damped spring (no bounce, no lag on key repeat).
- Info block follows focus after 90 ms; background after 150 ms, cross-fade 450 ms, colours glide.
- Page switch from the menu after resting 150 ms; library tabs after 150 ms.
- Images fade in over 300 ms (switched off in JVM tests, see `ImageFades`).

## Checks
- Fast D-pad repeat along a row: the row follows smoothly and stops with the focus.
- Between two titles the background never flashes dark.
- No visible edge where the picture meets the colour fill.
