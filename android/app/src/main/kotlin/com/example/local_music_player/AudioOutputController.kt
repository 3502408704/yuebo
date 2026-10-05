package com.example.local_music_player

import android.content.Context
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.media.AudioDeviceInfo
import android.media.AudioDeviceCallback
import android.media.AudioManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper

data class AudioOutput(val id: String, val name: String, val protocol: String, val device: AudioDeviceInfo?)

class AudioOutputController(context: Context) {
    private val context = context.applicationContext
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val adapter = BluetoothAdapter.getDefaultAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var a2dp: BluetoothA2dp? = null
    private var profileChanged: (() -> Unit)? = null
    private val a2dpListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            a2dp = proxy as? BluetoothA2dp
            handler.post { profileChanged?.invoke() }
        }

        override fun onServiceDisconnected(profile: Int) {
            a2dp = null
            handler.post { profileChanged?.invoke() }
        }
    }

    fun outputs(): List<AudioOutput> {
        bindA2dp()
        val devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        val local = devices.filterNot(::isBluetooth).map(::describe).distinctBy { it.name }
            .sortedWith(compareBy<AudioOutput> {
                when (it.device?.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 0
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> 1
                    else -> 2
                }
            }.thenBy { it.name })
        val bluetooth = connectedBluetoothOutputs(devices)
        return local + bluetooth
    }

    fun describe(device: AudioDeviceInfo): AudioOutput = AudioOutput(
        id = device.id.toString(),
        name = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "本机扬声器"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "本机听筒"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET -> "外接音频"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> device.productName.toString().ifBlank { "蓝牙设备" }
            else -> "外接音频"
        },
        protocol = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "本机"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "蓝牙"
            else -> "本机"
        },
        device = device,
    )

    fun observeChanges(onChanged: () -> Unit): AudioDeviceCallback = object : AudioDeviceCallback() {
        init { profileChanged = onChanged }
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = onChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = onChanged()
    }.also { manager.registerAudioDeviceCallback(it, Handler(Looper.getMainLooper())) }

    fun stopObserving(callback: AudioDeviceCallback) {
        manager.unregisterAudioDeviceCallback(callback)
        profileChanged = null
        a2dp?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        a2dp = null
    }

    private fun bindA2dp() {
        if (a2dp == null) runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) return@runCatching
            adapter?.getProfileProxy(context, a2dpListener, BluetoothProfile.A2DP)
        }
    }

    private fun connectedBluetoothOutputs(devices: List<AudioDeviceInfo>): List<AudioOutput> {
        val mediaOutputs = devices.filter(::isBluetoothMedia)
        val connected = runCatching { a2dp?.connectedDevices.orEmpty() }.getOrDefault(emptyList())
        val profileOutputs = connected.map { bluetooth ->
            val target = mediaOutputs.firstOrNull { it.matches(bluetooth) }
            AudioOutput(
                id = "bluetooth:${bluetooth.address}",
                name = bluetooth.name?.toString().orEmpty().ifBlank { "蓝牙设备" },
                protocol = "蓝牙",
                device = target,
            )
        }
        return (profileOutputs + mediaOutputs.map(::describe)).distinctBy { it.name }
    }

    private fun AudioDeviceInfo.matches(device: BluetoothDevice): Boolean =
        runCatching { address == device.address }.getOrDefault(false) ||
            productName.toString() == device.name?.toString()

    private fun isBluetooth(device: AudioDeviceInfo) = device.type in setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
    )

    private fun isBluetoothMedia(device: AudioDeviceInfo) = device.type in setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
    )

}
