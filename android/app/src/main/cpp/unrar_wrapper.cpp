#include <jni.h>
#include <string>
#include "dll.hpp"

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_local_1music_1player_UnrarNative_isEncrypted(
    JNIEnv* env, jclass, jstring pathJ) {
    const char* path = env->GetStringUTFChars(pathJ, nullptr);
    RAROpenArchiveDataEx data{};
    data.ArcName = const_cast<char*>(path);
    data.OpenMode = RAR_OM_LIST;
    data.CmtBuf = nullptr;
    HANDLE hArc = RAROpenArchiveEx(&data);
    env->ReleaseStringUTFChars(pathJ, path);
    if (!hArc) return JNI_FALSE;
    jboolean encrypted = JNI_FALSE;
    RARHeaderDataEx header{};
    header.CmtBuf = nullptr;
    if (RARReadHeaderEx(hArc, &header) == 0) {
        encrypted = (header.Flags & RHDF_ENCRYPTED) ? JNI_TRUE : JNI_FALSE;
    }
    RARCloseArchive(hArc);
    return encrypted;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_local_1music_1player_UnrarNative_extract(
    JNIEnv* env, jclass, jstring pathJ, jstring destJ, jstring pwdJ) {
    const char* path = env->GetStringUTFChars(pathJ, nullptr);
    const char* dest = env->GetStringUTFChars(destJ, nullptr);
    const char* pwd = pwdJ ? env->GetStringUTFChars(pwdJ, nullptr) : nullptr;

    RAROpenArchiveDataEx data{};
    data.ArcName = const_cast<char*>(path);
    data.OpenMode = RAR_OM_EXTRACT;
    data.CmtBuf = nullptr;
    HANDLE hArc = RAROpenArchiveEx(&data);
    if (!hArc) {
        if (pwdJ) env->ReleaseStringUTFChars(pwdJ, pwd);
        env->ReleaseStringUTFChars(destJ, dest);
        env->ReleaseStringUTFChars(pathJ, path);
        return ERAR_EOPEN;
    }
    if (pwd) RARSetPassword(hArc, const_cast<char*>(pwd));

    int rc = 0;
    RARHeaderDataEx header{};
    header.CmtBuf = nullptr;
    while ((rc = RARReadHeaderEx(hArc, &header)) == 0) {
        rc = RARProcessFile(hArc, RAR_EXTRACT, const_cast<char*>(dest), nullptr);
        if (rc != 0) break;
    }
    RARCloseArchive(hArc);

    if (pwdJ) env->ReleaseStringUTFChars(pwdJ, pwd);
    env->ReleaseStringUTFChars(destJ, dest);
    env->ReleaseStringUTFChars(pathJ, path);
    return rc == ERAR_END_ARCHIVE ? 0 : rc;
}
