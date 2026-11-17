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
- A picture never appears before its colours are known while its fill is not on screen (at most
  700 ms): picture and fill then fade in together (300 ms), the colours set at once. So no picture
  shows with bare edges and the fill following.
- A cut instead of a cross-fade: moving to another row on Home, switching to another page or to
  a page without picture, the old picture and its fill disappear at once and the new one fades in
  (300 ms). Within a row the cross-fade stays. A page never shows the previous page's artwork,
  and a row never shows the previous row's picture behind its text.

## Side menu
- Closed: icons on a soft shade, the open page marked by an amber bar and amber icon. Above the
  icons the small Masal mark (no lettering), always in the same place.
- Open (focus in the menu): a solid panel (#1A1816) with a 1 px line as its edge slides out with
  the labels; the page behind dims slightly (30 % black). Focus is a soft light field (white
  12 %), no glow, no zoom; the open page stays amber.

## Motion
- Rows and lazy lists move with a critically damped spring (no bounce, no lag on key repeat).
- Info block follows focus after 90 ms; background after 150 ms, cross-fade 450 ms, colours glide.
  To another row both change at once (cut, see above).
- Page switch from the menu after resting 150 ms; library tabs after 150 ms.
- Images fade in over 300 ms (switched off in JVM tests, see `ImageFades`).

## Checks
- Fast D-pad repeat along a row: the row follows smoothly and stops with the focus.
- Between two titles the background never flashes dark.
- Flicking fast down through the Home rows: no picture of an earlier row appears.
- Movies to TV in the menu: the movie picture is gone before the TV tab shows.
- From a title to a live channel and back: frame and colour fill change together, no visible edge.
- No visible edge where the picture meets the colour fill.
