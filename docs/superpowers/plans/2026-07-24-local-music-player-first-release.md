# Local Music Player First Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver an installable Flutter Android local music player that plays device media through BASS and casts original MP3/FLAC files to a DLNA renderer, with Xiaomi TV as the physical acceptance target.

**Architecture:** Flutter owns presentation and one `PlaybackCoordinator` state machine. A project-owned Kotlin BASS bridge is the local playback engine; a Dart DLNA layer discovers and controls renderers while a tokenized HTTP server exposes only the current local media item. System media integration goes through `audio_service` and `audio_session`.

**Tech Stack:** Flutter 3.27+, Dart 3.6+, Kotlin, BASS 2.4.18.3 Android, `audio_service`, `audio_session`, `on_audio_query`, `upnp_client`, Dart `HttpServer`, Flutter `Semantics`, Android MediaStore.

## Global Constraints

- Target Android only; use Flutter 3.27+ and Dart 3.6+.
- The app is free and non-commercial; include the BASS copyright and license notice in the in-app about screen and source distribution.
- Use BASS only for phone-local playback. DLNA serves original source bytes without transcoding or BASS DSP/EQ.
- Support source MP3 and FLAC for first release. Never silently lower cast quality.
- Device scanning and casting start only after explicit user actions.
- Use `android-accessibility-standard-zh.md` as the binding baseline. Map its View rules to Flutter semantics and native Android integration; do not add gesture-only or color-only operations.
- Modal surfaces restore focus to their trigger; dynamic progress must not steal focus or announce on every tick.
- The media HTTP server exposes one tokenized item, supports `HEAD`/`GET`/Range, binds to the active LAN address, and stops at session end.
- Use TDD for pure Dart state, protocol, and server code. Use Flutter widget tests for semantic labels and Android-device tests for BASS and actual DLNA behavior.

---

## Planned File Structure

```text
android/
  app/src/main/AndroidManifest.xml
  app/src/main/java/com/un4seen/bass/BASS.java
  app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86,x86_64}/libbass.so
  app/src/main/kotlin/com/example/localmusicplayer/BassBridge.kt
  app/src/main/kotlin/com/example/localmusicplayer/MainActivity.kt
lib/
  app.dart
  main.dart
  domain/models/{track.dart,playback_mode.dart,dlna_device.dart}.dart
  domain/ports/{bass_player.dart,library_repository.dart,dlna_renderer.dart}.dart
  domain/playback_coordinator.dart
  infrastructure/{media_store_library_repository.dart,bass_method_channel_player.dart}.dart
  infrastructure/dlna/{dlna_discovery.dart,dlna_renderer_client.dart,local_media_server.dart,source_reader.dart}.dart
  application/player_audio_handler.dart
  presentation/{home_screen.dart,now_playing_screen.dart,cast_picker.dart,player_controls.dart}.dart
test/
  domain/playback_coordinator_test.dart
  infrastructure/dlna/local_media_server_test.dart
  presentation/semantic_controls_test.dart
assets/licenses/bass-license.txt
```

### Task 1: Create the Flutter Android Application and Baseline Configuration

**Files:**
- Create: `pubspec.yaml`
- Create: `analysis_options.yaml`
- Create: `lib/main.dart`
- Create: `lib/app.dart`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Create: `test/app_smoke_test.dart`

**Interfaces:**
- Produces `LocalMusicPlayerApp`, the application root used by all later widget tests.
- Produces Android permissions required by MediaStore, LAN HTTP serving, Wi-Fi multicast discovery, and foreground media playback.

- [ ] **Step 1: Generate the project without overwriting repository documents**

Run:

```powershell
flutter create --org com.example --platforms android --project-name local_music_player .
```

Expected: Flutter creates `android/`, `lib/`, `test/`, `pubspec.yaml`, and Gradle files while preserving `docs/` and `android-accessibility-standard-zh.md`.

- [ ] **Step 2: Write the failing root-widget test**

```dart
import 'package:flutter_test/flutter_test.dart';
import 'package:local_music_player/app.dart';

void main() {
  testWidgets('shows the local music library heading', (tester) async {
    await tester.pumpWidget(const LocalMusicPlayerApp());

    expect(find.text('本地音乐'), findsOneWidget);
  });
}
```

- [ ] **Step 3: Run the test to verify it fails before the app root exists**

Run: `flutter test test/app_smoke_test.dart`

Expected: FAIL because `LocalMusicPlayerApp` has not been defined.

- [ ] **Step 4: Add dependencies and minimal app root**

```yaml
dependencies:
  flutter:
    sdk: flutter
  audio_service: ^0.18.19
  audio_session: ^0.2.4
  on_audio_query: ^2.9.0
  upnp_client: ^1.0.4
  uuid: ^4.5.1

dev_dependencies:
  flutter_test:
    sdk: flutter
  flutter_lints: ^5.0.0
```

```dart
// lib/main.dart
import 'package:flutter/widgets.dart';
import 'app.dart';

void main() => runApp(const LocalMusicPlayerApp());
```

```dart
// lib/app.dart
import 'package:flutter/material.dart';

class LocalMusicPlayerApp extends StatelessWidget {
  const LocalMusicPlayerApp({super.key});

  @override
  Widget build(BuildContext context) => const MaterialApp(
        home: Scaffold(
          body: SafeArea(child: Text('本地音乐')),
        ),
      );
}
```

Add these manifest declarations inside `<manifest>` and `<application>`:

```xml
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
```

```xml
<application android:label="本地音乐播放器" android:usesCleartextTraffic="true">
```

`usesCleartextTraffic` is required because many DLNA renderers fetch LAN HTTP URLs. The server is constrained in Task 7.

- [ ] **Step 5: Run formatter, analyzer, and test**

Run:

```powershell
dart format lib test
flutter analyze
flutter test test/app_smoke_test.dart
```

Expected: all commands exit 0.

- [ ] **Step 6: Commit the scaffold**

```powershell
git add pubspec.yaml pubspec.lock analysis_options.yaml lib android test
git commit -m "feat: scaffold Flutter music player"
```

### Task 2: Model the Library and Playback State Machine

**Files:**
- Create: `lib/domain/models/track.dart`
- Create: `lib/domain/models/playback_mode.dart`
- Create: `lib/domain/models/dlna_device.dart`
- Create: `lib/domain/ports/bass_player.dart`
- Create: `lib/domain/ports/dlna_renderer.dart`
- Create: `lib/domain/playback_coordinator.dart`
- Create: `test/domain/playback_coordinator_test.dart`

**Interfaces:**
- Consumes no platform APIs.
- Produces `PlaybackCoordinator`, `Track`, `PlaybackMode`, `DlnaDevice`, `BassPlayer`, and `DlnaRenderer` for infrastructure and UI tasks.

- [ ] **Step 1: Write failing state-transition tests**

```dart
test('starts casting only after renderer confirms playback', () async {
  final bass = FakeBassPlayer(position: const Duration(seconds: 32));
  final renderer = FakeDlnaRenderer(playSucceeds: true);
  final coordinator = PlaybackCoordinator(bass: bass, renderer: renderer);

  await coordinator.startCasting(track, device, Uri.parse('http://192.168.1.9/token/a'));

  expect(coordinator.mode, PlaybackMode.casting);
  expect(bass.pauseCalls, 1);
  expect(renderer.loadedUri, Uri.parse('http://192.168.1.9/token/a'));
});

test('restores local mode when renderer rejects the URI', () async {
  final bass = FakeBassPlayer();
  final coordinator = PlaybackCoordinator(
    bass: bass,
    renderer: FakeDlnaRenderer(playSucceeds: false),
  );

  await expectLater(
    coordinator.startCasting(track, device, Uri.parse('http://192.168.1.9/token/a')),
    throwsA(isA<CastFailure>()),
  );

  expect(coordinator.mode, PlaybackMode.local);
  expect(bass.resumeCalls, 1);
});
```

- [ ] **Step 2: Run the failing test**

Run: `flutter test test/domain/playback_coordinator_test.dart`

Expected: FAIL because models, fakes, and coordinator do not exist.

- [ ] **Step 3: Implement stable domain types and coordinator**

```dart
enum PlaybackMode { idle, local, preparingCast, casting, castInterrupted, error }

class Track {
  const Track({required this.id, required this.uri, required this.title, required this.artist, required this.duration, required this.mimeType});
  final int id;
  final Uri uri;
  final String title;
  final String artist;
  final Duration duration;
  final String mimeType;
}

abstract interface class BassPlayer {
  Future<void> pause();
  Future<void> resume();
  Future<void> play(Track track, {Duration position = Duration.zero});
  Future<Duration> get position;
}

abstract interface class DlnaRenderer {
  Future<void> load(Uri source, Track track);
  Future<void> play({Duration position = Duration.zero});
  Future<void> stop();
  Future<Duration> get position;
}
```

`startCasting` must assign `preparingCast`, pause BASS, load the renderer URI, start at the captured position, then assign `casting`. On any exception it must resume BASS, assign `local`, and throw `CastFailure`. `returnToLocal` must stop the renderer and call `bass.play(currentTrack, position: renderer.position)`.

- [ ] **Step 4: Run the state tests and analyzer**

Run:

```powershell
dart format lib/domain test/domain
flutter analyze
flutter test test/domain/playback_coordinator_test.dart
```

Expected: all commands exit 0.

- [ ] **Step 5: Commit domain behavior**

```powershell
git add lib/domain test/domain
git commit -m "feat: add playback state coordinator"
```

### Task 3: Read Device Music Through a Repository Boundary

**Files:**
- Create: `lib/domain/ports/library_repository.dart`
- Create: `lib/infrastructure/media_store_library_repository.dart`
- Create: `test/infrastructure/media_store_library_repository_test.dart`

**Interfaces:**
- Consumes `Track`.
- Produces `LibraryRepository.loadTracks()` and `LibraryRepository.requestAccess()` for `HomeScreen`.

- [ ] **Step 1: Write failing mapping and permission tests**

```dart
test('maps MediaStore data to a playable track', () {
  final track = mapSong(FakeSongModel(
    id: 7,
    uri: 'content://media/external/audio/media/7',
    title: '测试歌曲',
    artist: '测试歌手',
    durationMs: 123000,
    mimeType: 'audio/flac',
  ));

  expect(track.uri.scheme, 'content');
  expect(track.duration, const Duration(minutes: 2, seconds: 3));
  expect(track.mimeType, 'audio/flac');
});
```

- [ ] **Step 2: Run the failing test**

Run: `flutter test test/infrastructure/media_store_library_repository_test.dart`

Expected: FAIL because `mapSong` and the repository are absent.

- [ ] **Step 3: Implement repository and mapping**

```dart
abstract interface class LibraryRepository {
  Future<bool> requestAccess();
  Future<List<Track>> loadTracks();
}

Track mapSong(SongModel song) => Track(
      id: song.id,
      uri: Uri.parse(song.uri!),
      title: song.title,
      artist: song.artist ?? '未知艺术家',
      duration: Duration(milliseconds: song.duration ?? 0),
      mimeType: song.mimeType ?? 'audio/mpeg',
    );
```

`MediaStoreLibraryRepository.loadTracks()` must call `permissionsStatus()` before `querySongs()`, return an empty list only after a granted query returns no songs, and throw a typed `LibraryPermissionDenied` when access is unavailable.

- [ ] **Step 4: Run tests and analyzer**

Run:

```powershell
dart format lib/infrastructure lib/domain test/infrastructure
flutter analyze
flutter test test/infrastructure/media_store_library_repository_test.dart
```

Expected: all commands exit 0.

- [ ] **Step 5: Commit repository code**

```powershell
git add lib/domain/ports/library_repository.dart lib/infrastructure/media_store_library_repository.dart test/infrastructure
git commit -m "feat: load local MediaStore tracks"
```

### Task 4: Package and Bridge BASS on Android

**Files:**
- Create: `android/app/src/main/kotlin/com/example/localmusicplayer/BassBridge.kt`
- Modify: `android/app/src/main/kotlin/com/example/localmusicplayer/MainActivity.kt`
- Create: `android/app/src/main/java/com/un4seen/bass/BASS.java`
- Create: `android/app/src/main/jniLibs/arm64-v8a/libbass.so`
- Create: `android/app/src/main/jniLibs/armeabi-v7a/libbass.so`
- Create: `android/app/src/main/jniLibs/x86/libbass.so`
- Create: `android/app/src/main/jniLibs/x86_64/libbass.so`
- Create: `lib/infrastructure/bass_method_channel_player.dart`
- Create: `test/infrastructure/bass_method_channel_player_test.dart`
- Create: `assets/licenses/bass-license.txt`

**Interfaces:**
- Consumes `BassPlayer` and `Track`.
- Produces a `MethodChannelBassPlayer` that implements `BassPlayer`, uses channel `com.example.localmusicplayer/bass`, and returns position in integer milliseconds.

- [ ] **Step 1: Write the Dart channel contract test**

```dart
test('play sends content URI and start position to BASS channel', () async {
  final calls = <MethodCall>[];
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(const MethodChannel(bassChannelName), (call) async {
    calls.add(call);
    return null;
  });
  final player = MethodChannelBassPlayer();

  await player.play(track, position: const Duration(seconds: 5));

  expect(calls.single.method, 'play');
  expect(calls.single.arguments['positionMs'], 5000);
});
```

- [ ] **Step 2: Run the failing contract test**

Run: `flutter test test/infrastructure/bass_method_channel_player_test.dart`

Expected: FAIL because the channel adapter does not exist.

- [ ] **Step 3: Copy the official BASS Android package files**

Run:

```powershell
tar -xOf bass24-android.zip java/com/un4seen/bass/BASS.java > android/app/src/main/java/com/un4seen/bass/BASS.java
tar -xOf bass24-android.zip libs/arm64-v8a/libbass.so > android/app/src/main/jniLibs/arm64-v8a/libbass.so
tar -xOf bass24-android.zip libs/armeabi-v7a/libbass.so > android/app/src/main/jniLibs/armeabi-v7a/libbass.so
tar -xOf bass24-android.zip libs/x86/libbass.so > android/app/src/main/jniLibs/x86/libbass.so
tar -xOf bass24-android.zip libs/x86_64/libbass.so > android/app/src/main/jniLibs/x86_64/libbass.so
```

Copy the BASS license text from `bass24-android.zip` into `assets/licenses/bass-license.txt` and declare that asset in `pubspec.yaml`.

- [ ] **Step 4: Implement the Kotlin bridge and Dart adapter**

```kotlin
class BassBridge(private val context: Context) : MethodChannel.MethodCallHandler {
    private var stream = 0

    init {
        check(BASS.BASS_Init(-1, 48_000, BASS.BASS_DEVICE_AUDIOTRACK)) {
            "BASS init failed: ${BASS.BASS_ErrorGetCode()}"
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "play" -> {
                stream.takeIf { it != 0 }?.let(BASS::BASS_StreamFree)
                val uri = Uri.parse(call.argument<String>("uri")!!)
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r")!!
                stream = BASS.BASS_StreamCreateFile(descriptor, 0, 0, BASS.BASS_STREAM_PRESCAN)
                check(stream != 0) { "BASS stream failed: ${BASS.BASS_ErrorGetCode()}" }
                val position = call.argument<Int>("positionMs")!!.toDouble() / 1000.0
                BASS.BASS_ChannelSetPosition(stream, BASS.BASS_ChannelSeconds2Bytes(stream, position), BASS.BASS_POS_BYTE)
                BASS.BASS_ChannelPlay(stream, false)
                result.success(null)
            }
            "pause" -> { BASS.BASS_ChannelPause(stream); result.success(null) }
            "resume" -> { BASS.BASS_ChannelPlay(stream, false); result.success(null) }
            "positionMs" -> result.success((BASS.BASS_ChannelBytes2Seconds(stream, BASS.BASS_ChannelGetPosition(stream, BASS.BASS_POS_BYTE)) * 1000).toLong())
            else -> result.notImplemented()
        }
    }
}
```

```dart
const bassChannelName = 'com.example.localmusicplayer/bass';

class MethodChannelBassPlayer implements BassPlayer {
  MethodChannelBassPlayer({MethodChannel? channel}) : _channel = channel ?? const MethodChannel(bassChannelName);
  final MethodChannel _channel;

  @override
  Future<void> play(Track track, {Duration position = Duration.zero}) => _channel.invokeMethod<void>('play', {
        'uri': track.uri.toString(),
        'positionMs': position.inMilliseconds,
      });

  @override
  Future<void> pause() => _channel.invokeMethod<void>('pause');

  @override
  Future<void> resume() => _channel.invokeMethod<void>('resume');

  @override
  Future<Duration> get position async => Duration(milliseconds: await _channel.invokeMethod<int>('positionMs') ?? 0);
}
```

`MainActivity.configureFlutterEngine` registers `BassBridge` on the named channel. The bridge must release the old stream before loading a new one and release BASS from the activity lifecycle when the process is destroyed.

- [ ] **Step 5: Run channel test and assemble an APK**

Run:

```powershell
flutter test test/infrastructure/bass_method_channel_player_test.dart
flutter build apk --debug
```

Expected: Dart contract test passes and `build/app/outputs/flutter-apk/app-debug.apk` exists.

- [ ] **Step 6: Commit BASS integration**

```powershell
git add android lib/infrastructure/bass_method_channel_player.dart assets pubspec.yaml pubspec.lock test/infrastructure
git commit -m "feat: add BASS local playback bridge"
```

### Task 5: Integrate Background Media Controls and Queue State

**Files:**
- Create: `lib/application/player_audio_handler.dart`
- Modify: `lib/domain/playback_coordinator.dart`
- Create: `test/application/player_audio_handler_test.dart`

**Interfaces:**
- Consumes `BassPlayer`, `Track`, and `PlaybackCoordinator`.
- Produces `PlayerAudioHandler` for notification, lock screen, headset, and audio-focus commands.

- [ ] **Step 1: Write a failing command-routing test**

```dart
test('media-service pause delegates to the coordinator', () async {
  final coordinator = FakeCoordinator();
  final handler = PlayerAudioHandler(coordinator);

  await handler.pause();

  expect(coordinator.pauseCalls, 1);
});
```

- [ ] **Step 2: Run the failing test**

Run: `flutter test test/application/player_audio_handler_test.dart`

Expected: FAIL because the handler is absent.

- [ ] **Step 3: Implement the audio handler**

```dart
class PlayerAudioHandler extends BaseAudioHandler {
  PlayerAudioHandler(this._coordinator);
  final PlaybackCoordinator _coordinator;

  @override
  Future<void> play() => _coordinator.resume();

  @override
  Future<void> pause() => _coordinator.pause();

  @override
  Future<void> skipToNext() => _coordinator.skipToNext();

  @override
  Future<void> skipToPrevious() => _coordinator.skipToPrevious();

  @override
  Future<void> seek(Duration position) => _coordinator.seek(position);
}
```

Configure `AudioSessionConfiguration.music()` during app startup. Publish a `MediaItem` whenever the current track changes and a `PlaybackState` whenever local BASS state changes. The coordinator routes system commands to BASS only in `local` mode; casting mode routes commands to the renderer.

- [ ] **Step 4: Run the tests**

Run:

```powershell
dart format lib/application lib/domain test/application
flutter analyze
flutter test test/application/player_audio_handler_test.dart test/domain/playback_coordinator_test.dart
```

Expected: all commands exit 0.

- [ ] **Step 5: Commit media integration**

```powershell
git add lib/application lib/domain test/application test/domain
git commit -m "feat: add Android media session controls"
```

### Task 6: Discover and Control DLNA Renderers

**Files:**
- Create: `lib/infrastructure/dlna/dlna_discovery.dart`
- Create: `lib/infrastructure/dlna/dlna_renderer_client.dart`
- Create: `test/infrastructure/dlna/dlna_renderer_client_test.dart`

**Interfaces:**
- Consumes `upnp_client` `Device`, `DlnaDevice`, `DlnaRenderer`, and current `Track`.
- Produces `DlnaDiscovery.scan()` and `DlnaRendererClient.load/play/pause/stop/seek/position`.

- [ ] **Step 1: Write failing discovery-filter and command-delegation tests**

```dart
test('discovery keeps only devices with AVTransport', () async {
  final discovery = DlnaDiscovery(FakeDeviceDiscoverer([rendererDevice, nonRendererDevice]));

  final devices = await discovery.scan();

  expect(devices.single.udn, rendererDevice.description!.udn);
});

test('load delegates the encoded URL to AVTransport', () async {
  final transport = FakeAvTransport();
  final client = DlnaRendererClient(transport: transport);

  await client.load(Uri.parse('http://192.168.1.7/session/a%20b.flac'), track);

  expect(transport.loadedUri, 'http://192.168.1.7/session/a%20b.flac');
});
```

- [ ] **Step 2: Run the failing test**

Run: `flutter test test/infrastructure/dlna/dlna_renderer_client_test.dart`

Expected: FAIL because the discovery adapter and renderer client are absent.

- [ ] **Step 3: Implement discovery and a narrow renderer client**

```dart
class DlnaDiscovery {
  DlnaDiscovery(this._discovererFactory);
  final DeviceDiscoverer Function() _discovererFactory;

  Future<List<DlnaDevice>> scan() async {
    final discoverer = _discovererFactory();
    await discoverer.start(addressTypes: [InternetAddressType.IPv4]);
    try {
      final devices = await discoverer.getDevices(
        searchTarget: 'urn:schemas-upnp-org:device:MediaRenderer:1',
      );
      return devices
          .where((device) => device.avTransportService() != null)
          .map(DlnaDevice.fromUpnp)
          .toList(growable: false);
    } finally {
      discoverer.dispose();
    }
  }
}

class DlnaRendererClient implements DlnaRenderer {
  DlnaRendererClient({required AvTransportService transport}) : _transport = transport;
  final AvTransportService _transport;

  @override
  Future<void> load(Uri source, Track track) => _transport.setAVTransportURI(source.toString());

  @override
  Future<void> play({Duration position = Duration.zero}) async {
    if (position > Duration.zero) {
      await _transport.seek(SeekMode.relTime, formatDlnaTime(position));
    }
    await _transport.play();
  }

  @override
  Future<void> stop() => _transport.stop();
}
```

`DlnaRendererClient` must call the package AVTransport methods for `SetAVTransportURI`, `Play`, `Pause`, `Stop`, `Seek`, and `GetPositionInfo`. It must use `formatDlnaTime` to supply `HH:MM:SS` seek targets, wrap package exceptions in `CastFailure` with device name and action, and treat missing sink protocol information as unknown rather than supported.

- [ ] **Step 4: Run protocol tests**

Run:

```powershell
dart format lib/infrastructure/dlna test/infrastructure/dlna
flutter analyze
flutter test test/infrastructure/dlna/dlna_renderer_client_test.dart
```

Expected: all commands exit 0.

- [ ] **Step 5: Commit DLNA control layer**

```powershell
git add lib/infrastructure/dlna test/infrastructure/dlna
git commit -m "feat: add DLNA renderer discovery and controls"
```

### Task 7: Serve One Local Media Item Securely over LAN HTTP

**Files:**
- Create: `lib/infrastructure/dlna/source_reader.dart`
- Create: `lib/infrastructure/dlna/local_media_server.dart`
- Create: `test/infrastructure/dlna/local_media_server_test.dart`

**Interfaces:**
- Consumes `Track` and a `SourceReader` capable of random-access reads.
- Produces `LocalMediaServer.start(track): Future<Uri>` and `LocalMediaServer.stop(): Future<void>`.

- [ ] **Step 1: Write failing range and isolation tests**

```dart
test('serves only the active token with a partial-content response', () async {
  final server = LocalMediaServer(reader: MemorySourceReader(bytes: List<int>.generate(10, (i) => i)));
  final uri = await server.start(track);

  final response = await get(uri, headers: {'Range': 'bytes=2-5'});

  expect(response.statusCode, HttpStatus.partialContent);
  expect(response.bodyBytes, [2, 3, 4, 5]);
  expect((await get(uri.replace(path: '/wrong'))).statusCode, HttpStatus.notFound);
});
```

- [ ] **Step 2: Run the failing server test**

Run: `flutter test test/infrastructure/dlna/local_media_server_test.dart`

Expected: FAIL because the server is absent.

- [ ] **Step 3: Implement source access and HTTP responses**

```dart
abstract interface class SourceReader {
  Future<int> length(Uri source);
  Stream<List<int>> read(Uri source, int start, int endInclusive);
}

class LocalMediaServer {
  LocalMediaServer({required SourceReader reader, Uuid? uuid}) : _reader = reader, _uuid = uuid ?? const Uuid();
  final SourceReader _reader;
  final Uuid _uuid;
  HttpServer? _server;
  late String _token;
  late Track _track;

  Future<Uri> start(Track track) async {
    _track = track;
    _token = _uuid.v4();
    _server = await HttpServer.bind(InternetAddress.anyIPv4, 0);
    unawaited(_server!.forEach(_handle));
    final address = await activeLanAddress();
    return Uri(scheme: 'http', host: address.address, port: _server!.port, path: '/media/$_token');
  }
}
```

`_handle` accepts only `HEAD` and `GET` on `/media/<token>`. It returns `404` for every other path, `405` for other methods, `416` for invalid ranges, and includes `Accept-Ranges`, `Content-Length`, `Content-Range`, and the original MP3/FLAC MIME type. `stop()` closes the `HttpServer`, clears the token, and makes all former URLs unreachable.

- [ ] **Step 4: Run test and static checks**

Run:

```powershell
dart format lib/infrastructure/dlna test/infrastructure/dlna
flutter analyze
flutter test test/infrastructure/dlna/local_media_server_test.dart
```

Expected: all commands exit 0.

- [ ] **Step 5: Commit the server**

```powershell
git add lib/infrastructure/dlna test/infrastructure/dlna
git commit -m "feat: serve tokenized DLNA media"
```

### Task 8: Connect the Coordinator to DLNA Sessions and Foreground Lifecycle

**Files:**
- Modify: `lib/domain/playback_coordinator.dart`
- Create: `lib/infrastructure/dlna/dlna_cast_session.dart`
- Modify: `lib/application/player_audio_handler.dart`
- Create: `test/infrastructure/dlna/dlna_cast_session_test.dart`

**Interfaces:**
- Consumes `LocalMediaServer`, `DlnaRendererClient`, `BassPlayer`, `Track`, and `DlnaDevice`.
- Produces `DlnaCastSession.start/stop/returnToLocal` and coordinator transitions that preserve queue position.

- [ ] **Step 1: Write a failing recovery test**

```dart
test('stops the server and enters castInterrupted on renderer failure', () async {
  final server = FakeMediaServer();
  final session = DlnaCastSession(server: server, renderer: ThrowingRenderer());

  await expectLater(session.start(track), throwsA(isA<CastFailure>()));

  expect(server.stopCalls, 1);
  expect(session.mode, PlaybackMode.castInterrupted);
});
```

- [ ] **Step 2: Run the failing session test**

Run: `flutter test test/infrastructure/dlna/dlna_cast_session_test.dart`

Expected: FAIL because the session is absent.

- [ ] **Step 3: Implement cast lifecycle ownership**

```dart
class DlnaCastSession {
  DlnaCastSession({required this.server, required this.renderer});
  final LocalMediaServer server;
  final DlnaRenderer renderer;
  PlaybackMode mode = PlaybackMode.idle;

  Future<void> start(Track track, {required Duration position}) async {
    mode = PlaybackMode.preparingCast;
    try {
      final source = await server.start(track);
      await renderer.load(source, track);
      await renderer.play(position: position);
      mode = PlaybackMode.casting;
    } catch (_) {
      await server.stop();
      mode = PlaybackMode.castInterrupted;
      rethrow;
    }
  }

  Future<void> stop() async {
    await renderer.stop();
    await server.stop();
    mode = PlaybackMode.idle;
  }
}
```

The coordinator must start the audio service before a cast session and keep its media notification updated during casting. It must poll renderer position at a bounded interval, update UI/media state without moving accessibility focus, and stop polling and the HTTP server on every terminal path.

- [ ] **Step 4: Run session, coordinator, and media-handler tests**

Run:

```powershell
flutter test test/domain/playback_coordinator_test.dart test/infrastructure/dlna/dlna_cast_session_test.dart test/application/player_audio_handler_test.dart
flutter analyze
```

Expected: all commands exit 0.

- [ ] **Step 5: Commit cast orchestration**

```powershell
git add lib/domain lib/infrastructure/dlna lib/application test
git commit -m "feat: orchestrate DLNA casting lifecycle"
```

### Task 9: Build the Usable, Semantic Flutter Interface

**Files:**
- Create: `lib/presentation/home_screen.dart`
- Create: `lib/presentation/now_playing_screen.dart`
- Create: `lib/presentation/cast_picker.dart`
- Create: `lib/presentation/player_controls.dart`
- Modify: `lib/app.dart`
- Create: `test/presentation/semantic_controls_test.dart`

**Interfaces:**
- Consumes `LibraryRepository`, `PlaybackCoordinator`, `DlnaDiscovery`, and app state streams.
- Produces an accessible local library, now-playing view, and explicit cast picker.

- [ ] **Step 1: Write semantic and focus tests**

```dart
testWidgets('cast picker labels devices and does not cast on selection', (tester) async {
  final coordinator = FakeCoordinator();
  await tester.pumpWidget(TestApp(coordinator: coordinator, devices: [device]));

  await tester.tap(find.bySemanticsLabel('投送到设备'));
  await tester.pumpAndSettle();
  expect(find.bySemanticsLabel('小米电视，DLNA 设备，可用'), findsOneWidget);

  await tester.tap(find.text('小米电视'));
  expect(coordinator.castCalls, 0);
  expect(find.bySemanticsLabel('开始投送到小米电视'), findsOneWidget);
});

testWidgets('progress exposes time and adjustment semantics', (tester) async {
  await tester.pumpWidget(TestApp(position: const Duration(seconds: 65), duration: const Duration(minutes: 3)));

  expect(find.bySemanticsLabel('播放进度，1 分 5 秒，共 3 分 0 秒'), findsOneWidget);
});
```

- [ ] **Step 2: Run the failing widget tests**

Run: `flutter test test/presentation/semantic_controls_test.dart`

Expected: FAIL because presentation widgets are absent.

- [ ] **Step 3: Implement native-semantic controls**

```dart
Semantics(
  label: isPlaying ? '暂停' : '播放',
  button: true,
  child: IconButton(
    tooltip: isPlaying ? '暂停' : '播放',
    onPressed: onTogglePlayback,
    icon: Icon(isPlaying ? Icons.pause : Icons.play_arrow),
  ),
)
```

```dart
Semantics(
  label: '播放进度，${formatDuration(position)}，共 ${formatDuration(duration)}',
  slider: true,
  value: '${position.inSeconds} 秒',
  increasedValue: '快进 5 秒',
  decreasedValue: '快退 5 秒',
  onIncrease: () => onSeek(position + const Duration(seconds: 5)),
  onDecrease: () => onSeek(position - const Duration(seconds: 5)),
  child: Slider(value: progress, onChanged: onSliderChanged),
)
```

Use a `Dialog` for the cast picker. Give it a title, a visible close command, and a `Focus` node so closing restores focus to the cast trigger. Device discovery starts when the dialog opens because the user explicitly invoked it. Selecting a device changes only selected state; a separate primary button calls `startCasting`. A library row contains a full track label and a normal activation path; any extra action is separately reachable and named.

- [ ] **Step 4: Run widget and analyzer checks**

Run:

```powershell
dart format lib/presentation test/presentation lib/app.dart
flutter analyze
flutter test test/presentation/semantic_controls_test.dart
```

Expected: all commands exit 0.

- [ ] **Step 5: Commit interface work**

```powershell
git add lib/presentation lib/app.dart test/presentation
git commit -m "feat: add accessible player and cast UI"
```

### Task 10: Verify on Android, Package the First Release, and Document Use

**Files:**
- Create: `README.md`
- Create: `docs/release/first-release-checklist.md`
- Modify: `android/app/build.gradle.kts` or `android/app/build.gradle`

**Interfaces:**
- Produces `build/app/outputs/flutter-apk/app-release.apk` and an auditable manual acceptance record.

- [ ] **Step 1: Add the release checklist**

```markdown
# First Release Checklist

- [ ] Install the release APK on a physical Android device.
- [ ] Grant music permission and verify a non-empty local library.
- [ ] Play, pause, seek, skip, repeat, shuffle, background, notification, and headset controls.
- [ ] With TalkBack enabled, verify library rows, controls, progress adjustments, cast picker focus return, start/stop casting, error recovery, large text, and D-pad navigation.
- [ ] With Xiaomi TV on the same Wi-Fi, cast MP3 320 kbps, CD-quality FLAC, and 24-bit/96 kHz FLAC when supported.
- [ ] Verify no unrelated URL is served before, during, or after a cast session.
- [ ] Disconnect Wi-Fi during casting; verify local audio does not auto-start and the recovery action works.
```

- [ ] **Step 2: Run all automated checks**

Run:

```powershell
dart format --set-exit-if-changed lib test
flutter analyze
flutter test
flutter build apk --release
```

Expected: all commands exit 0 and `build/app/outputs/flutter-apk/app-release.apk` exists.

- [ ] **Step 3: Perform physical-device acceptance**

Install:

```powershell
adb install -r build/app/outputs/flutter-apk/app-release.apk
```

Record each checklist result and any device-specific compatibility limit in `docs/release/first-release-checklist.md`. Do not label the release complete until every required local-playback and Xiaomi-TV case is verified or explicitly documented as blocked by the receiver.

- [ ] **Step 4: Write concise release instructions**

`README.md` must state the Android/Flutter prerequisites, BASS non-commercial licensing condition, source formats, how to build the APK, how cast selection works, and the fact that DLNA compatibility depends on the receiver.

- [ ] **Step 5: Commit release artifacts**

```powershell
git add README.md docs/release android/app
git commit -m "docs: prepare first release"
git status --short
```

Expected: the output contains no modified or untracked application/release files. Do not add the user-provided accessibility baseline unless explicitly requested.

## Plan Self-Review

### Spec Coverage

- Flutter Android app, BASS local engine, MediaStore library, background system controls, and local queue state are covered by Tasks 1-5.
- Explicit DLNA discovery, AVTransport control, tokenized byte-range local serving, direct MP3/FLAC source casting, no-transcode policy, and recovery transitions are covered by Tasks 6-8.
- Xiaomi TV, user-selected device start, focus restoration, semantic labels/actions, no auto-play, media progress controls, and baseline-driven accessibility are covered by Task 9 and Task 10 manual acceptance.
- Wi-Fi, permission, source-read, unsupported format, renderer failure, and disconnection behavior are covered by Tasks 3, 6-8, and the release checklist.
- BASS license notice and non-commercial constraint are covered by Tasks 1, 4, and 10.

### Placeholder Scan

The implementation tasks contain no deferred work markers and name all planned files, interfaces, commands, expected checks, and commit boundaries.

### Type Consistency

`Track`, `PlaybackMode`, `BassPlayer`, `DlnaRenderer`, `PlaybackCoordinator`, `LocalMediaServer`, and `DlnaCastSession` are introduced before later tasks consume them. Position values are `Duration` in Dart and integer milliseconds only across the BASS method channel.
