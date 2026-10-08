# OpenTV Mobile roadmap

What might come after 1.0. Nothing here is promised — it's a list of directions, roughly in order
of how often they come up. Suggestions and pull requests are welcome; open an issue first for
anything large.

## Fixes

- **Provider type picker hides "Stalker portal" in portrait** — the three type buttons on the Add
  provider screen don't fit one row on a phone, and users miss the third one (it only shows once
  the phone is turned). Replace them with a full-width three-way selector with short labels:
  Xtream / M3U / Stalker.

## Next

- **Local M3U files** — pick a `.m3u`/`.m3u8` from the phone instead of typing a URL. The file is
  copied into the app and can be re-imported when you have a newer one (a local file can't
  refresh itself the way a URL does).
- **Movie and series playback controls** ([#1](https://github.com/opentv-mobile/opentv-mobile/issues/1))
  — bring live TV's Fit / Fill / Stretch options and Picture-in-Picture to on-demand playback,
  and add portrait / landscape, automatic rotation, and orientation locking. Arbitrary rotation
  of the video image is outside the planned scope.
- **Programme guide on the phone** — a touch-friendly grid (swipe through time) alongside the
  now/next list.
- **Paging for huge film/series libraries** — load "All" shelves and grids incrementally.
- **Home-screen widget** — your Recent channels one tap from the launcher.

## Later

- **Chromecast / Google Cast** — cast live channels (HLS) and MP4 films to a TV. (Chromecast with
  Google TV can already run the app itself.)
- **More providers tested** — M3U and Stalker portals get the same polish Xtream has had.

## Background

OpenTV's original feature analysis (benchmarked against TiviMate, IPTV Smarters and others) lives
in the [upstream repository](https://github.com/opentvproject/opentv/blob/main/docs/ROADMAP.md).
