package com.anaalarm.support

import android.Manifest
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.rule.GrantPermissionRule
import com.anaalarm.MainActivity
import com.anaalarm.ui.wakeup.WakeUpActivity

/**
 * Activities are launched by hand (rather than with an activity rule) so tests can seed the
 * database and settings *before* the first composition runs.
 */
object Screens {

    fun launchHome(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(MainActivity::class.java)

    fun launchWakeUp(): ActivityScenario<WakeUpActivity> =
        ActivityScenario.launch(WakeUpActivity::class.java)

    fun runtimePermissions(): GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) {
            GrantPermissionRule.grant(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
        }
}
