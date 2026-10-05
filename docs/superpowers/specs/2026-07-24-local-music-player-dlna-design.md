# Flutter Local Music Player with DLNA Design

## Goal

Build a free, non-commercial Flutter Android local music player that is usable with TalkBack, keyboard, and alternative input. It plays local music with the BASS audio library and can cast the original media file to a DLNA/UPnP renderer on the same Wi-Fi network. Xiaomi TV is the first physical acceptance target.

## Scope

The first release provides:

- Android MediaStore music discovery, including songs, albums, artists, artwork, and local media URIs.
- Local playback, queueing, seek, repeat, shuffle, playback position, and EQ through BASS.
- Background playback, lock-screen controls, notification controls, headset controls, and audio-focus management through `audio_service` and `audio_session`.
- Explicit DLNA renderer discovery, device selection, original MP3 and FLAC file casting, playback controls, seeking, track changes, and return to local playback.
- A visible, recoverable state for permission, network, device, format, and playback failures.

The first release does not provide:

- Google Cast, AirPlay, screen mirroring, file transfer, cloud playback, account features, or ads.
- DLNA transcoding, casting BASS EQ/DSP output, multi-room synchronization, or a generic media-server library.
- A promise that every DLNA device plays high-resolution audio. Device capability determines the supported format.

## Technical Decisions

| Area | Decision | Reason |
| --- | --- | --- |
| App platform | Flutter for Android | Matches the requested framework. |
| Local audio engine | BASS 2.4 Android in a project-owned Flutter plugin | Low-latency, high-quality local playback; free for the confirmed non-commercial use. |
| System media integration | `audio_service` and `audio_session` | Supplies Android media session, notification, background, headset, and audio-focus behavior. |
| Local library | `on_audio_query` over Android MediaStore | Fast first implementation of song and artwork discovery. Keep it behind a repository interface so it can later be replaced by a small platform channel if needed. |
| DLNA client | `upnp_client` plus a small app-owned adapter | Provides discovery and casting foundations without introducing Android-only UI or a large native stack. |
| Cast source | Short-lived, in-app HTTP server | A renderer cannot fetch the phone's `content://` URI. It needs a LAN-reachable HTTP URL with byte-range support. |

## Component Boundaries

```text
MediaStore -> LibraryRepository -> Flutter screens
                                  |
                                  v
                          PlaybackCoordinator
                           /                 \
                     BassPlayer            DlnaCastSession
                       |                    /             \
                 audio_service        DlnaDiscovery    LocalMediaServer
```

`LibraryRepository` returns app media models rather than plugin-specific objects. It owns permission-aware library reads.

`BassPlayer` is the only local audio-engine adapter. It exposes queue, playback state, current item, position, duration, seek, repeat, shuffle, and EQ. The plugin packages the BASS Android binaries and exposes a narrow Dart API.

`DlnaDiscovery` discovers DMR devices only after an explicit user action. It maps device descriptions into a stable `DlnaDevice` model containing UDN, display name, endpoint URLs, supported services, and advertised sink protocol information.

`LocalMediaServer` exposes only the current cast item. It binds to the active Wi-Fi address, uses an unguessable session path, supports `HEAD`, `GET`, and HTTP byte ranges, and reads the original media through Android's content resolver. It has no directory listing, upload, or general file serving capability.

`DlnaCastSession` owns AVTransport and RenderingControl calls. It validates a selected renderer, loads the media URL, controls transport, polls position, and surfaces renderer errors.

`PlaybackCoordinator` is the sole owner of user-visible playback state and queue position. It prevents BASS and a renderer from playing the same item simultaneously.

## Playback State Machine

The coordinator has these primary modes:

- `idle`: no active media.
- `local`: BASS owns playback.
- `preparingCast`: BASS is paused while the selected device, media server, and renderer command sequence are validated.
- `casting`: the renderer owns playback and BASS remains paused.
- `castInterrupted`: a renderer or network failure ended casting; queue and last position remain recoverable, but local output does not start automatically.
- `error`: an operation failed before a playable mode was established.

Local-to-cast transition:

1. The user starts discovery, selects a device, and explicitly starts casting.
2. Validate Wi-Fi availability, renderer services, source readability, MIME type, and advertised compatibility when available.
3. Start `LocalMediaServer`, pause BASS, load the tokenized URL with `SetAVTransportURI`, seek to the local position when supported, and send `Play`.
4. Enter `casting` only after the renderer reports a playable or playing state. Otherwise, stop the server and restore the paused local state.

Cast-to-local transition:

1. The user explicitly chooses return to phone playback.
2. Stop the renderer, close the media server, and resume BASS at the last known renderer position.

When a cast session drops unexpectedly, preserve the queue, device name, media item, and last position. Show a recovery action; do not resume phone speakers automatically.

## Media Compatibility and Quality

Cast the original bytes without transcoding. The first release accepts MP3 and FLAC sources.

For Chromecast-class devices, FLAC 24-bit/96 kHz is a reasonable high-resolution target. DLNA has no equivalent universal ceiling: use each renderer's advertised capabilities and reject or warn on unsupported sources rather than silently reducing quality. Xiaomi TV is validated with MP3 at 320 kbps, CD-quality FLAC, and FLAC 24-bit/96 kHz where the specific renderer accepts it.

BASS EQ and DSP apply only to local playback. They are not part of the direct file cast path.

## UI and Accessibility Constraints

The authoritative baseline is `android-accessibility-standard-zh.md` at the repository root. Although it uses Android View examples, the Flutter implementation must preserve its behavioral requirements through semantic widgets, meaningful actions, predictable focus, native controls where suitable, and Android platform integration where Flutter alone cannot expose required semantics.

Specific application rules:

- Device discovery is user initiated. Selecting a device never starts playback by itself.
- The cast picker is a modal task boundary. It has a clear title, an explicit close path, background isolation, and focus returns to the cast trigger when it closes.
- Every action has a short task-oriented semantic name. Device rows communicate name, DLNA role, and availability.
- Playback state, selection state, errors, and outcomes are represented semantically and visibly. Announcements occur after user actions or important asynchronous outcomes, never for ordinary progress ticks or list rebuilding.
- Song rows and controls expose one complete task per focus target. Independent actions remain separately reachable or are exposed as explicitly named semantic actions.
- The progress control exposes current position, duration, range, and seek actions. It remains usable without drag gestures.
- No auto-start, implicit destructive operation, focus theft, color-only state, icon-only meaning, or gesture-only command is permitted.

## Error Handling

| Condition | Behavior | Recovery |
| --- | --- | --- |
| Music permission unavailable | Show the permission state instead of an empty library. | Request permission explicitly or open system settings. |
| Wi-Fi unavailable | Do not start discovery or the HTTP server. | Retry after connecting to Wi-Fi. |
| No DMR devices found | Finish scan with an explicit empty result. | Refresh scan. |
| Renderer cannot play source type | Preserve local playback and identify the device and unsupported source format. | Choose another device or another track. |
| Phone source cannot be read | Do not start cast commands. | Return to local library and choose a readable item. |
| Renderer rejects URL or times out | Stop the session server and restore the paused local state. | Retry casting or choose another device. |
| Network path fails or app service is stopped | Enter `castInterrupted`; never enable phone output automatically. | Return to phone playback or reconnect and retry. |

Casting keeps a foreground media service active while the phone hosts media, so Android does not suspend the server in the background. The service stops when the cast session ends.

## Verification

Automated tests cover:

- Playback coordinator mode transitions, including every failed cast transition.
- DLNA device-description parsing, protocol compatibility policy, AVTransport command formation, and position conversion.
- HTTP server authentication path, MIME selection, byte-range responses, and shutdown behavior.
- Library mapping, media session command routing, and accessibility label/state/action policies.

Manual device testing covers:

- Xiaomi TV discovery, selection, load, playback, pause, seek, next/previous, return to phone, and unexpected disconnection.
- MP3 320 kbps, CD-quality FLAC, and 24-bit/96 kHz FLAC when the TV advertises support.
- Local playback lifecycle: screen off, notification controls, headset controls, audio focus interruption, and app backgrounding.
- TalkBack linear navigation, headings, default activation, semantic actions, modal open/close focus restoration, errors, large text, external keyboard/D-pad, and screen rotation.

## Acceptance Criteria

The first implementation is accepted when a Xiaomi TV can be deliberately selected from the player, play an original local MP3 or supported FLAC over the same Wi-Fi, respond to player controls, and return to local BASS playback without losing queue position. The cast server must not expose unrelated files and must close at session end. The complete player and cast workflow must be operable with TalkBack and without gesture-only controls, according to the repository accessibility baseline.
