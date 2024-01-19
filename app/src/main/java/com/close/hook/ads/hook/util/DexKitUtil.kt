package com.close.hook.ads.hook.util

import android.content.Context
import com.google.common.cache.CacheBuilder
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.util.concurrent.TimeUnit

object DexKitUtil {
    @Volatile private var bridge: DexKitBridge? = null
    private val methodCache = CacheBuilder.newBuilder()
        .maximumSize(100)
        .expireAfterWrite(30, TimeUnit.MINUTES)
        .build<String, List<MethodData>>()

    fun initializeDexKitBridge(context: Context) {
        if (bridge == null) {
            synchronized(this) {
                if (bridge == null) {
                    System.loadLibrary("dexkit")
                    bridge = DexKitBridge.create(context.applicationInfo.sourceDir)
                }
            }
        }
    }

    fun getBridge(): DexKitBridge {
        return bridge ?: throw IllegalStateException("DexKitBridge not initialized")
    }

    fun releaseBridge() {
        synchronized(this) {
            bridge?.close()
            bridge = null
        }
    }

    fun getCachedOrFindMethods(packageName: String, findMethodLogic: () -> List<MethodData>?): List<MethodData>? {
        return methodCache.get(packageName) { findMethodLogic() }
    }
}