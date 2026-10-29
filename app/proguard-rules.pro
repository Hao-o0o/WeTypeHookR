# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
-dontobfuscate
-dontoptimize

# LibXposed 官方：保持 java_init.list 中入口类名与资源文件内容同步（R8 资源路径重写时同步修正类名）
-adaptresourcefilecontents META-INF/xposed/java_init.list

# 保留模块自身代码：入口与寄生 Activity 都由框架按类名反射加载
-keep class com.haostoo.wetypehookr.** { *; }

# YukiHook 生成的入口（KSP 产出，类名 = 注解类 + _YukiHookXposedInit）
-keep class com.haostoo.wetypehookr.HookEntry_YukiHookXposedInit { *; }

# 寄生 Activity 由框架在启动阶段换回模块类，构造函数必须保留
-keepclassmembers class * extends com.haostoo.wetypehookr.BaseHostActivity {
    public <init>();
}