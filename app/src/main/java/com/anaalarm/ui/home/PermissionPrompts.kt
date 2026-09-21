package com.anaalarm.ui.home

/**
 * Home shows a recovery card only after the user has actually denied microphone
 * access. A not-yet-answered first launch stays on the system dialog.
 */
internal fun showMicRecoveryCard(permissionGranted: Boolean, userDeniedRequest: Boolean): Boolean =
    !permissionGranted && userDeniedRequest
