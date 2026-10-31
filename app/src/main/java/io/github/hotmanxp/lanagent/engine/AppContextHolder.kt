// AppContextHolder.kt — 全进程可读的 Application Context。
//
// 能力实现是 object 单例,拿不到构造参数,系统 API(PackageManager /
// NotificationManager / MediaProjection)又都要 Context —— 所以 Application
// 起来时把 Context 存一份在这里。
//
// 为什么不用 getApplicationContext() 就地拿:能力实现被 HTTP handler 以
// 静态方法的方式调用,拿不到上下文参数。
package io.github.hotmanxp.lanagent.engine

import android.content.Context

object AppContextHolder {

    @Volatile
    var context: Context? = null
}
