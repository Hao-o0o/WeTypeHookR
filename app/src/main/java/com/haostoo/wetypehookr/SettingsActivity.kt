package com.haostoo.wetypehookr

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import java.util.concurrent.atomic.AtomicInteger

/**
 * 设置页：模块的类，跑在宿主进程里。
 *
 * 因此 `packageName` / `pid` / `filesDir` 全部是宿主的，配置直接落宿主目录，
 * 与 Hook 共享同一批文件与同一个内存缓存。
 */
class SettingsActivity : BaseHostActivity() {

    @SuppressLint("LogNotTimber")
    override fun onCreate(savedInstanceState: Bundle?) {
        aliveCount.incrementAndGet()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val host = packageName
        Log.i(TAG, "SettingsActivity onCreate host=$host pid=${Process.myPid()} filesDir=$filesDir")

        setContent {
            SettingsApp()
        }
    }

    override fun onDestroy() {
        aliveCount.decrementAndGet()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WETYPE_UI"

        private val aliveCount = AtomicInteger()

        /** 有模块设置页存活时，框架会拒绝热重载。 */
        val isAlive: Boolean get() = aliveCount.get() > 0
    }
}
