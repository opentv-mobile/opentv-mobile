# OpenTV Mobile roadmap

Implemented work for 1.1.0 and possible next directions. Future items are not promised, roughly in order
of how often they come up. Suggestions and pull requests are welcome; open an issue first for
anything large.

## 1.1.0 release preparation

The items below are implemented on `feature/roadmap-1.1`. The user approved general manual testing
and explicitly requested the 1.1.0 release. This is not evidence that every exhaustive device,
locale or edge case passed. Commit, push, tag and CI publication are still pending.
The acceptance order and device cases remain in
[`MANUAL_TEST_ROADMAP.md`](MANUAL_TEST_ROADMAP.md).

## Included in 1.1.0

- **Phone provider selector** — full-width Xtream / M3U / Stalker segments, including portrait,
  large-font and RTL handling.
- **Local M3U files** — document picker, validated private snapshot, atomic re-import and explicit
  remote XMLTV support. Catalogue refresh skips local snapshots until the user re-imports one.
- **Movie and series playback controls** ([#1](https://github.com/opentv-mobile/opentv-mobile/issues/1))
  — shared Fit / Fill / Stretch preference, Picture-in-Picture and per-session landscape,
  portrait, system and locked orientation. Arbitrary rotation of the video image is out of scope.
- **Programme guide on the phone** — list/guide switch, touch time grid, local-day navigation,
  UTC-duration geometry across DST and programme actions.
- **Paging for huge film/series libraries** — Room/Paging grids for categories and “All”, with
  provider-scoped category identities, movie quality/provider variant selection, empty/error states
  and per-filter scroll restoration.
- **Home-screen widget** — up to six recent logical channels, cold/warm launch handoff,
  parental filtering and launcher resize handling.
- **Adaptive phone chrome** — compact landscape side navigation, scrollable browsing controls and
  safe insets for system bars/cutouts; player sessions default to landscape and phone browsing to
  portrait unless navigation rotation is enabled.
- **Localization** — new controls and messages translated across all supported app locales.

## Later

- **Chromecast / Google Cast** — cast live channels (HLS) and MP4 films to a TV. (Chromecast with
  Google TV can already run the app itself.)
- **More providers tested** — M3U and Stalker portals get the same polish Xtream has had.

## Background

OpenTV's original feature analysis (benchmarked against TiviMate, IPTV Smarters and others) lives
in the [upstream repository](https://github.com/opentvproject/opentv/blob/main/docs/ROADMAP.md).
