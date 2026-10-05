/*
 * libffmpeg_player：基于 FFmpeg 的迷你视频播放器（JNI），作为 ExoPlayer 的旧格式兜底引擎。
 * 能力：avformat 拆容器（文件/URL/http）→ avcodec 软解 → swscale 经 ANativeWindow 渲染 → swr 转 PCM 写 AudioTrack。
 * 许可：FFmpeg 9.0.1（LGPL 精简构建），本文件为 Apache-2.0。
 * 说明：AudioTrack 非阻塞短写支持取消和暂停；播放头作为音频时钟，缓冲区按会话复用。
 */
#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdatomic.h>

#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/error.h>
#include <libavutil/channel_layout.h>
#include <libavutil/imgutils.h>
#include <libavutil/time.h>
#include <libswscale/swscale.h>
#include <libswresample/swresample.h>

#define LOG_TAG "FfmpegPlayer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM *g_vm = NULL;
static jclass g_fp_class = NULL;
static jmethodID g_track_write, g_track_head, g_track_pause, g_track_flush, g_track_play;

typedef struct Player {
    AVFormatContext *fmt;
    int video_idx;
    int audio_idx;
    AVCodecContext *video_ctx;
    AVCodecContext *audio_ctx;
    int output_sample_rate;
    struct SwsContext *sws;
    SwrContext *swr;

    ANativeWindow *window;
    int window_w;
    int window_h;
    jobject audio_track;   /* 全局引用，可空 */
    uint8_t *pcm;
    unsigned int pcm_capacity;
    jbyteArray pcm_array;
    int pcm_array_capacity;
    uint64_t written_frames;
    uint32_t last_head;
    uint64_t head_wrap;
    int64_t audio_base_ms;
    int64_t discard_before_ms;
    int64_t open_deadline_us;

    /* ?? */
    pthread_t thread;
    pthread_mutex_t lock;
    pthread_cond_t cond;
    _Atomic int started;
    _Atomic int playing;
    _Atomic int stop;
    _Atomic int ended;
    _Atomic int seek_req;
    _Atomic int video_enabled;
    int64_t seek_ms;

    /* ?? */
    _Atomic int64_t audio_clock_ms;
    _Atomic int64_t video_clock_ms;
    int64_t video_start_wall_ms;
    int64_t video_first_pts_ms;
    int64_t duration_ms;

    char error[256];
} Player;

static void set_error(Player *p, const char *msg) {
    snprintf(p->error, sizeof(p->error), "%s", msg);
}

static int64_t ms_from_pts(AVRational tb, int64_t pts) {
    if (pts == AV_NOPTS_VALUE) return -1;
    return av_rescale_q(pts, tb, (AVRational){1, 1000});
}

static int ffmpeg_interrupt_cb(void *opaque) {
    Player *p = (Player *)opaque;
    return p && (p->stop || (p->open_deadline_us > 0 &&
                            av_gettime_relative() >= p->open_deadline_us));
}

/* 句柄化回调：Java 侧经 register(handle, callbacks) 分发，支持并发实例。 */
static jlong player_handle(const Player *p) {
    return (jlong)(intptr_t)p;
}

static void clear_java_exception(JNIEnv *env, const char *where);

static void notify_complete(JNIEnv *env, jlong handle) {
    if (!g_fp_class) return;
    jmethodID cb = (*env)->GetStaticMethodID(env, g_fp_class, "nativeOnComplete", "(J)V");
    if (cb) {
        (*env)->CallStaticVoidMethod(env, g_fp_class, cb, handle);
        clear_java_exception(env, "nativeOnComplete");
    }
}

static void clear_java_exception(JNIEnv *env, const char *where) {
    if (env && (*env)->ExceptionCheck(env)) {
        LOGE("Java exception from %s", where);
        (*env)->ExceptionClear(env);
    }
}

static void notify_diagnostic(JNIEnv *env, jlong handle, const char *msg) {
    if (!g_fp_class || !env || !msg) return;
    jmethodID cb = (*env)->GetStaticMethodID(env, g_fp_class, "nativeOnDiagnostic", "(JLjava/lang/String;)V");
    if (!cb) return;
    jstring jmsg = (*env)->NewStringUTF(env, msg);
    if (jmsg) {
        (*env)->CallStaticVoidMethod(env, g_fp_class, cb, handle, jmsg);
        (*env)->DeleteLocalRef(env, jmsg);
        clear_java_exception(env, "nativeOnDiagnostic");
    }
}

static void notify_error(JNIEnv *env, jlong handle, const char *msg) {
    if (!g_fp_class) return;
    jmethodID cb = (*env)->GetStaticMethodID(env, g_fp_class, "nativeOnError", "(JLjava/lang/String;)V");
    if (cb) {
        jstring jmsg = (*env)->NewStringUTF(env, msg);
        (*env)->CallStaticVoidMethod(env, g_fp_class, cb, handle, jmsg);
        (*env)->DeleteLocalRef(env, jmsg);
        clear_java_exception(env, "nativeOnError");
    }
}

static ANativeWindow *player_window_ref(Player *p) {
    ANativeWindow *window = NULL;
    pthread_mutex_lock(&p->lock);
    if (p->window) {
        window = p->window;
        ANativeWindow_acquire(window);
    }
    pthread_mutex_unlock(&p->lock);
    return window;
}

static jobject player_audio_track_ref(Player *p, JNIEnv *env) {
    jobject track = NULL;
    pthread_mutex_lock(&p->lock);
    if (p->audio_track) track = (*env)->NewLocalRef(env, p->audio_track);
    pthread_mutex_unlock(&p->lock);
    return track;
}

/* 解码线程内调用，暂停时睡眠；seek/stop 必须能中断写入和排空。 */
static int await_output(Player *p) {
    pthread_mutex_lock(&p->lock);
    while (!p->playing && !p->stop && !p->seek_req)
        pthread_cond_wait(&p->cond, &p->lock);
    int ready = !p->stop && !p->seek_req;
    pthread_mutex_unlock(&p->lock);
    return ready;
}

static uint64_t audio_head(Player *p, JNIEnv *env, jobject track) {
    uint32_t head = (uint32_t)(*env)->CallIntMethod(env, track, g_track_head);
    clear_java_exception(env, "AudioTrack.getPlaybackHeadPosition");
    if (head < p->last_head) p->head_wrap += UINT64_C(1) << 32;
    p->last_head = head;
    uint64_t frames = p->head_wrap + head;
    p->audio_clock_ms = p->audio_base_ms + av_rescale(frames, 1000, p->output_sample_rate);
    return frames;
}

/* ---------- 渲染与音频输出 ---------- */
static void render_video(Player *p, AVFrame *frame, int64_t pts_ms, JNIEnv *env) {
    (void)env;
    if (!atomic_load(&p->video_enabled)) return;
    if (!frame || frame->width <= 0 || frame->height <= 0) {
        return;
    }
    if (!await_output(p) || (pts_ms >= 0 && pts_ms < p->discard_before_ms)) return;
    ANativeWindow *window = player_window_ref(p);
    if (!window) return;
    if (!p->sws || p->window_w != frame->width || p->window_h != frame->height) {
        if (p->sws) sws_freeContext(p->sws);
        p->sws = sws_getContext(frame->width, frame->height, frame->format,
                                frame->width, frame->height, AV_PIX_FMT_RGBA,
                                SWS_BILINEAR, NULL, NULL, NULL);
        p->window_w = frame->width;
        p->window_h = frame->height;
        ANativeWindow_setBuffersGeometry(window, frame->width, frame->height, WINDOW_FORMAT_RGBA_8888);
        if (!p->sws) { ANativeWindow_release(window); return; }
    }
    int64_t master = (p->audio_idx >= 0) ? p->audio_clock_ms : p->video_clock_ms;
    if (pts_ms >= 0) {
        if (p->audio_idx >= 0) {
            int64_t ahead = pts_ms - master;
            /* 单线程交错解码，等待必须有界，否则缺音频包时无法继续 demux。 */
            if (ahead > 50) {
                int64_t wait = ahead - 50;
                if (wait > 200) wait = 200;
                while (wait > 0 && await_output(p)) { usleep(5000); wait -= 5; }
            }
        } else {
            if (p->video_clock_ms < 0) {
                p->video_first_pts_ms = pts_ms;
                p->video_start_wall_ms = av_gettime() / 1000;
            }
            p->video_clock_ms = pts_ms;
            int64_t target = pts_ms - p->video_first_pts_ms;
            int64_t elapsed = av_gettime() / 1000 - p->video_start_wall_ms;
            int64_t wait = target - elapsed;
            if (wait > 5) usleep((useconds_t)(wait < 2000 ? wait : 2000) * 1000);
        }
    }
    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(window, &buf, NULL) == 0) {
        uint8_t *dst[4] = {(uint8_t *)buf.bits, NULL, NULL, NULL};
        int dst_linesize[4] = {buf.stride * 4, 0, 0, 0};
        sws_scale(p->sws, (const uint8_t *const *)frame->data, frame->linesize, 0,
                  frame->height, dst, dst_linesize);
        ANativeWindow_unlockAndPost(window);
    }
    ANativeWindow_release(window);
}

static void write_audio(Player *p, AVFrame *frame, JNIEnv *env) {
    if (!env) return;
    jobject track = player_audio_track_ref(p, env);
    if (!track) return;
    if (!p->swr) {
        AVChannelLayout out_layout = AV_CHANNEL_LAYOUT_STEREO;
        int r = swr_alloc_set_opts2(&p->swr, &out_layout, AV_SAMPLE_FMT_S16,
                                    p->output_sample_rate,
                                    &p->audio_ctx->ch_layout, p->audio_ctx->sample_fmt,
                                    p->audio_ctx->sample_rate, 0, NULL);
        if (r < 0 || swr_init(p->swr) < 0) {
            set_error(p, "音频解码初始化失败");
            (*env)->DeleteLocalRef(env, track);
            return;
        }
    }
    int out_samples = swr_get_out_samples(p->swr, frame ? frame->nb_samples : 0);
    if (out_samples <= 0) { (*env)->DeleteLocalRef(env, track); return; }
    int out_size = out_samples * 2 * 2;   /* 恒定立体声 16bit */
    av_fast_malloc(&p->pcm, &p->pcm_capacity, out_size);
    if (!p->pcm) { set_error(p, "音频缓冲分配失败"); (*env)->DeleteLocalRef(env, track); return; }
    uint8_t *outp = p->pcm;
    int got = swr_convert(p->swr, &outp, out_samples,
                          frame ? (const uint8_t **)frame->extended_data : NULL,
                          frame ? frame->nb_samples : 0);
    if (got <= 0) { (*env)->DeleteLocalRef(env, track); return; }
    int bytes = got * 2 * 2;
    int offset = 0;
    if (frame && p->discard_before_ms > 0) {
        int64_t pts = ms_from_pts(p->fmt->streams[p->audio_idx]->time_base, frame->best_effort_timestamp);
        if (pts >= 0 && pts < p->discard_before_ms) {
            int64_t skip = av_rescale(p->discard_before_ms - pts, p->output_sample_rate, 1000);
            offset = (int)(skip < got ? skip : got) * 4;
        }
    }
    if (bytes > p->pcm_array_capacity) {
        if (p->pcm_array) (*env)->DeleteGlobalRef(env, p->pcm_array);
        jbyteArray local = (*env)->NewByteArray(env, bytes);
        p->pcm_array = local ? (*env)->NewGlobalRef(env, local) : NULL;
        if (local) (*env)->DeleteLocalRef(env, local);
        p->pcm_array_capacity = p->pcm_array ? bytes : 0;
    }
    if (!p->pcm_array) {
        clear_java_exception(env, "音频缓冲");
        set_error(p, "音频缓冲分配失败");
        (*env)->DeleteLocalRef(env, track);
        return;
    }
    (*env)->SetByteArrayRegion(env, p->pcm_array, 0, bytes, (const jbyte *)p->pcm);
    while (offset < bytes && await_output(p)) {
        int written = (*env)->CallIntMethod(env, track, g_track_write, p->pcm_array,
                                            offset, bytes - offset, 1 /* WRITE_NON_BLOCKING */);
        if ((*env)->ExceptionCheck(env) || written < 0) {
            clear_java_exception(env, "AudioTrack.write");
            set_error(p, "音频输出失败");
            break;
        }
        offset += written;
        p->written_frames += written / 4;
        audio_head(p, env, track);
        if (written == 0) usleep(5000);
    }
    (*env)->DeleteLocalRef(env, track);
}

/* ---------- 解码线程 ---------- */
static void do_seek(Player *p, JNIEnv *env, int64_t ms) {
    int stream = p->video_idx >= 0 ? p->video_idx : p->audio_idx;
    if (stream >= 0) {
        AVRational tb = p->fmt->streams[stream]->time_base;
        int64_t ts = av_rescale_q(ms * 1000, (AVRational){1, 1000000}, tb);
        av_seek_frame(p->fmt, stream, ts, AVSEEK_FLAG_BACKWARD);
    } else {
        av_seek_frame(p->fmt, -1, ms * AV_TIME_BASE / 1000, AVSEEK_FLAG_BACKWARD);
    }
    if (p->video_ctx) avcodec_flush_buffers(p->video_ctx);
    if (p->audio_ctx) avcodec_flush_buffers(p->audio_ctx);
    if (p->swr) swr_free(&p->swr);
    p->written_frames = p->last_head = p->head_wrap = 0;
    p->audio_base_ms = p->discard_before_ms = ms;
    p->audio_clock_ms = ms;
    p->video_clock_ms = -1;
    pthread_mutex_lock(&p->lock);
    jobject track = p->audio_track;
    if (track) {
        (*env)->CallVoidMethod(env, track, g_track_pause);
        (*env)->CallVoidMethod(env, track, g_track_flush);
        if (p->playing && !p->stop) (*env)->CallVoidMethod(env, track, g_track_play);
        clear_java_exception(env, "AudioTrack.seek");
    }
    pthread_mutex_unlock(&p->lock);
}

static void *decode_loop(void *arg) {
    Player *p = (Player *)arg;
    JNIEnv *env = NULL;
    (*g_vm)->AttachCurrentThread(g_vm, &env, NULL);
    AVPacket *pkt = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    int ended = 0;
    const char *err_msg = NULL;

play_loop:
    ended = 0;
    while (!p->stop) {
        pthread_mutex_lock(&p->lock);
        while (!p->playing && !p->stop) pthread_cond_wait(&p->cond, &p->lock);
        int seek_flag = p->seek_req;
        int64_t seek_ms = p->seek_ms;
        p->seek_req = 0;
        pthread_mutex_unlock(&p->lock);
        if (p->stop) break;
        if (seek_flag) do_seek(p, env, seek_ms);

        int r = av_read_frame(p->fmt, pkt);
        if (r == AVERROR_EOF) { ended = 1; break; }
        if (r < 0) { err_msg = "媒体读取失败，文件可能已损坏或网络连接中断。"; break; }

        if (pkt->stream_index == p->video_idx && p->video_ctx && atomic_load(&p->video_enabled)) {
            if (avcodec_send_packet(p->video_ctx, pkt) == 0) {
                while (avcodec_receive_frame(p->video_ctx, frame) == 0) {
                    int64_t pts = ms_from_pts(p->fmt->streams[p->video_idx]->time_base, frame->pts);
                    render_video(p, frame, pts, env);
                    av_frame_unref(frame);
                }
            }
        } else if (pkt->stream_index == p->audio_idx && p->audio_ctx) {
            if (avcodec_send_packet(p->audio_ctx, pkt) == 0) {
                while (avcodec_receive_frame(p->audio_ctx, frame) == 0) {
                    write_audio(p, frame, env);
                    av_frame_unref(frame);
                }
            }
        }
        av_packet_unref(pkt);
        if (p->error[0]) { err_msg = p->error; break; }
    }

    if (ended && !p->stop) {
/* 冲刷视频解码器剩余帧 */
        if (p->video_ctx && atomic_load(&p->video_enabled)) {
            avcodec_send_packet(p->video_ctx, NULL);
            while (avcodec_receive_frame(p->video_ctx, frame) == 0) {
                int64_t pts = ms_from_pts(p->fmt->streams[p->video_idx]->time_base, frame->pts);
                render_video(p, frame, pts, env);
                av_frame_unref(frame);
            }
        }
        if (p->audio_ctx) {
            avcodec_send_packet(p->audio_ctx, NULL);
            while (!p->stop && !p->seek_req && avcodec_receive_frame(p->audio_ctx, frame) == 0) {
                write_audio(p, frame, env);
                av_frame_unref(frame);
            }
            if (p->swr && !p->stop && !p->seek_req) write_audio(p, NULL, env);
            jobject track = player_audio_track_ref(p, env);
            if (track) {
                while (await_output(p) && audio_head(p, env, track) < p->written_frames) usleep(5000);
                (*env)->DeleteLocalRef(env, track);
            }
        }
        if (p->error[0]) { err_msg = p->error; ended = 0; }
    }

    if (p->seek_req && !p->stop && !err_msg) goto play_loop;

    if (ended && !p->stop && !err_msg) {
        p->ended = 1;
        notify_complete(env, player_handle(p));
        pthread_mutex_lock(&p->lock);
        while (!p->stop && !p->seek_req) pthread_cond_wait(&p->cond, &p->lock);
        pthread_mutex_unlock(&p->lock);
        if (!p->stop) { p->ended = 0; goto play_loop; }
    }

    pthread_mutex_lock(&p->lock);
    p->ended = ended ? 1 : 0;
    pthread_mutex_unlock(&p->lock);

    av_frame_free(&frame);
    av_packet_free(&pkt);

    if (!p->stop && env) {
        if (err_msg) {
            notify_diagnostic(env, player_handle(p), err_msg);
            notify_error(env, player_handle(p), err_msg);
        }
    }
    (*g_vm)->DetachCurrentThread(g_vm);
    return NULL;
}

/* ---------- ?? ---------- */
static Player *player_create(void) {
    Player *p = (Player *)calloc(1, sizeof(Player));
    if (!p) return NULL;
    p->video_idx = -1;
    p->audio_idx = -1;
    atomic_store(&p->video_enabled, 1);
    p->video_clock_ms = -1;
    pthread_mutex_init(&p->lock, NULL);
    pthread_cond_init(&p->cond, NULL);
    return p;
}

static void player_close(Player *p, JNIEnv *env) {
    if (!p) return;
    pthread_mutex_lock(&p->lock);
    p->stop = 1;
    pthread_cond_signal(&p->cond);
    pthread_mutex_unlock(&p->lock);
    if (p->started) { pthread_join(p->thread, NULL); p->started = 0; }
    if (p->window) { ANativeWindow_release(p->window); p->window = NULL; }
    if (p->audio_track && env) { (*env)->DeleteGlobalRef(env, p->audio_track); p->audio_track = NULL; }
    if (p->pcm_array && env) (*env)->DeleteGlobalRef(env, p->pcm_array);
    av_freep(&p->pcm);
    if (p->swr) swr_free(&p->swr);
    if (p->sws) sws_freeContext(p->sws);
    if (p->audio_ctx) avcodec_free_context(&p->audio_ctx);
    if (p->video_ctx) avcodec_free_context(&p->video_ctx);
    if (p->fmt) avformat_close_input(&p->fmt);
    pthread_mutex_destroy(&p->lock);
    pthread_cond_destroy(&p->cond);
    free(p);
}

static void log_stream_open_fail(AVCodecParameters *par, const char *kind,
                                 int params_rc, int open_rc) {
    char eb[AV_ERROR_MAX_STRING_SIZE] = {0};
    int rc = params_rc < 0 ? params_rc : open_rc;
    av_strerror(rc, eb, sizeof(eb));
    LOGE("ffp %s stream open failed: codec=%s(%d) params=%d open=%d err=%s",
         kind, avcodec_get_name(par->codec_id), par->codec_id,
         params_rc, open_rc, eb);
}

static int open_streams(Player *p, int audio_only) {
    int i;
    for (i = 0; i < (int)p->fmt->nb_streams; i++) {
        AVStream *st = p->fmt->streams[i];
        if (st->codecpar->codec_type == AVMEDIA_TYPE_VIDEO && p->video_idx < 0 &&
            !audio_only && !(st->disposition & AV_DISPOSITION_ATTACHED_PIC)) {
            const AVCodec *dec = avcodec_find_decoder(st->codecpar->codec_id);
            if (!dec) {
                LOGE("ffp no decoder in build for video codec=%s(%d)",
                     avcodec_get_name(st->codecpar->codec_id), st->codecpar->codec_id);
                continue;
            }
            AVCodecContext *c = avcodec_alloc_context3(dec);
            int pr = c ? avcodec_parameters_to_context(c, st->codecpar) : -1;
            int orc = pr >= 0 ? avcodec_open2(c, dec, NULL) : -1;
            if (pr >= 0 && orc >= 0) {
                p->video_ctx = c;
                p->video_idx = i;
                LOGI("ffp video stream %d opened: %s %dx%d", i,
                     avcodec_get_name(st->codecpar->codec_id),
                     st->codecpar->width, st->codecpar->height);
            } else {
                log_stream_open_fail(st->codecpar, "video", pr, orc);
                if (c) avcodec_free_context(&c);
            }
        } else if (st->codecpar->codec_type == AVMEDIA_TYPE_AUDIO && p->audio_idx < 0) {
            const AVCodec *dec = avcodec_find_decoder(st->codecpar->codec_id);
            if (!dec) {
                LOGE("ffp no decoder in build for audio codec=%s(%d)",
                     avcodec_get_name(st->codecpar->codec_id), st->codecpar->codec_id);
                continue;
            }
            AVCodecContext *c = avcodec_alloc_context3(dec);
            int pr = c ? avcodec_parameters_to_context(c, st->codecpar) : -1;
            int orc = pr >= 0 ? avcodec_open2(c, dec, NULL) : -1;
            if (pr >= 0 && orc >= 0) {
                p->audio_ctx = c;
                /* DSD 解码可输出 352.8kHz PCM，超出 Android AudioTrack 采样率上限。 */
                p->output_sample_rate = c->sample_rate > 192000 ? 48000 : c->sample_rate;
                p->audio_idx = i;
                LOGI("ffp audio stream %d opened: %s %dch %dHz", i,
                     avcodec_get_name(st->codecpar->codec_id),
                     st->codecpar->ch_layout.nb_channels, st->codecpar->sample_rate);
            } else {
                log_stream_open_fail(st->codecpar, "audio", pr, orc);
                if (c) avcodec_free_context(&c);
            }
        }
    }
    if (p->video_idx < 0 && p->audio_idx < 0) return -1;
    if (p->fmt->duration != AV_NOPTS_VALUE && p->fmt->duration > 0)
        p->duration_ms = p->fmt->duration / 1000;
    return 0;
}

/* ---------- JNI ---------- */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_vm = vm;
    avformat_network_init();
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK && env) {
        jclass local = (*env)->FindClass(env, "com/example/local_music_player/FfmpegPlayerJni");
        if (local) {
            g_fp_class = (jclass)(*env)->NewGlobalRef(env, local);
            (*env)->DeleteLocalRef(env, local);
        }
        jclass track = (*env)->FindClass(env, "android/media/AudioTrack");
        if (!track) return JNI_ERR;
        g_track_write = (*env)->GetMethodID(env, track, "write", "([BIII)I");
        g_track_head = (*env)->GetMethodID(env, track, "getPlaybackHeadPosition", "()I");
        g_track_pause = (*env)->GetMethodID(env, track, "pause", "()V");
        g_track_flush = (*env)->GetMethodID(env, track, "flush", "()V");
        g_track_play = (*env)->GetMethodID(env, track, "play", "()V");
        (*env)->DeleteLocalRef(env, track);
        if ((*env)->ExceptionCheck(env)) return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_com_example_local_1music_1player_FfmpegPlayerJni_nativeCreate(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return (jlong)(intptr_t)player_create();
}

/* https 校验用的 CA 文件路径（App 侧从 assets 拷贝到 cacheDir 后注入）。
 * ffmpeg 的 tls_mbedtls 在 verify 开启且未配 ca_file 时，握手成功后
 * get_verify_result 必失败并以 EIO 结束 open——所有 https 源因此不可用。 */
static char g_ca_path[512] = {0};

JNIEXPORT void JNICALL
Java_com_example_local_1music_1player_FfmpegPlayerJni_nativeSetCaFile(JNIEnv *env, jobject thiz,
                                                                      jstring path) {
    (void)thiz;
    const char *cpath = path ? (*env)->GetStringUTFChars(env, path, NULL) : NULL;
    if (!cpath) {
        g_ca_path[0] = 0;
        return;
    }
    strncpy(g_ca_path, cpath, sizeof(g_ca_path) - 1);
    g_ca_path[sizeof(g_ca_path) - 1] = 0;
    (*env)->ReleaseStringUTFChars(env, path, cpath);
}

 JNIEXPORT jboolean JNICALL
Java_com_example_local_1music_1player_FfmpegPlayerJni_nativeOpen(JNIEnv *env, jobject thiz, jlong h,
                                                             jstring path, jboolean audio_only) {
    (void)thiz;
    Player *p = (Player *)(intptr_t)h;
    if (!p) return 0;
    p->open_deadline_us = av_gettime_relative() + 15000000;
    const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (!cpath) return JNI_FALSE;
    p->fmt = avformat_alloc_context();
    if (!p->fmt) {
        (*env)->ReleaseStringUTFChars(env, path, cpath);
        return 0;
    }
    p->fmt->interrupt_callback.callback = ffmpeg_interrupt_cb;
    p->fmt->interrupt_callback.opaque = p;
    AVDictionary *options = NULL;
    av_dict_set(&options, "rw_timeout", "15000000", 0);
    av_dict_set(&options, "timeout", "15000000", 0);
    av_dict_set(&options, "probesize", "2097152", 0);
    av_dict_set(&options, "analyzeduration", "5000000", 0);
    if (g_ca_path[0]) av_dict_set(&options, "ca_file", g_ca_path, 0);
    int ret = avformat_open_input(&p->fmt, cpath, NULL, &options);
    av_dict_free(&options);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    if (ret < 0) {
        char errbuf[128];
        av_strerror(ret, errbuf, sizeof(errbuf));
        LOGE("nativeOpen avformat_open_input failed (%d): %s", ret, errbuf);
        set_error(p, "cannot open video");
        return 0;
    }
    if (avformat_find_stream_info(p->fmt, NULL) < 0) {
        LOGE("nativeOpen find_stream_info failed");
        set_error(p, "cannot parse video");
        return 0;
    }
    if (open_streams(p, audio_only) < 0) {
        LOGE("nativeOpen no decodable stream");
        set_error(p, "cannot decode video");
        return 0;
    }
    atomic_store(&p->video_enabled, audio_only ? 0 : 1);
    p->open_deadline_us = 0;
    LOGI("nativeOpen OK streams=%d v=%d a=%d dur=%lld", (int)p->fmt->nb_streams, p->video_idx, p->audio_idx, (long long)p->duration_ms);
    return p->stop ? JNI_FALSE : JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_local_1music_1player_FfmpegPlayerJni_nativeHasVideoTrack(JNIEnv *env, jobject thiz, jlong h) {
    (void)env; (void)thiz;
    Player *p = (Player *)(intptr_t)h;
    return (p && p->video_idx >= 0) ? JNI_TRUE : JNI_FALSE;
}


JNIEXPORT jboolean JNICALL
Java_com_example_local_1music_1player_FfmpegPlayerJni_nativeSetSurface(JNIEnv *env, jobject thiz, jlong h, jobject surface) {
    (void)thiz;
    Player *p = (Player *)(intptr_t)h;
    if (!p) return JNI_FALSE;
    pthread_mutex_lock(&p->lock);
    if (p->window) { ANativeWindow_release(p->window); p->window = NULL; }
    if (surface) p->window = ANativeWindow_fromSurface(env, surface);
    int ready = p->window != NULL;
    pthread_mutex_unlock(&p->lock);
    pthread_mutex_lock(&p->lock);
    pthread_cond_signal(&p->cond);
    pthread_mutex_unlock(&p->lock);
    notify_diagnostic(env, player_handle(p), ready ? "Surface 已绑定" : "Surface 已清除");
    return ready ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_local_1music_1player_FfmpegPlayerJni_nativeSetVideoEnabled(JNIEnv *env, jobject thiz, jlong h, jboolean enabled) {
    (void)env; (void)thiz;
    Player *p = (Player *)(intptr_t)h;
    if (!p) return JNI_FALSE;
    atomic_store(&p->video_enabled, enabled ? 1 : 0);
    pthread_mutex_lock(&p->lock);
    pthread_cond_signal(&p->cond);
    pthread_mutex_unlock(&p->lock);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_local_1music_1player_FfmpegPlayerJni_nativeSetAudioTrack(JNIEnv *env, jobject thiz, jlong h, jobject track) {
    (void)thiz;
    Player *p = (Player *)(intptr_t)h;
    if (!p) return JNI_FALSE;
    jobject next = track ? (*env)->NewGlobalRef(env, track) : NULL;
    pthread_mutex_lock(&p->lock);
    if (p->audio_track) { (*env)->DeleteGlobalRef(env, p->audio_track); p->audio_track = NULL; }
    p->audio_track = next;
    pthread_mutex_unlock(&p->lock);
    notify_diagnostic(env, player_handle(p), next ? "AudioTrack 已绑定" : "AudioTrack 已清除");
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_example_local_1music_1player_FfmpegPlayerJni_nativeAudioSampleRate(JNIEnv *env, jobject thiz, jlong h) {
    (void)env; (void)thiz;
    Player *p = (Player *)(intptr_t)h;
    return p ? p->output_sample_rate : 0;
}

JNIEXPORT jint JNICALL
Java_com_example_local_1music_1player_FfmpegPlayerJni_nativeAudioChannels(JNIEnv *env, jobject thiz, jlong h) {
    (void)env; (void)thiz;
    Player *p = (Player *)(intptr_t)h;
    return (p && p->audio_ctx) ? (jint)p->audio_ctx->ch_layout.nb_channels : 0;
}

JNIEXPORT void JNICALL
Java_com_example_local_1music_1player_FfmpegPlayerJni_nativeStart(JNIEnv *env, jobject thiz, jlong h, jboolean play_when_ready) {
    (void)env; (void)thiz;
    Player *p = (Player *)(intptr_t)h;
    if (!p) return;
    pthread_mutex_lock(&p->lock);
    if (p->started) { pthread_mutex_unlock(&p->lock); return; }
    if (pthread_create(&p->thread, NULL, decode_loop, p) != 0) {
        pthread_mutex_unlock(&p->lock);
        notify_error(env, player_handle(p), "外部解码线程启动失败");
        return;
    }
    p->started = 1;
    p->playing = play_when_ready;
    pthread_mutex_unlock(&p->lock);
    notify_diagnostic(env, player_handle(p), "原生解码线程已启动");
}

JNIEXPORT void JNICALL
Java_com_example_local_1music_1player_FfmpegPlayerJni_nativePause(JNIEnv *env, jobject thiz, jlong h) {
    (void)env; (void)thiz;
    Player *p = (Player *)(intptr_t)h;
    if (!p) return;
    pthread_mutex_lock(&p->lock);
    p->playing = 0;
    pthread_cond_signal(&p->cond);
    pthread_mutex_unlock(&p->lock);
}

JNIEXPORT void JNICALL
Java_com_example_local_1music_1player_FfmpegPlayerJni_nativeResume(JNIEnv *env, jobject thiz, jlong h) {
    (void)env; (void)thiz;
    Player *p = (Player *)(intptr_t)h;
    if (!p) return;
    pthread_mutex_lock(&p->lock);
    if (p->ended && !p->seek_req) { p->seek_ms = 0; p->seek_req = 1; }
    p->playing = 1;
    pthread_cond_signal(&p->cond);
    pthread_mutex_unlock(&p->lock);
}

JNIEXPORT void JNICALL
Java_com_example_local_1music_1player_FfmpegPlayerJni_nativeSeek(JNIEnv *env, jobject thiz, jlong h, jlong ms) {
    (void)env; (void)thiz;
    Player *p = (Player *)(intptr_t)h;
    if (!p) return;
    pthread_mutex_lock(&p->lock);
    p->seek_req = 1;
    p->seek_ms = ms < 0 ? 0 : ms;
    pthread_cond_signal(&p->cond);
    pthread_mutex_unlock(&p->lock);
}

JNIEXPORT void JNICALL
Java_com_example_local_1music_1player_FfmpegPlayerJni_nativeStop(JNIEnv *env, jobject thiz, jlong h) {
    (void)env; (void)thiz;
    Player *p = (Player *)(intptr_t)h;
    if (!p) return;
    pthread_mutex_lock(&p->lock);
    p->stop = 1;
    pthread_cond_signal(&p->cond);
    pthread_mutex_unlock(&p->lock);
}

JNIEXPORT void JNICALL
Java_com_example_local_1music_1player_FfmpegPlayerJni_nativeClose(JNIEnv *env, jobject thiz, jlong h) {
    (void)thiz;
    Player *p = (Player *)(intptr_t)h;
    if (p) player_close(p, env);
}

JNIEXPORT jlong JNICALL
Java_com_example_local_1music_1player_FfmpegPlayerJni_nativePositionMs(JNIEnv *env, jobject thiz, jlong h) {
    (void)env; (void)thiz;
    Player *p = (Player *)(intptr_t)h;
    if (!p) return 0;
    if (p->audio_idx >= 0) return p->audio_clock_ms;
    return p->video_clock_ms > 0 ? p->video_clock_ms : 0;
}

JNIEXPORT jlong JNICALL
Java_com_example_local_1music_1player_FfmpegPlayerJni_nativeDurationMs(JNIEnv *env, jobject thiz, jlong h) {
    (void)env; (void)thiz;
    Player *p = (Player *)(intptr_t)h;
    return p ? p->duration_ms : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_example_local_1music_1player_FfmpegPlayerJni_nativeIsPlaying(JNIEnv *env, jobject thiz, jlong h) {
    (void)env; (void)thiz;
    Player *p = (Player *)(intptr_t)h;
    return (p && p->playing && !p->stop && p->started && !p->ended) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_local_1music_1player_FfmpegPlayerJni_nativeSetVolume(JNIEnv *env, jobject thiz, jlong h, jfloat vol) {
    (void)thiz;
    Player *p = (Player *)(intptr_t)h;
    if (!p || !env) return;
    jobject track = player_audio_track_ref(p, env);
    if (!track) return;
    jclass cls = (*env)->GetObjectClass(env, track);
    if (!cls) { (*env)->DeleteLocalRef(env, track); return; }
    jmethodID m = (*env)->GetMethodID(env, cls, "setVolume", "(F)I");
    if (m) (*env)->CallIntMethod(env, track, m, vol);
    if (cls) (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, track);
    clear_java_exception(env, "AudioTrack.setVolume");
}

JNIEXPORT jstring JNICALL
Java_com_example_local_1music_1player_FfmpegPlayerJni_nativeLastError(JNIEnv *env, jobject thiz, jlong h) {
    (void)thiz;
    Player *p = (Player *)(intptr_t)h;
    if (!p || p->error[0] == 0) return NULL;
    return (*env)->NewStringUTF(env, p->error);
}
