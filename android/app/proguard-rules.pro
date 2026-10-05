# Keep rules belong to native Android dependencies.

# FFmpeg 兜底播放器：C 通过 GetStaticMethodID 按名调用 nativeOnComplete/nativeOnError，
# 且 external fun 为 native 方法，R8 混淆会误删/改名，必须整类保留。
-keep class com.example.local_music_player.FfmpegPlayerJni { *; }
