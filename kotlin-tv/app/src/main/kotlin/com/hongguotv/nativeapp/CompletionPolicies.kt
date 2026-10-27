// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.Series

class IncompatibleEpisodeException(message:String="没有电视可播放的编码或清晰度"):java.io.IOException(message)

/** Only consecutive automatic skips consume the budget. Rendering and explicit actions reset it. */
class IncompatibleSkipBudget {
    private var expected:Int?=null
    var count=0;private set
    fun rendered() {expected=null;count=0}
    fun next(current:Int,last:Int,enabled:Boolean,codecFailure:Boolean,canContinue:Boolean):Int? {
        if(!enabled || !codecFailure || !canContinue || current>=last || current<0)return null
        if(expected!=current)count=0
        if(count>=5)return null
        count++;expected=current+1;return current+1
    }
    companion object {
        // Media3 stable PlaybackException decoder-init/decode/format codes, not network/DRM errors.
        fun codecError(code:Int)=code in setOf(4001,4003,4004,4005)
    }
}

object ExitRecommendationPolicy {
    fun pick(updated:List<Series>,candidates:List<Series>,hidden:Set<String>):List<Series> =
        (updated+candidates).filterNot {it.id in hidden}.distinctBy {it.id}.take(3)
}
