package com.example.local_music_player

object UnrarNative {
    init {
        System.loadLibrary("unrar_wrapper")
    }

    /** 0=成功；22=需要密码；24=密码错误；其它为 unrar 错误码。 */
    external fun extract(path: String, destDir: String, password: String?): Int

    external fun isEncrypted(path: String): Boolean
}
