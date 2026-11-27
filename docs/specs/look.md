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
- Live channels play in the same frame too. Until their picture starts the frame shows a calm
  gradient (no logo placeholder) and the fill a neutral dark tone; once the picture runs, the
  fill takes the colours of its first frames (four quarters, as for pictures).
- The fill is always there while a frame is shown and fades together with the frame: its colours
  glide over the same 450 ms as the picture's cross-fade, so frame and fill never change apart.
- Apps use one Bing photo of the day for all apps, never movie artwork.
- Picture, video and fill are one layer, faded as a whole: half way through a fade the picture's
  hard edges never show through a half transparent fill, and a trailer fades out with its picture
  (its playback stops at once, so two live channels never run together). A picture appears only
  once its colours are known (at most 700 ms), then the layer fades in over 300 ms. To a page or
  tab without picture it fades out (200 ms).
- Videos in the frame (trailers, live channels) fill it like a photo: another shape (4:3, 21:9) is
  scaled up and cut at the edges, never stretched.
- A cut instead of a cross-fade: moving to another row on Home, switching to another page or to
  a page without picture, the old picture and its fill disappear at once and the new one fades in
  (300 ms). Within a row the cross-fade stays. A page never shows the previous page's artwork,
  and a row never shows the previous row's picture behind its text.

## Side menu
- Closed: icons on a soft shade, the open page marked by an amber bar and amber icon. Above the
  icons the small Masal mark, always in the same place; the name "Masal" joins it when the menu
  opens.
- Open (focus in the menu): a rounded card (#1C1A18, 18 dp corners, fine edge, soft shadow)
  floats over the page with space above, below and to the left, as wide as the longest label in
  the current language needs; the page behind dims slightly (25 % black). Focus is a soft light
  field (white 12 %), no glow, no zoom, also while OK is pressed (no dark flash); the open page
  stays amber (its bar shows only while the menu is closed).

## Motion
- Rows and lazy lists move with a critically damped spring (no bounce, no lag on key repeat).
- Card rows: Android TV pulls the focused card to 30 % of the row's width, which can leave the last
  few dp of an earlier card at the row's left edge. A card showing less than a quarter of its width
  there is not drawn, so no thin slit appears; a card cut further in stays visible as it is
  (`noSliverAtStart`: Home rows, shelves, episodes and seasons, cast, the Apps page's Home row).
- Info block follows focus after 90 ms; background after 150 ms, cross-fade 450 ms, colours glide.
  To another row both change at once (cut, see above).
- Page switch from the menu after resting 150 ms; library tabs after 150 ms.
- Images fade in over 300 ms (switched off in JVM tests, see `ImageFades`).

## Checks
- Fast D-pad repeat along a row: the row follows smoothly and stops with the focus.
- Moving right along a Home row: no thin strip of a card at the row's left edge.
- Between two titles the background never flashes dark.
- Flicking fast down through the Home rows: no picture of an earlier row appears.
- Movies to TV in the menu: the movie picture is gone before the TV tab shows.
- From a title to a live channel and back: frame and colour fill change together, no visible edge.
- No visible edge where the picture meets the colour fill.
