package io.github.usernamehaha.autohost.internal

import android.os.SystemClock
import kotlin.time.AbstractLongTimeSource
import kotlin.time.DurationUnit

/**
 * `System.nanoTime()` 在设备深度休眠时不走，用它衡量"测量结果过了多久"会把睡了一夜的数据当成新鲜的。
 * `elapsedRealtime` 包含休眠时间。
 */
internal object ElapsedRealtimeSource : AbstractLongTimeSource(DurationUnit.NANOSECONDS) {
    override fun read(): Long = SystemClock.elapsedRealtimeNanos()
}
