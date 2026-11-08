package ua.zevs.anitubetv

import android.app.Activity
import android.content.Context
import android.content.res.Configuration

/**
 * The projector reports a high density, so 1280x720 is only ~640 dp wide and
 * everything looks huge. Force a TV-like density: the screen becomes ~960 dp wide.
 */
open class TvActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        val dm = newBase.resources.displayMetrics
        val longSide = maxOf(dm.widthPixels, dm.heightPixels)
        val targetDpi = (longSide * 160 / 960).coerceIn(120, 640)
        val cfg = Configuration(newBase.resources.configuration)
        if (cfg.densityDpi > targetDpi) {
            cfg.densityDpi = targetDpi
            cfg.screenWidthDp = dm.widthPixels * 160 / targetDpi
            cfg.screenHeightDp = dm.heightPixels * 160 / targetDpi
            cfg.smallestScreenWidthDp = minOf(cfg.screenWidthDp, cfg.screenHeightDp)
            super.attachBaseContext(newBase.createConfigurationContext(cfg))
        } else {
            super.attachBaseContext(newBase)
        }
    }
}
