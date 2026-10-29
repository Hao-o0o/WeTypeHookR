package com.haostoo.wetypehookr

import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.highcapable.yukihookapi.hook.xposed.parasitic.activity.proxy.ModuleActivity

/**
 * 运行在宿主进程里的模块 Activity 基类。
 *
 * 三个生命周期方法与 [getClassLoader] 必须委托给 [delegate]：
 * 它们负责应用模块主题、重新注入模块资源、修正 `savedInstanceState` 的 ClassLoader。
 */
abstract class BaseHostActivity : AppCompatActivity(), ModuleActivity {

    override val moduleTheme: Int get() = R.style.Theme_WeTypeHookR

    override fun getClassLoader(): ClassLoader = delegate.getClassLoader()

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.onCreate(savedInstanceState)
        super.onCreate(savedInstanceState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        delegate.onConfigurationChanged(newConfig)
        super.onConfigurationChanged(newConfig)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        delegate.onRestoreInstanceState(savedInstanceState)
        super.onRestoreInstanceState(savedInstanceState)
    }
}
