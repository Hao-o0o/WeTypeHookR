package com.haostoo.wetypehookr

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.util.Log.e
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File


// ================= CompositionLocal =================

val LocalActivity = compositionLocalOf<Activity> { error("No Activity") }
val LocalInjectedLifecycleOwner = compositionLocalOf<ActivityInjectedLifecycleOwner> { error("No LifecycleOwner") }

// ================= 入口 =================

fun injectSettingsPage(activity: Activity) {
    // 1. 创建插件自己的 Context，确保加载插件资源而非宿主资源
    Thread.setDefaultUncaughtExceptionHandler { _, e ->
        try {
            File("/storage/emulated/0/Android/data/com.bytedance.android.doubaoime/files/haostoo/swipe_test.txt")
                .appendText("UNCAUGHT: $e\n${e.stackTraceToString()}\n")
        } catch (_: Exception) {}
    }
    val pluginContext = try {
        activity.createPackageContext(
            "com.haostoo.wetypehookr",
            Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
        ).also {
            val logDir = android.os.Environment.getExternalStorageDirectory()
                .resolve("Android/data/${activity.packageName}/files/haostoo/")
            logDir.mkdirs()
            //logDir.resolve("inject_log.txt").appendText("pluginContext OK\n")
        }
    } catch (e: Exception) {
        val logDir = android.os.Environment.getExternalStorageDirectory()
            .resolve("Android/data/${activity.packageName}/files/haostoo/")
        logDir.mkdirs()
        //logDir.resolve("inject_log.txt").appendText("pluginContext FAILED: $e\n")
        activity
    }

    // 2. 包装 Context：隔离资源
    val isolatedContext = object : ContextWrapper(pluginContext) {
        //override fun getResources(): Resources = pluginContext.resources
        override fun getApplicationContext(): Context = this
        //override fun getTheme(): Resources.Theme = pluginContext.theme
        override fun getSystemService(name: String): Any? {
            if (name == WINDOW_SERVICE) return activity.getSystemService(name)
            return super.getSystemService(name)
        }
    }

    // 3. Lifecycle 宿主
    val lifecycleOwner = ActivityInjectedLifecycleOwner()
    lifecycleOwner.start()

    // 4. 隐藏 ActionBar（可选）
    activity.actionBar?.hide()
    val safeContext = object : ContextWrapper(activity) {
        private val safeResources = object : Resources(activity.assets, activity.resources.displayMetrics, activity.resources.configuration) {
            override fun getText(id: Int): CharSequence {
                return try { super.getText(id) } catch (_: Exception) { "" }
            }
            override fun getString(id: Int): String {
                return try { super.getString(id) } catch (_: Exception) { "" }
            }
            override fun getString(id: Int, vararg formatArgs: Any?): String {
                return try { super.getString(id, *formatArgs) } catch (_: Exception) { "" }
            }
        }
        override fun getResources(): Resources = safeResources
    }
    // 5. ComposeView
    val composeView = ComposeView(safeContext).apply {
        setViewTreeLifecycleOwner(lifecycleOwner)
        setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        setContent {
            val navOwner = remember { FakeNavigationEventDispatcherOwner() }
            val hostPackage = activity.packageName
            setContent {
                val navOwner = remember { FakeNavigationEventDispatcherOwner() }

                CompositionLocalProvider(
                    LocalContext provides isolatedContext,
                    LocalOnBackPressedDispatcherOwner provides lifecycleOwner,
                    LocalNavigationEventDispatcherOwner provides navOwner,
                    LocalViewModelStoreOwner provides lifecycleOwner,
                    LocalActivity provides activity,
                    LocalInjectedLifecycleOwner provides lifecycleOwner
                ) {
                    MiuixTheme {
                        App(onClose = { activity.finish() })
                    }
                }
            }
        }
//        setContent {
//            val navOwner = remember { FakeNavigationEventDispatcherOwner() }
//
//            CompositionLocalProvider(
//                LocalContext provides isolatedContext,
//                LocalOnBackPressedDispatcherOwner provides lifecycleOwner,
//                LocalNavigationEventDispatcherOwner provides navOwner,
//                LocalViewModelStoreOwner provides lifecycleOwner,
//                LocalActivity provides activity,
//                LocalInjectedLifecycleOwner provides lifecycleOwner
//            ) {
//                MiuixTheme {
//                    App(onClose = { activity.finish() })
//                }
//            }
//        }

        // 生命周期回收
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                lifecycleOwner.stop()
            }
        })
    }

    // 6. 替换 Activity 内容
    val root = activity.window.decorView
        .findViewById<ViewGroup>(android.R.id.content)

    root.removeAllViews()
    root.addView(
        composeView,
        ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    )
}

// ================= Navigation 修复 =================

class FakeNavigationEventDispatcherOwner :
    NavigationEventDispatcherOwner {

    override val navigationEventDispatcher: NavigationEventDispatcher =
        NavigationEventDispatcher()
}

// ================= Lifecycle 宿主 =================

class ActivityInjectedLifecycleOwner :
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner,
    OnBackPressedDispatcherOwner {

    var onActivityResult: ((requestCode: Int, resultCode: Int, data: android.content.Intent?) -> Unit)? = null

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val onBackPressedDispatcher = OnBackPressedDispatcher()
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    fun start() {
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun stop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }
}