package com.trackspeed.android.camera

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal frame processor - just holds gate position.
 * Photo Finish mode handles all processing inside PhotoFinishDetector.
 */
@Singleton
class FrameProcessor @Inject constructor() {
    // Gate position is now managed by GateEngine directly.
    // This class is kept for backward compatibility with any remaining references.
}
