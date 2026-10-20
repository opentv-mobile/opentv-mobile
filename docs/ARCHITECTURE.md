# Architecture

This document explains the decisions that are not obvious from reading the code, and in
particular the ones made in direct response to how apps in this category typically fail.

## The shape of it

```
app/src/main/java/app/opentv/
├── core/           ServiceLocator — the whole dependency graph, one file
├── data/
│   ├── model/      Domain models, which double as Room entities
│   ├── db/         Room database and DAOs
│   ├── remote/     XtreamApi — the provider panel client
│   ├── local/      Private snapshots of user-selected local playlists
│   ├── parser/     M3uParser, XmltvParser — pure, streaming, heavily tested
│   ├── repo/       SourceRepository, CatalogRepository, EpgRepository, VodBrowseRepository
│   └── work/       SyncWorker — periodic background refresh
├── player/         PlayerController, PlaybackErrors
├── widget/         Recent-channel widget and ID-only app handoff
└── ui/             Compose screens and view models
```

Single module, single Gradle build. A multi-module setup would build faster in CI and is what
a team of ten would do. This is not a team of ten; it is a project whose survival depends on a
stranger with a broken channel being able to find the relevant code in under five minutes.

## No backend. At all.

OpenTV has no service operated by the project. There is no account or hosted cloud backend. The
optional browser manager is served directly by the user's device on the local network, and optional
device sync is direct or uses storage owned by the user.

This is the single most important architectural decision in the project, and it is worth being
explicit about why, because a web dashboard is a genuinely nice feature and users ask for it.

**A cloud dashboard has to be paid for every month, forever, per user.** An app funded by a
one-off payment or by donations cannot carry that. Every player that has tried has arrived at
the same place: the hosting bill grows with success, the revenue does not, and eventually the
servers go quiet — taking the app with them, because by then the app cannot start without
them. Users who paid discover that their "lifetime" purchase bought them a client for a
service that no longer exists.

So OpenTV stores everything on the device, in SQLite. The consequences are accepted honestly:

- Without opting into local/NAS sync, setting up a second device means entering details again and
  user state remains local. This is a real cost.

And what is bought with it:

- The app costs nothing to run, so it cannot be switched off.
- Provider credentials never leave the device. Nobody has to trust the maintainers with them.
- It works with no internet connection to anything except your own provider.

If multi-device setup becomes painful enough, the answer is **export/import of a settings file**
— or, at most, optional sync through storage the *user* already owns and pays for. Never a
service the project has to fund.

## Local playlists are immutable snapshots

The Android document picker grants temporary read access. `LocalPlaylistStore` copies the selected
`.m3u`/`.m3u8` into app-private storage, validates the complete copy, parses it, then publishes the
source and catalogue in one Room transaction. A failed or cancelled import deletes only the new
temporary snapshot. A successful re-import commits first and deletes the previous private copy
afterwards, so it cannot replace a working catalogue with a partial one.

Local playlists accept IPTV entries with absolute remote stream URLs; local media paths and HLS
media manifests are rejected. They cannot refresh themselves, so catalogue refresh workers skip
them. An explicitly configured remote XMLTV URL remains independent and can still refresh.

## Large VOD catalogues page from Room

`VodBrowseRepository` builds Paging 3 flows over Room `PagingSource`s. Browse cards do not load all
variants into memory: SQL returns one logical movie group per row and variants are fetched only when
a card is opened. A category key is `(sourceId, categoryId)`, because provider-local IDs routinely
collide. The Compose grid saves its viewport by filter key, restoring it after a detail screen but
starting at the top for a genuinely different provider/category filter.

## Player display policy is shared, orientation is session state

Live and VOD use the same persisted Media3 resize mode for Fit, Fill and Stretch. Orientation is
different: `PlayerOrientationSession` starts in landscape for each playback destination and can be
changed to portrait, system rotation or the physical rotation captured by Lock. PiP and multi-window
resizes never rewrite that policy. PiP entry requires actual playback; when neither the activity nor
its PiP window is visible, playback is no longer allowed to continue invisibly.

## The home widget exposes IDs, not streams

The recent-channel widget reads a bounded projection without stream URLs, portal commands,
credentials or programme metadata. It filters parental categories before rendering and validates
the channel again when tapped. The launcher hands only a channel ID to `MainActivity`; the app then
resolves it, gives the explicit request priority over resume-last behaviour and applies the normal
recording and parental checks. Deleting a source therefore leaves no playable URL embedded in a
stale widget row.

## The EPG, and why it is written the way it is

A guide that quietly stops working is the defining bug of this app category. It is almost
always caused by the same implementation:

```
delete everything for this source
download the new guide
insert it
```

That is fine when it works. When the download stalls, the provider rate-limits, the box sleeps
mid-sync, or the XML is truncated, the user is left with an empty guide — and the only advice
anyone can give is "reinstall the app", which appears to help because it restarts the sync from
scratch. It fixes nothing. The next refresh fails the same way.

`EpgRepository` never deletes before it has the replacement:

1. Programmes are **upserted in batches of 500** as they stream out of the parser. The unique
   index on `(sourceId, epgChannelId, startUtcMillis)` makes this idempotent, so a sync that
   dies at 60% leaves 60% of a *fresher* guide — strictly better than what was there.
2. Pruning happens **by age** (`deleteEndedBefore`), never by source, and only **after** a sync
   reports success.
3. A failed sync leaves the previous guide completely intact and returns the reason.

`XmltvParser` streams via `XmlPullParser` and emits programmes to a callback. A week of guide
data for a large provider is comfortably 150 MB of XML; nothing about that fits in the heap of
a cheap TV stick, and `String`-based parsing is an OOM waiting to happen.

**Times are stored as UTC epoch millis, always.** `XmltvParser.parseXmltvTime` normalises the
`+0100`-style offsets that XMLTV uses, treats a missing offset as UTC rather than guessing the
device zone, and converts to local time only at draw time. `XmltvTimeTest` changes the JVM's
default zone between assertions specifically to catch the "works on my machine, an hour out on
yours" bug.

## Catalogue refresh preserves what the user did

`ChannelDao.replaceCatalogue` merges rather than wipes: it reads existing rows, carries
`favourite`, `hidden` and `sortIndex` across to the incoming rows by `streamId`, upserts, and
only then deletes channels the provider no longer lists.

This is why `M3uParser` works so hard to produce a **stable** `streamId` for playlists with no
`tvg-id` — an FNV-1a hash of the URL rather than `String.hashCode()`. An unstable id means
every refresh looks like a completely new set of channels, and the user's favourites silently
vanish.

## SQLite's bound-variable limit is a real constraint here

Two queries are written in a non-obvious way because of it, and both are marked in the code.

SQLite caps the number of bound variables in a statement — 999 on older Android versions,
32766 on newer. A real IPTV catalogue has thousands of channels and a large playlist has tens
of thousands. So neither of these works, despite being the natural thing to write:

- `DELETE FROM channels WHERE streamId NOT IN (:everyCurrentId)` — replaced by a sync-stamp
  comparison (`Channel.lastSeenMillis`), which has no limit.
- `SELECT * FROM programmes WHERE epgChannelId IN (:everyChannelId)` — replaced by a
  time-window query with grouping done in memory by the caller.

Both would pass every test written against a small synthetic dataset and fail on exactly the
providers where it matters most.

## Playback

`PlayerController` owns exactly one `ExoPlayer` for the lifetime of the screen and only ever
swaps its media item.

The naive implementation of "change channel" releases the player and builds a new one. On a
low-end box that is slow enough that a user holding channel-up queues several constructions and
teardowns, and the codec ends up in a state where nothing plays until the app is force-stopped.
That is the entire mechanism behind "changing channels too quickly causes streams to fail", and
it is self-inflicted.

So: requests are debounced (350 ms), an in-flight switch is cancelled the moment a newer one
arrives, and the player is `stop()`ed rather than released. Holding channel-up costs one actual
tune — the one you stopped on.

### Retries are tuned for IPTV, not for CDNs

ExoPlayer's default policy gives up almost immediately on a 403 or a 5xx. That is right for a
CDN and wrong here: a provider under load returns 403, 429 or 503 for a few seconds and then
serves the stream perfectly. Users experience the default as "this channel doesn't work" when
it demonstrably works on the second attempt.

`PlayerController` retries those with exponential backoff (500 ms doubling to 8 s, 5 attempts)
plus up to three silent restarts. `401`, `404` and `410` are **not** retried — those will not
fix themselves.

### Errors say what to do

`PlaybackErrors` turns exceptions into sentences. `ERROR_CODE_IO_BAD_HTTP_STATUS` tells a user
nothing; "the account may already be streaming on another device, or the provider may be
blocking this app's User-Agent — you can change it in the source's advanced settings" tells them
what to try.

This matters more than it sounds. The alternative habit — hiding the error code because users
complained about seeing it — treats the symptom while the stream still fails, and removes the
only diagnostic the user had.

## Defensive parsing of Xtream responses

`XtreamApi` reads `JsonElement` by hand instead of deserialising into `@Serializable` data
classes, and every accessor returns null rather than throwing.

Xtream Codes is not a standard. Different panel builds return the same field as a number, a
quoted number, an empty string, `null`, or omit it. `stream_id` alone flips between `12345` and
`"12345"`. Strict deserialisation means one unusual field aborts the parse of the entire
catalogue, which the user experiences as "the app won't load my channels" with nothing to go on.

It is ugly. It is correct. Do not "clean it up" into typed models without a very large corpus
of real panel responses to test against.

Similarly, `syncXtream` treats VOD as optional: plenty of accounts are live-TV only, and a 404
on `get_vod_streams` must never cost the user their channel list.

## Dependency injection

`ServiceLocator` is a hand-rolled container. Hilt is the conventional choice and is
deliberately not used: it adds an annotation processor, a build-time graph and a class of error
message that is genuinely hard to read, in exchange for wiring about a dozen objects. For a
project hoping for drive-by contributions from people fixing their own bugs, "you can read the
whole graph in one file" is worth more than the ceremony.

## Testing

The parsers are pure functions over strings and streams, which makes them cheap to test
exhaustively — and they are where the bugs that ruin someone's evening actually live. Every
malformed case in `M3uParserTest` is something that should cost the user one channel, never the
whole playlist.

Things worth testing that are not yet covered, and would be very welcome contributions:

- `EpgRepository.sync` against a mid-stream failure, asserting the old guide survives
- `ChannelDao.replaceCatalogue` preserving favourites across a refresh
- `XtreamApi` against captured real-world panel responses (with credentials stripped)
