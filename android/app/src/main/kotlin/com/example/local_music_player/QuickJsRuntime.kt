package com.example.local_music_player

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.JSArray
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSException
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** QuickJS 沙箱运行时：一个插件一个实例，自带单线程；所有 JS 操作在该线程串行执行。 */
class QuickJsRuntime(
    private val pluginName: String,
    private val moduleLoader: (String) -> String? = { null },
) : AutoCloseable {

    companion object {
        private const val TAG = "YueboQuickJs"
        @Volatile private var nativeLoaded = false

        @Synchronized
        fun ensureNativeLoaded() {
            if (!nativeLoaded) {
                QuickJSLoader.init()
                nativeLoaded = true
            }
        }
    }

    private val thread = HandlerThread("quickjs-$pluginName").apply { start() }
    private val handler = Handler(thread.looper)
    private var context: QuickJSContext? = null
    @Volatile private var destroyed = false

    /** 加载阶段禁止真实网络：插件顶层副作用在 evaluate 期间同步请求会阻塞或抛未处理拒绝；加载完成后置 true。 */
    @Volatile var allowNetwork = false
    private val heldObjects = ArrayList<JSObject>()
    private var nextTimerId = 1L
    private val timers = LinkedHashMap<Long, TimerEntry>()
    private val moduleCache = HashMap<String, JSObject>()

    private class TimerEntry(val dueAtMs: Long, val fn: JSFunction)

    /** 在 JS 线程创建上下文并注入全局对象；返回 this 便于链式调用。 */
    fun start(): QuickJsRuntime = postBlocking {
        val ctx = QuickJSContext.create()
        ctx.setMemoryLimit(64 * 1024 * 1024)
        ctx.setConsole(object : QuickJSContext.Console {
            override fun log(info: String) {
                Log.d(TAG, "[$pluginName] $info")
            }
            override fun info(info: String) {
                Log.i(TAG, "[$pluginName] $info")
            }
            override fun warn(info: String) {
                Log.w(TAG, "[$pluginName] $info")
            }
            override fun error(info: String) {
                Log.e(TAG, "[$pluginName] $info")
            }
        })
        val global = ctx.getGlobalObject()
        global.setProperty("setTimeout", JSCallFunction { args -> setTimeoutImpl(args) })
        global.setProperty("clearTimeout", JSCallFunction { args -> clearTimeoutImpl(args) })
        global.setProperty("require", JSCallFunction { args ->
            val name = args.getOrNull(0)?.toString() ?: return@JSCallFunction null
            // 缺失模块返回 undefined 而非抛异常：插件内置 try/catch（如 crypto-js 的
            // require("crypto")）需要能接住，而不是让 Java 异常直接炸掉整个 evaluate。
            requireModule(name)
        })
        context = ctx
        this
    }

    /** 执行脚本，返回原始结果（JSObject 由调用方负责 release）。 */
    fun evaluate(js: String): Any? = postBlocking { context?.evaluate(js) }

    /** 执行脚本并返回 JS 对象（如 module.exports）；调用方负责 release。 */
    fun evaluateObject(js: String): JSObject? = postBlocking { context?.evaluate(js) as? JSObject }

    /** 同步调用 JS 函数，返回原始结果。 */
    fun callFunction(fn: JSFunction, vararg args: Any?): Any? = postBlocking {
        fn.call(*args.map(::kotlinToJs).toTypedArray())
    }

    /** 调用可能返回 Promise 的 JS 函数；驱动微任务与 setTimeout 直到 settled 或超时。 */
    fun callFunctionAsync(fn: JSFunction, timeoutMs: Long, vararg args: Any?): Any? = postBlocking {
        val ctx = context ?: error("QuickJS 运行时未初始化")
        val raw = fn.call(*args.map(::kotlinToJs).toTypedArray())
        awaitSettled(ctx, raw, timeoutMs)
    }

    /** 向全局对象注入值（JSObject/JSCallFunction/Map/List/基本类型）。 */
    fun setGlobal(name: String, value: Any?) = postBlocking {
        val global = context?.getGlobalObject() ?: return@postBlocking
        val jsValue = kotlinToJs(value)
        when (jsValue) {
            null -> global.setProperty(name, null as String?)
            is String -> global.setProperty(name, jsValue)
            is Int -> global.setProperty(name, jsValue)
            is Long -> global.setProperty(name, jsValue)
            is Double -> global.setProperty(name, jsValue)
            is Boolean -> global.setProperty(name, jsValue)
            is JSObject -> global.setProperty(name, jsValue)
            is JSCallFunction -> global.setProperty(name, jsValue)
            is ByteArray -> global.setProperty(name, jsValue)
            else -> global.setProperty(name, jsValue.toString())
        }
    }

    /** JS 值转 Kotlin：JSObject -> Map、JSArray -> List、其余原样。 */
    fun jsToKotlin(value: Any?): Any? = postBlocking {
        when (value) {
            null -> null
            is JSArray -> value.toArray()
            is JSObject -> value.toMap()
            else -> value
        }
    }

    /** Kotlin 值转 JS：Map/List 经 JSON 转换，JSObject 直通。 */
    fun kotlinToJs(value: Any?): Any? = postBlocking {
        when (value) {
            is JSObject -> value
            is Map<*, *> -> mapToJsObject(value)
            is List<*> -> listToJsArray(value)
            else -> value
        }
    }

    /** 构造已 resolve 的 Promise（value 可为 Map/List/基本类型）。 */
    fun resolvePromise(value: Any?): JSObject? = postBlocking {
        promiseHelper("Promise.resolve", value)
    }

    /** 构造已 reject 的 Promise。 */
    fun rejectPromise(value: Any?): JSObject? = postBlocking {
        promiseHelper("Promise.reject", value)
    }

    /** 构造永不 settle 的 Promise：加载阶段用于挂起插件顶层网络副作用，避免 evaluate 期间同步请求。 */
    fun pendingPromise(): JSObject? = postBlocking {
        context?.evaluate("new Promise(function(){})") as? JSObject
    }

    private fun promiseHelper(fnExpr: String, value: Any?): JSObject? {
        val ctx = context ?: return null
        // wrapper 裸调 Promise.resolve/reject 时 this=undefined，QuickJS 会抛 "not an object"；
        // 必须用方法调用表达式（Promise.resolve(...)），this 才是 Promise 构造器。
        val json = when (value) {
            is Map<*, *> -> JSONObject().also { obj ->
                for ((k, v) in value) obj.put(k.toString(), toJsonValue(v))
            }
            is List<*> -> JSONArray().also { arr -> value.forEach { arr.put(toJsonValue(it)) } }
            else -> runCatching { JSONObject.wrap(value) }.getOrElse { JSONObject.NULL }
        }
        return ctx.evaluate("$fnExpr($json)") as? JSObject
    }

    /** 在 JS 线程执行任意 JS 对象操作（quickjs wrapper 要求所有操作与 create 同线程）。 */
    fun <T> onJsThread(block: () -> T): T = postBlocking(block)

    /** 持有一个 JS 对象直到 close()（防止被释放后 JS 侧仍引用）。 */
    fun hold(obj: JSObject): JSObject = postBlocking {
        runCatching { if (obj.isAlive() && obj.getRefCount() > 0) obj.hold() }
        heldObjects.add(obj)
        obj
    }

    fun release(obj: JSObject) = postBlocking {
        heldObjects.remove(obj)
        safeRelease(obj)
    }

    private fun awaitSettled(ctx: QuickJSContext, raw: Any?, timeoutMs: Long): Any? {
        val promise = raw as? JSObject ?: return raw
        val thenFn = promise.getJSFunction("then") ?: return raw
        val settled = AtomicBoolean(false)
        var result: Any? = null
        var error: Any? = null
        thenFn.call(
            JSCallFunction { args ->
                result = jsToKotlin(args.getOrNull(0))
                settled.set(true)
                null
            },
            JSCallFunction { args ->
                error = args.getOrNull(0)
                settled.set(true)
                null
            },
        )
        safeRelease(thenFn)
        if (!settled.get()) {
            val start = SystemClock.uptimeMillis()
            while (!settled.get() && !destroyed) {
                fireDueTimers()
                try {
                    ctx.evaluate("0")
                } catch (t: Throwable) {
                    error = t
                    break
                }
                if (settled.get()) break
                if (SystemClock.uptimeMillis() - start > timeoutMs) {
                    safeRelease(promise)
                    throw TimeoutException("插件执行超时（$pluginName）")
                }
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        safeRelease(promise)
        error?.let { throw pluginError(it) }
        return result
    }

    private fun requireModule(name: String): JSObject? {
        val key = name.removeSuffix(".js")
        moduleCache[key]?.let { return it }
        val code = moduleLoader(key) ?: return null
        // module 必须 IIFE 私有：全局 var module 会被后续 evaluate 覆盖，导致导出错乱。
        val script = "(function() {\n" +
            "var module = { exports: {} };\n" +
            "(function(module, exports, require) {\n" + code + "\n})(module, module.exports, require);\n" +
            "return module.exports;\n})();"
        val exports = context?.evaluate(script) as? JSObject ?: return null
        hold(exports)
        moduleCache[key] = exports
        return exports
    }

    private fun setTimeoutImpl(args: Array<Any?>): Any? {
        val fn = args.getOrNull(0) as? JSFunction ?: return null
        val delay = (args.getOrNull(1) as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L
        val id = nextTimerId++
        fn.hold()
        timers[id] = TimerEntry(SystemClock.uptimeMillis() + delay, fn)
        return id
    }

    private fun clearTimeoutImpl(args: Array<Any?>): Any? {
        val id = (args.getOrNull(0) as? Number)?.toLong() ?: return null
        timers.remove(id)?.let { safeRelease(it.fn) }
        return null
    }

    private fun fireDueTimers() {
        if (timers.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val due = timers.filterValues { it.dueAtMs <= now }
        for ((id, entry) in due) {
            timers.remove(id)
            try {
                entry.fn.call()
            } catch (t: Throwable) {
                Log.w(TAG, "[$pluginName] setTimeout 回调异常: ${t.message}")
            } finally {
                safeRelease(entry.fn)
            }
        }
    }

    private fun pluginError(value: Any?): RuntimeException {
        val message = when (value) {
            is Map<*, *> -> value["message"]?.toString() ?: value.toString()
            is Throwable -> value.message ?: value.toString()
            else -> value?.toString() ?: "未知错误"
        }
        return QuickJSException("插件错误：$message")
    }

    private fun mapToJsObject(map: Map<*, *>): JSObject {
        val ctx = context ?: error("QuickJS 运行时未初始化")
        val json = JSONObject()
        for ((key, value) in map) json.put(key.toString(), toJsonValue(value))
        return (ctx.parse(json.toString()) as? JSObject) ?: ctx.createNewJSObject()
    }

    private fun listToJsArray(list: List<*>): JSArray {
        val ctx = context ?: error("QuickJS 运行时未初始化")
        val json = JSONArray()
        list.forEach { json.put(toJsonValue(it)) }
        return (ctx.parse(json.toString()) as? JSArray) ?: ctx.createNewJSArray()
    }

    private fun toJsonValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> JSONObject().also { obj ->
            for ((k, v) in value) obj.put(k.toString(), toJsonValue(v))
        }
        is List<*> -> JSONArray().also { arr -> value.forEach { arr.put(toJsonValue(it)) } }
        null -> JSONObject.NULL
        is JSObject -> JSONObject.NULL
        else -> value
    }

    private fun <T> postBlocking(block: () -> T): T {
        if (Thread.currentThread() === thread) return block()
        val latch = CountDownLatch(1)
        val box = arrayOfNulls<Any?>(1)
        val error = arrayOfNulls<Throwable>(1)
        handler.post {
            try {
                box[0] = block()
            } catch (t: Throwable) {
                error[0] = t
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        error[0]?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return box[0] as T
    }

    private fun safeRelease(obj: JSObject?) {
        if (obj == null) return
        try {
            if (obj.isAlive() && !obj.isRefCountZero()) obj.release()
        } catch (t: Throwable) {
            Log.w(TAG, "[$pluginName] 释放 JS 对象失败: ${t.message}")
        }
    }

    override fun close() {
        if (destroyed) return
        destroyed = true
        handler.post {
            for (entry in timers.values) safeRelease(entry.fn)
            timers.clear()
            for (obj in heldObjects) safeRelease(obj)
            heldObjects.clear()
            moduleCache.clear()
            try {
                context?.destroy()
            } catch (t: Throwable) {
                Log.w(TAG, "[$pluginName] 关闭 QuickJS 失败: ${t.message}")
            }
            context = null
        }
        thread.quitSafely()
    }
}
