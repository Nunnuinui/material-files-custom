/*
 * Custom fork addition: PiP registration helper.
 */

package me.zhanghai.android.files.player;

import android.app.Activity;

/**
 * PiP parameter registration states that Kotlin cannot express:
 * the framework marks the parameter non-null, but unregistering
 * the params entirely is done by passing null from Java.
 */
public final class PipRegistration {
    private PipRegistration() {}

    /** Unregisters the activity's PiP params entirely. */
    public static void clear(Activity activity) {
        activity.setPictureInPictureParams(null);
    }
}
