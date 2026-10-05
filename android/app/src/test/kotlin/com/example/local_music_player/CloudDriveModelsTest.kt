package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudDriveModelsTest {
    @Test
    fun baiduFileMapsToCloudFile() {
        val baidu = BaiduPanFile(
            fsId = 123L,
            path = "/音乐/song.mp3",
            isDir = false,
            serverFilename = "song.mp3",
            size = 2048L,
            category = 2,
            serverMtime = 1700000000L,
        )
        val cloud = baidu.toCloudFile()
        assertEquals(CloudDisk.BAIDU, cloud.provider)
        assertEquals("123", cloud.id)
        assertEquals("/音乐/song.mp3", cloud.path)
        assertEquals("/音乐", cloud.parentId)
        assertEquals("song.mp3", cloud.name)
        assertEquals(false, cloud.isDir)
        assertEquals(2048L, cloud.size)
        assertEquals(1700000000000L, cloud.mtime)
    }

    @Test
    fun quarkFileMapsToCloudFile() {
        val quark = QuarkPanFile(
            fid = "f1",
            pdirFid = "0",
            fileName = "歌.mp3",
            isDir = false,
            size = 1024L,
            category = 2,
            updatedAt = 1700000000000L,
        )
        val cloud = quark.toCloudFile()
        assertEquals(CloudDisk.QUARK, cloud.provider)
        assertEquals("f1", cloud.id)
        assertEquals("f1", cloud.path)
        assertEquals("0", cloud.parentId)
        assertEquals("歌.mp3", cloud.name)
        assertEquals(1024L, cloud.size)
        assertEquals(1700000000000L, cloud.mtime)
    }

    @Test
    fun cloudFileRoundTripsToBaidu() {
        val cloud = BaiduPanFile(
            fsId = 7L,
            path = "/a/b.mp3",
            isDir = false,
            serverFilename = "b.mp3",
            size = 99L,
            category = 2,
            serverMtime = 1700000000L,
        ).toCloudFile()
        val baidu = cloud.toBaiduPanFile()
        assertEquals(7L, baidu.fsId)
        assertEquals("/a/b.mp3", baidu.path)
        assertEquals("b.mp3", baidu.serverFilename)
        assertEquals(99L, baidu.size)
        assertEquals(1700000000L, baidu.serverMtime)
    }

    @Test
    fun cloudFileRoundTripsToQuark() {
        val cloud = QuarkPanFile(
            fid = "f9",
            pdirFid = "0",
            fileName = "x.flac",
            isDir = false,
            size = 555L,
            category = 2,
            updatedAt = 1700000000000L,
        ).toCloudFile()
        val quark = cloud.toQuarkPanFile()
        assertEquals("f9", quark.fid)
        assertEquals("0", quark.pdirFid)
        assertEquals("x.flac", quark.fileName)
        assertEquals(555L, quark.size)
        assertEquals(1700000000000L, quark.updatedAt)
    }

    @Test
    fun quarkTagPrefixesFid() {
        assertEquals("quark:f1", quarkTag("f1"))
        assertEquals("quark:abc123", quarkTag("abc123"))
    }

    @Test
    fun diskDisplayNamesAreChinese() {
        assertEquals("百度网盘", CloudDisk.BAIDU.displayName)
        assertEquals("夸克网盘", CloudDisk.QUARK.displayName)
        assertTrue(CloudDisk.entries.size == 2)
    }
}
