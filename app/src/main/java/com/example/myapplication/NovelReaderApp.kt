package com.example.myapplication

import android.app.Application
import android.content.Context
import com.example.myapplication.data.AppContainer

/**
 * 应用入口。
 *
 * ══ Application 是什么 ═════════════════════════════════════════
 * 它是整个进程里【最早】被创建的 Android 组件，比任何 Activity 都早，
 * 而且整个进程里只有一个实例。
 *
 * 正因为如此，它是放"全局单例"的天然位置 —— 比如我们的 AppContainer。
 *
 * 【为什么不在 MainActivity 里创建 AppContainer】
 * 因为 Activity 会因为屏幕旋转、返回栈变化等原因被销毁重建。
 * 放在 Activity 里会导致：
 *   - 每转一次屏就重建一次书库对象（浪费）
 *   - 两个 Activity 可能持有不同的容器实例（状态不一致）
 *
 * 放进 Application 就只有一个，生命周期和进程一样长。
 * ═══════════════════════════════════════════════════════════════
 *
 * 【别忘了在 AndroidManifest.xml 里注册】
 * <application android:name=".NovelReaderApp" ...>
 * 漏了这行的话，Android 会创建一个默认的 Application 而不是这个类，
 * 于是 container 永远不会被初始化，第一次访问就抛
 * UninitializedPropertyAccessException。
 * 这是新手很常见的一个"莫名其妙崩溃"，原因就是忘了注册。
 */
class NovelReaderApp : Application() {

    /**
     * 全局依赖容器。
     *
     * `lateinit` 表示"我现在还不能赋值，但保证在用之前会赋值"。
     * 它不能是 nullable，因为我们不希望每个使用点都写 `!!` 或 `?.`。
     *
     * `private set` 保证只有这个类自己能赋值 ——
     * 别的地方只能读，不能偷偷替换掉全局容器。
     */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // onCreate 在 Activity 之前执行，所以任何界面代码访问 container 时它一定已经就绪
        container = AppContainer(this)
    }
}

/**
 * 取容器的便捷扩展。
 *
 * 用起来是 `context.appContainer`，比
 * `(context.applicationContext as NovelReaderApp).container` 短很多，
 * 而且把那个强制类型转换只写在一处 —— 将来如果换了容器持有方式，
 * 只需要改这里。
 */
val Context.appContainer: AppContainer
    get() = (applicationContext as NovelReaderApp).container
