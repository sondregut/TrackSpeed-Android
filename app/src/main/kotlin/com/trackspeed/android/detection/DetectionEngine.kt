package com.trackspeed.android.detection

import android.util.Log
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Geometry-based crossing detector ported from iOS `DetectionEngine.swift`
 * (commits 4fdf2b4a + c46bbac4). Replaces the previous PhotoFinishDetector
 * + ZeroAllocCCL + RollingShutterCalculator stack.
 *
 * Pipeline (per frame):
 *   1. Downsample Y plane to processWidth × processHeight (180×320 portrait).
 *   2. Frame diff vs previous frame, threshold → binary motion mask.
 *   3. 8-way connected components (union-find).
 *   4. For each component, prefilter on size / fill / aspect / flash guard,
 *      with a two-tier (strict / lenient) rule keyed on whether the blob
 *      has a qualifying gate-column vertical run (§24).
 *   5. Pick the largest qualifying blob; require it intersect a thick gate
 *      band (gateColumn ± 4) projection that is ≥ max(30, 0.25 × blobH)
 *      in vertical run length (§23, §27, §34).
 *   6. Apply §35 H-TEMPORAL-WAIT (drop lower-half qualifiers) and
 *      §37 H-LIMB-WAIT-RELEASE (release suppression after 3 frames).
 *   7. Apply §31 head-snag picker bias and §29 EMPTY_STRIP_FALLBACK.
 *   8. detY = blob.minY + 0.30 × blobHeight (§48 H-DETY-BLOB-RELATIVE).
 *   9. Strip-width interpolation at detY for sub-frame timing.
 *  10. Low-light exposure correction (+0.75 × exposureSec for >2 ms).
 *
 * The §22 limb-lead diagnostic logs and the columnStats / arm-spike
 * profiling code from iOS are intentionally not ported — they don't affect
 * detection, only feed the iOS log-analysis tooling.
 */
class DetectionEngine(injectedConfiguration: ReplicaDetectionConfiguration? = null) {

    private val usesSharedConfiguration = injectedConfiguration == null
    private var configuration = injectedConfiguration?.let {
        it.copy(parameters = it.parameters.copy())
    } ?: ReplicaDetectionConfigurationStore.snapshot()
    private val parameters: ReplicaDetectionConfiguration.Parameters
        get() = configuration.parameters

    // ── Processing geometry (portrait) ─────────────────────────────────
    private val processWidth = PROCESS_WIDTH
    private val processHeight = PROCESS_HEIGHT
    private var scaleX = 4
    private var scaleY = 4
    private var lastFullW = 0
    private var lastFullH = 0
    private var lastTransposed = false

    val gateColumn: Int = processWidth / 2

    // ── Buffers ────────────────────────────────────────────────────────
    private val bufferA = ByteArray(processWidth * processHeight)
    private val bufferB = ByteArray(processWidth * processHeight)
    private var usingA = true
    private val maskBuf = ByteArray(processWidth * processHeight)
    private val labels = IntArray(processWidth * processHeight)
    private var parentBuf = IntArray(0)
    private var compBuf = arrayOf<Component>()

    // ── Previous-frame bookkeeping ─────────────────────────────────────
    private var hasPrevious = false
    private var previousTimestampNanos = 0L
    private var previousExposureNanos: Long? = null

    // ── Session state ──────────────────────────────────────────────────
    @Volatile var isActive: Boolean = false
        private set
    @Volatile var isFrontCamera: Boolean = false
    @Volatile var latestGateOccupancy: Float = 0f
        private set
    private var sessionStartNanos: Long? = null
    private var lastDetectionRealElapsedNanos: Long? = null
    private var gateOccupied = false
    private var gateBuildup = 0
    private var lowContrastSequenceFrames = 0
    private var limbWaitStreak = 0
    private val motionDirectionHistory = ArrayList<MotionDirectionSample>(MOTION_DIRECTION_HISTORY_LEN)
    private var frameIndex = 0
    private var cooldownOverrideSeconds: Double? = null
    private val cooldownSeconds: Double
        get() = cooldownOverrideSeconds ?: parameters.cooldownSeconds

    /**
     * 0..1, fraction of frame width where the gate sits. Updated by the UI
     * when the user drags the gate line. The gateColumn used internally is
     * fixed at processWidth / 2 (matches iOS), so changing this currently
     * has no effect on detection — kept for forward compatibility with a
     * future re-parameterization.
     */
    var gatePositionFraction: Float = 0.5f

    init {
        // Not strictly required (Kotlin zero-inits), but matches iOS init
        // semantics and makes intent explicit.
        bufferA.fill(0)
        bufferB.fill(0)
        maskBuf.fill(0)
    }

    // ── Public control ─────────────────────────────────────────────────

    @Synchronized
    fun setCooldown(durationSeconds: Double) {
        cooldownOverrideSeconds = durationSeconds.coerceAtLeast(0.0)
    }

    @Synchronized
    fun clearCooldownOverride() {
        cooldownOverrideSeconds = null
    }

    @Synchronized
    fun start(timestampNanos: Long? = null) {
        if (usesSharedConfiguration) {
            configuration = ReplicaDetectionConfigurationStore.snapshot()
        }
        isActive = true
        latestGateOccupancy = 0f
        sessionStartNanos = timestampNanos
        lastDetectionRealElapsedNanos = null
        gateOccupied = false
        gateBuildup = 0
        lowContrastSequenceFrames = 0
        limbWaitStreak = 0
        motionDirectionHistory.clear()
        hasPrevious = false
        frameIndex = 0
        lastFullW = 0
        lastFullH = 0
    }

    @Synchronized
    fun stop() {
        isActive = false
        latestGateOccupancy = 0f
    }

    /**
     * Re-arm the warmup counter so the next N frames are skipped — call
     * after a camera-session interruption (background/foreground) to
     * suppress the exposure-snap false positive.
     */
    @Synchronized
    fun resetWarmup() {
        frameIndex = 0
        hasPrevious = false
        motionDirectionHistory.clear()
    }

    @Synchronized
    fun reset() {
        isActive = false
        latestGateOccupancy = 0f
        sessionStartNanos = null
        lastDetectionRealElapsedNanos = null
        gateOccupied = false
        hasPrevious = false
        frameIndex = 0
        lastFullW = 0
        lastFullH = 0
        gateBuildup = 0
        lowContrastSequenceFrames = 0
        limbWaitStreak = 0
        motionDirectionHistory.clear()
    }

    // ── Main entry point ───────────────────────────────────────────────

    /**
     * Process one frame. Returns a [Result] when a crossing fires this
     * frame, otherwise null. Processing and control methods share this
     * instance's monitor, so camera restart and frame callbacks cannot race.
     */
    @Synchronized
    fun processFrame(
        yPlane: ByteArray,
        fullWidth: Int,
        fullHeight: Int,
        rowStride: Int,
        timestampNanos: Long,
        exposureNanos: Long? = null
    ): Result? {
        if (!isActive) return null
        if (sessionStartNanos == null) sessionStartNanos = timestampNanos
        frameIndex++

        // Y plane sometimes arrives landscape even with rotation hinted;
        // detect and transpose during downsample.
        val isLandscape = fullWidth > fullHeight

        if (fullWidth != lastFullW || fullHeight != lastFullH) {
            lastFullW = fullWidth
            lastFullH = fullHeight
            lastTransposed = isLandscape
            val srcW = if (isLandscape) fullHeight else fullWidth
            val srcH = if (isLandscape) fullWidth else fullHeight
            scaleX = max(srcW / processWidth, 1)
            scaleY = max(srcH / processHeight, 1)
            hasPrevious = false
        }

        val w = processWidth
        val h = processHeight
        val cur = if (usingA) bufferA else bufferB
        val prev = if (usingA) bufferB else bufferA

        extractGray(yPlane, fullWidth, fullHeight, rowStride, isLandscape, cur)

        try {
            if (!hasPrevious) return null
            if (frameIndex <= parameters.warmupFrames) return null

            // 1. Frame diff + threshold (fused).
            val count = w * h
            for (i in 0 until count) {
                val d = (cur[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF)
                maskBuf[i] = if ((if (d < 0) -d else d) >= parameters.diffThreshold) 1 else 0
            }
            latestGateOccupancy = gateBandOccupancy(parameters.gateBandHalfWidth)

            // Cooldown — compare against real frame-time of last fire.
            val sessionStart = sessionStartNanos ?: return null
            val elapsedNanos = timestampNanos - sessionStart
            val cooldownNanos = (cooldownSeconds * 1e9).toLong()
            lastDetectionRealElapsedNanos?.let { lastReal ->
                if (elapsedNanos - lastReal < cooldownNanos) return null
            }

            // 2. §23 + §34 thick-band gate-column runs (gateColumn ± 4 OR-projection).
            val frameGateRuns = gateColumnRunsThick(parameters.thickGateHalfWidth)
            val frameGateRunsMerged = if (parameters.useLeadingEdgeTrigger) {
                mergeRuns(frameGateRuns, parameters.gateRunMergeMaxGap)
            } else {
                frameGateRuns
            }
            val gateBandMetrics = gateBandMetrics(parameters.gateBandHalfWidth)
            val longestMergedGateRun = frameGateRunsMerged.maxOfOrNull { it.length } ?: 0
            val hasLowContrastGateSequenceFrame =
                parameters.useLeadingEdgeTrigger &&
                    parameters.lowContrastBodyFallbackEnabled &&
                    longestMergedGateRun >= parameters.lowContrastBodyFallbackMinMergedRun &&
                    gateBandMetrics.columns >= parameters.lowContrastBodyFallbackMinGateBandColumns &&
                    gateBandMetrics.pixels >= parameters.lowContrastBodyFallbackMinGateBandPixels
            lowContrastSequenceFrames = if (hasLowContrastGateSequenceFrame) {
                lowContrastSequenceFrames + 1
            } else {
                0
            }
            val lowContrastPreflightActive =
                lowContrastSequenceFrames >= parameters.lowContrastBodyFallbackMinSequenceFrames &&
                    longestMergedGateRun in parameters.lowContrastBodyFallbackMinMergedRun until parameters.torsoRunAbsMin &&
                    !gateOccupied

            // 3. Connected components.
            val components = findComponents()

            // 4. Blob filtering + selection.
            val minH = (h * parameters.heightFraction).toInt()
            val minW = (w * parameters.widthFraction).toInt()
            val gMin = max(gateColumn - parameters.gateBandHalfWidth, 0)
            val gMax = min(gateColumn + parameters.gateBandHalfWidth, w - 1)

            var best: Candidate? = null
            var anyBlobAtGate = false

            for (comp in components) {
                if (comp.height < minH) continue
                if (comp.width < minW) continue

                // §24 two-tier prefilter — lenient if the blob has a
                // qualifying gate-band run, strict otherwise.
                val qualifyingMin = max(
                    parameters.torsoRunAbsMin,
                    min(parameters.torsoRunAbsMax, (comp.height * parameters.torsoRunHeightFraction).toInt())
                )
                var hasQualifyingRun = false
                for (run in frameGateRunsMerged) {
                    if (run.endY < comp.minY || run.startY > comp.maxY) continue
                    if (run.endY - run.startY + 1 >= qualifyingMin) {
                        hasQualifyingRun = true
                        break
                    }
                }
                val useLenient = parameters.useLeadingEdgeTrigger &&
                    (hasQualifyingRun || lowContrastPreflightActive)
                val fillFloor = if (useLenient) parameters.minFillRatioLenient else parameters.minFillRatio
                val aspectCeil = if (useLenient) parameters.maxAspectRatioLenient else parameters.maxAspectRatio

                val fillRatio = comp.area.toFloat() / (comp.width * comp.height).toFloat()
                if (fillRatio < fillFloor) continue
                if (comp.width > (aspectCeil * comp.height).toInt()) continue
                if (comp.maxX < gMin || comp.minX > gMax) continue

                // §19 full-frame flash guard.
                val frameArea = (w * h).toFloat()
                val coverage = comp.area.toFloat() / frameArea
                val wFrac = comp.width.toFloat() / w
                val hFrac = comp.height.toFloat() / h
                if (coverage > parameters.flashGuardCoverage
                    && fillRatio > parameters.flashGuardFill
                    && wFrac > parameters.flashGuardWidthFraction
                    && hFrac > parameters.flashGuardHeightFraction
                ) continue

                // §50 Full-width band guard — reject global horizontal
                // scene/exposure changes that span essentially the whole
                // frame. Real runner crossings stay below this width.
                if (wFrac >= parameters.fullWidthBandWidthFraction) continue

                // When the remotely selected iOS profile disables the §23
                // leading-edge gate, use the same pre-§23 local-support
                // rule: a 3-column window in the ±2 gate band must average
                // at least 8% of the working-frame height. Without this
                // branch Android would accept the flag but still execute
                // the newer torso-run gate, silently diverging from iOS.
                if (!parameters.useLeadingEdgeTrigger &&
                    !hasLegacyLocalGateSupport(comp, gMin, gMax)
                ) continue

                val lowContrastEligible = lowContrastPreflightActive &&
                    lowContrastBodyFallbackEligible(
                        comp = comp,
                        mergedRuns = frameGateRunsMerged,
                        gateBand = gateBandMetrics,
                        sequenceFrames = lowContrastSequenceFrames
                    )
                if (!hasQualifyingRun && lowContrastPreflightActive && !lowContrastEligible) continue
                anyBlobAtGate = true

                if (best == null || comp.area > best!!.comp.area) {
                    best = Candidate(
                        comp = comp,
                        lowContrastFallback = !hasQualifyingRun && lowContrastEligible
                    )
                }
            }

            gateBuildup = if (anyBlobAtGate) gateBuildup + 1 else 0

            val candidate = best ?: run {
                if (gateOccupied) gateOccupied = false
                motionDirectionHistory.clear()
                return null
            }

            val compCenterX = (candidate.comp.minX + candidate.comp.maxX) / 2f
            val movingLeftToRight = inferMotionDirection(compCenterX)
            recordMotionDirectionSample(compCenterX)

            if (gateOccupied) return null

            // 5. §23 fire gate. The remotely selectable pre-§23 profile
            // already passed its local-support gate during candidate
            // selection, so only the leading-edge profile runs this block.
            if (parameters.useLeadingEdgeTrigger) {
                val blobH = candidate.comp.height
                val normalMinRequired = max(
                    parameters.torsoRunAbsMin,
                    min(parameters.torsoRunAbsMax, (blobH * parameters.torsoRunHeightFraction).toInt())
                )
                val rawQualifyingIndices = mutableListOf<Int>()
                for (i in frameGateRunsMerged.indices) {
                    val r = frameGateRunsMerged[i]
                    if (r.endY - r.startY + 1 >= normalMinRequired) rawQualifyingIndices.add(i)
                }

                // §35 H-TEMPORAL-WAIT — drop runs whose startY sits in the
                // lower half of the blob; those are legs/hips, not torso.
                val blobMidY = candidate.comp.minY + candidate.comp.height / 2
                val upperHalfIndices = rawQualifyingIndices.filter {
                    frameGateRunsMerged[it].startY < blobMidY
                }

            // Current iOS behavior keeps the normal torso threshold for the
            // primary qualifier pass. Only when that pass is empty may a
            // low-contrast candidate use the shorter fallback threshold, and
            // even then the run must begin in the configured upper-body zone.
            // Treating every >=18px run as a normal qualifier lets leg/shadow
            // fragments fire too early on low-contrast Android cameras.
                val lowContrastUpperLimitY = candidate.comp.minY +
                    (candidate.comp.height * parameters.lowContrastBodyFallbackUpperZoneFraction).toInt()
                val lowContrastFallbackIndices = if (
                    candidate.lowContrastFallback && rawQualifyingIndices.isEmpty()
                ) {
                    frameGateRunsMerged.indices.filter { index ->
                        val run = frameGateRunsMerged[index]
                        run.length >= parameters.lowContrastBodyFallbackMinMergedRun &&
                            run.endY >= candidate.comp.minY &&
                            run.startY <= min(blobMidY, lowContrastUpperLimitY)
                    }
                } else {
                    emptyList()
                }
                val lowContrastFireFallbackEligible =
                    rawQualifyingIndices.isEmpty() && lowContrastFallbackIndices.isNotEmpty()

            // §37 H-LIMB-WAIT-RELEASE — release the suppression after
            // limbWaitReleaseAfter consecutive same-pattern suppressions.
                val wouldSuppress = rawQualifyingIndices.isNotEmpty() && upperHalfIndices.isEmpty()
                val released = wouldSuppress && limbWaitStreak >= parameters.limbWaitReleaseAfter
                val qualifyingIndices = when {
                    lowContrastFireFallbackEligible -> lowContrastFallbackIndices
                    released -> rawQualifyingIndices
                    else -> upperHalfIndices
                }

                when {
                    lowContrastFireFallbackEligible || released -> limbWaitStreak = 0
                    wouldSuppress -> limbWaitStreak++
                    else -> limbWaitStreak = 0
                }

                if (qualifyingIndices.isEmpty()) {
                    return null
                }

            // §31 head-snag picker bias — probe horizontal strip width at
            // each qualifier centerY; if topmost is much narrower than the
            // widest, move widest to the front of the picker order.
                val quals = qualifyingIndices.map { idx ->
                    val run = frameGateRunsMerged[idx]
                    val cy = (run.startY + run.endY) / 2
                    var lx = gateColumn
                    var rx = gateColumn
                    var xi = gateColumn - 1
                    while (xi >= 0 && maskBuf[cy * w + xi].toInt() != 0) { lx = xi; xi-- }
                    xi = gateColumn + 1
                    while (xi < w && maskBuf[cy * w + xi].toInt() != 0) { rx = xi; xi++ }
                    QualProbe(idx = idx, centerY = cy, width = (rx - gateColumn) + (gateColumn - lx))
                }.toMutableList()

                if (quals.size > 1) {
                    val topW = quals[0].width
                    val maxW = quals.maxOf { it.width }
                    if (maxW > 0 && topW * 2 < maxW) {
                        val widestOffset = quals.indices.maxByOrNull { quals[it].width } ?: 0
                        if (widestOffset != 0) {
                            val widest = quals.removeAt(widestOffset)
                            quals.add(0, widest)
                        }
                    }
                }

            // §29 EMPTY_STRIP_FALLBACK — pick the first qualifier whose
            // horizontal strip is non-empty at its centerY.
                var pickedIdx = -1
                for (q in quals) {
                    if (q.width > 0) {
                        pickedIdx = q.idx
                        break
                    }
                }
                if (pickedIdx < 0) return null
            }

            // §48 H-DETY-BLOB-RELATIVE — detY is a property of the body,
            // computed from the blob bbox, not from which gate-column run
            // the picker happened to select.
            val torsoDetY = candidate.comp.minY +
                (candidate.comp.height * parameters.torsoFraction).toInt()

            // 6. Position-based interpolation.
            val detRow = torsoDetY.coerceIn(0, h - 1)
            var runLeftX = gateColumn
            var runRightX = gateColumn
            var x = gateColumn - 1
            while (x >= 0 && maskBuf[detRow * w + x].toInt() != 0) { runLeftX = x; x-- }
            x = gateColumn + 1
            while (x < w && maskBuf[detRow * w + x].toInt() != 0) { runRightX = x; x++ }

            val dBefore: Float
            val dAfter: Float
            if (movingLeftToRight) {
                dBefore = (gateColumn - runLeftX).toFloat()
                dAfter = (runRightX - gateColumn).toFloat()
            } else {
                dBefore = (runRightX - gateColumn).toFloat()
                dAfter = (gateColumn - runLeftX).toFloat()
            }
            val stripWidth = dBefore + dAfter
            val hRun = runRightX - runLeftX + 1

            // §38 H-THICK-STRIP-CHECK — if single-col strip is empty,
            // probe the full thick band before rejecting.
            if (stripWidth == 0f) {
                var hasMaskInBand = false
                val tMin = max(0, gateColumn - parameters.thickGateHalfWidth)
                val tMax = min(w - 1, gateColumn + parameters.thickGateHalfWidth)
                var tx = tMin
                while (tx <= tMax) {
                    if (maskBuf[detRow * w + tx].toInt() != 0) {
                        hasMaskInBand = true
                        break
                    }
                    tx++
                }
                if (!hasMaskInBand) return null
                if (!hasGateColumnSupport(candidate.comp)) return null
            }

            val buildupAtDetection = gateBuildup
            val xAnchorProbe = xAnchorProbe(
                comp = candidate.comp,
                torsoDetY = detRow,
                movingLeftToRight = movingLeftToRight
            )
            if (shouldRejectSparseStartupSceneMotion(xAnchorProbe)) return null
            if (shouldRejectThinGateRowSceneMotion(
                    comp = candidate.comp,
                    probe = xAnchorProbe,
                    hRun = hRun,
                    stripWidth = stripWidth,
                    buildup = buildupAtDetection
                )
            ) return null

            val nowSec = timestampNanos / 1e9
            val prevSec = previousTimestampNanos / 1e9
            val dt = nowSec - prevSec
            val startSec = sessionStart / 1e9
            val baseFraction = if (stripWidth > 0f) (dBefore / stripWidth).toDouble() else 0.5
            var fraction = baseFraction
            val velocityPxPerSec = if (dt > 0.0 && stripWidth > 0f) {
                (stripWidth / dt).toFloat()
            } else {
                0f
            }

            var crossingTime = prevSec + fraction * dt - startSec
            var runtimeDisplayX: Float? = null
            var runtimeRule: String? = null

            val baseCurrentX = xAnchorCurrentX(
                movingLeftToRight = movingLeftToRight,
                dBefore = dBefore,
                dAfter = dAfter,
                fraction = baseFraction
            )
            val runtimeCandidates = xAnchorCandidateSlate(
                comp = candidate.comp,
                currentX = baseCurrentX,
                runLeftX = runLeftX,
                runRightX = runRightX,
                movingLeftToRight = movingLeftToRight,
                dBefore = dBefore,
                fraction = baseFraction,
                dt = dt
            )
            val runtimeProposal = proposedXAnchor(
                candidates = runtimeCandidates,
                probe = xAnchorProbe,
                comp = candidate.comp,
                stripWidth = stripWidth,
                movingLeftToRight = movingLeftToRight
            )
            val hasValidBodySupport = runtimeCandidates.any { anchor ->
                anchor.valid == "Y" && anchor.name in VALID_BODY_CANDIDATE_NAMES
            }
            val torsoFragmentCount = gateColumnFragmentCount(candidate.comp)
            if (parameters.incoherentSceneMotionGuardEnabled && shouldRejectIncoherentSceneMotion(
                    blobWidth = candidate.comp.width,
                    processWidth = w,
                    noTorsoSupport = xAnchorProbe.frontTorsoX < 0,
                    hasValidBodySupport = hasValidBodySupport,
                    gateRowWidth = xAnchorProbe.wAtDetY,
                    stripWidth = stripWidth,
                    horizontalRun = hRun,
                    torsoFragmentCount = torsoFragmentCount,
                    parameters = parameters
                )
            ) {
                Log.d(
                    TAG,
                    "[REPLICA] REJECT: incoherent_scene_motion_no_torso " +
                        "width=${candidate.comp.width}/$w gateWidth=${xAnchorProbe.wAtDetY} " +
                        "strip=$stripWidth hRun=$hRun fragments=$torsoFragmentCount"
                )
                return null
            }
            if (shouldRejectNoTorsoTinyGateRowAccept(
                    proposal = runtimeProposal,
                    probe = xAnchorProbe,
                    hRun = hRun,
                    stripWidth = stripWidth,
                    blobHeightFraction = candidate.comp.height.toFloat() / h
                )
            ) return null
            if (futureBodyTorsoWaitCandidate(
                    candidates = runtimeCandidates,
                    proposal = runtimeProposal,
                    probe = xAnchorProbe,
                    stripWidth = stripWidth
                ) != null
            ) return null
            if (futureTorsoGateRowWaitCandidate(
                    candidates = runtimeCandidates,
                    proposal = runtimeProposal,
                    probe = xAnchorProbe,
                    stripWidth = stripWidth
                ) != null
            ) return null
            if (noTorsoFutureBodyFrontLTRDefer(
                    candidates = runtimeCandidates,
                    proposal = runtimeProposal,
                    probe = xAnchorProbe,
                    movingLeftToRight = movingLeftToRight
                )
            ) {
                return null
            }
            if (xAnchorSelectorV3FutureWaitCandidate(
                    candidates = runtimeCandidates,
                    movingLeftToRight = movingLeftToRight
                ) != null
            ) return null

            val adjustment = nearGateDetFrontRuntimeAdjustment(
                candidates = runtimeCandidates,
                proposal = runtimeProposal,
                probe = xAnchorProbe,
                stripWidth = stripWidth
            ) ?: waitForTorsoFarRuntimeAdjustment(
                candidates = runtimeCandidates,
                proposal = runtimeProposal,
                probe = xAnchorProbe
            ) ?: zeroGateRowBodyFrontR2LRuntimeAdjustment(
                candidates = runtimeCandidates,
                proposal = runtimeProposal,
                probe = xAnchorProbe,
                stripWidth = stripWidth,
                movingLeftToRight = movingLeftToRight
            ) ?: strongPastTorsoFrontRuntimeAdjustment(
                candidates = runtimeCandidates,
                proposal = runtimeProposal,
                probe = xAnchorProbe,
                stripWidth = stripWidth
            ) ?: xAnchorSelectorV3RuntimeAdjustment(
                candidates = runtimeCandidates,
                probe = xAnchorProbe,
                dBefore = dBefore,
                movingLeftToRight = movingLeftToRight
            )
            if (adjustment != null) {
                fraction = adjustment.fraction.toDouble()
                crossingTime = prevSec + fraction * dt - startSec
                runtimeDisplayX = adjustment.x
                runtimeRule = adjustment.rule
            }

            // Low-light exposure correction (>2 ms). Applied after any x-anchor
            // adjustment so the final timestamp matches the iOS engine.
            crossingTime += exposureCorrectionSeconds(exposureNanos ?: previousExposureNanos)

            // Convert back to absolute monotonic timestamp for downstream
            // consumers that work in monotonic-nanos terms (UI, Supabase).
            val crossingTimestampNanos = sessionStart + (crossingTime * 1e9).toLong()

            lastDetectionRealElapsedNanos = elapsedNanos
            gateOccupied = true
            gateBuildup = 0

            return Result(
                crossingTimeSeconds = crossingTime,
                crossingTimestampNanos = crossingTimestampNanos,
                interpolationFraction = fraction,
                dBeforePx = dBefore,
                dAfterPx = dAfter,
                movingLeftToRight = movingLeftToRight,
                velocityPxPerSec = velocityPxPerSec,
                gateY = torsoDetY,
                componentBoundsNorm = NormalizedRect(
                    x = candidate.comp.minX.toFloat() / w,
                    y = candidate.comp.minY.toFloat() / h,
                    width = candidate.comp.width.toFloat() / w,
                    height = candidate.comp.height.toFloat() / h
                ),
                blobHeightFraction = candidate.comp.height.toFloat() / h,
                blobCenterXFraction = compCenterX / w,
                buildupFrames = buildupAtDetection,
                xAnchorRuntimeDisplayX = runtimeDisplayX,
                xAnchorRuntimeRule = runtimeRule
            )
        } finally {
            usingA = !usingA
            previousTimestampNanos = timestampNanos
            previousExposureNanos = exposureNanos
            hasPrevious = true
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /**
     * Downsample `src` from full-resolution into the engine's working
     * portrait buffer. When [transpose] is true (Y plane arrives landscape)
     * source x and y axes are swapped on the way in.
     */
    private fun extractGray(
        src: ByteArray,
        fullW: Int,
        fullH: Int,
        bpr: Int,
        transpose: Boolean,
        dest: ByteArray
    ) {
        val w = processWidth
        val h = processHeight
        val sx = scaleX
        val sy = scaleY
        if (transpose) {
            for (ty in 0 until h) {
                val bufX = ty * sy
                if (bufX >= fullW) continue
                for (tx in 0 until w) {
                    val bufY = tx * sx
                    if (bufY >= fullH) continue
                    val srcIdx = bufY * bpr + bufX
                    if (srcIdx < src.size) dest[ty * w + tx] = src[srcIdx]
                }
            }
        } else {
            for (ty in 0 until h) {
                val rowOff = ty * sy * bpr
                if (rowOff >= src.size) continue
                for (tx in 0 until w) {
                    val srcIdx = rowOff + tx * sx
                    if (srcIdx < src.size) dest[ty * w + tx] = src[srcIdx]
                }
            }
        }
    }

    /**
     * Pre-§23 iOS local-support gate used when `useLeadingEdgeTrigger` is
     * remotely disabled. It finds the longest gap-merged vertical run in
     * each gate-band column and accepts any adjacent 3-column window whose
     * average reaches the fixed 8% frame-height floor.
     */
    private fun hasLegacyLocalGateSupport(comp: Component, gMin: Int, gMax: Int): Boolean {
        val columns = (gMin..gMax).filter { it in 0 until processWidth }
        if (columns.isEmpty()) return false

        val columnRuns = IntArray(columns.size)
        columns.forEachIndexed { index, column ->
            var runLength = 0
            var gapLength = 0
            var bestLength = 0
            for (y in comp.minY..comp.maxY) {
                if (maskBuf[y * processWidth + column].toInt() != 0) {
                    runLength += 1 + gapLength
                    gapLength = 0
                } else if (runLength > 0 && gapLength < parameters.gateRunMergeMaxGap) {
                    gapLength++
                } else {
                    bestLength = max(bestLength, runLength)
                    runLength = 0
                    gapLength = 0
                }
            }
            columnRuns[index] = max(bestLength, runLength)
        }

        val windowWidth = min(LEGACY_GATE_SLICE_WIDTH, columnRuns.size)
        val minimumSupport = max(3, (processHeight * LEGACY_MIN_GATE_HEIGHT_FRACTION).toInt())
        for (start in 0..columnRuns.size - windowWidth) {
            var total = 0
            for (index in start until start + windowWidth) total += columnRuns[index]
            if (total / windowWidth >= minimumSupport) return true
        }
        return false
    }

    /**
     * §34 H-THICK-GATE-PROJECTION — return contiguous Y runs of the
     * OR-projection across (gateColumn ± halfWidth). A row is active iff
     * any column in the band has a mask pixel at that row.
     */
    private fun gateColumnRunsThick(halfWidth: Int): List<Run> {
        val w = processWidth
        val h = processHeight
        val xMin = max(0, gateColumn - halfWidth)
        val xMax = min(w - 1, gateColumn + halfWidth)
        val runs = ArrayList<Run>(8)
        var rs = -1
        for (y in 0 until h) {
            var active = false
            var x = xMin
            while (x <= xMax) {
                if (maskBuf[y * w + x].toInt() != 0) { active = true; break }
                x++
            }
            if (active) {
                if (rs < 0) rs = y
            } else if (rs >= 0) {
                runs.add(Run(rs, y - 1))
                rs = -1
            }
        }
        if (rs >= 0) runs.add(Run(rs, h - 1))
        return runs
    }

    private fun gateBandOccupancy(halfWidth: Int): Float {
        val metrics = gateBandMetrics(halfWidth)
        return if (metrics.totalPixels > 0) {
            metrics.pixels.toFloat() / metrics.totalPixels.toFloat()
        } else {
            0f
        }
    }

    private fun gateBandMetrics(halfWidth: Int): GateBandMetrics {
        val w = processWidth
        val h = processHeight
        val xMin = max(0, gateColumn - halfWidth)
        val xMax = min(w - 1, gateColumn + halfWidth)
        val total = (xMax - xMin + 1) * h
        if (total <= 0) return GateBandMetrics()

        var active = 0
        var activeColumns = 0
        for (x in xMin..xMax) {
            var columnActive = false
            for (y in 0 until h) {
                if (maskBuf[y * w + x].toInt() != 0) {
                    active++
                    columnActive = true
                }
            }
            if (columnActive) activeColumns++
        }
        return GateBandMetrics(columns = activeColumns, pixels = active, totalPixels = total)
    }

    private fun lowContrastBodyFallbackEligible(
        comp: Component,
        mergedRuns: List<Run>,
        gateBand: GateBandMetrics,
        sequenceFrames: Int
    ): Boolean {
        if (!parameters.lowContrastBodyFallbackEnabled) return false
        if (sequenceFrames < parameters.lowContrastBodyFallbackMinSequenceFrames) return false
        if (gateBand.columns < parameters.lowContrastBodyFallbackMinGateBandColumns) return false
        if (gateBand.pixels < parameters.lowContrastBodyFallbackMinGateBandPixels) return false
        val heightFraction = comp.height.toFloat() / processHeight
        val widthFraction = comp.width.toFloat() / processWidth
        if (heightFraction < parameters.lowContrastBodyFallbackMinBlobHeightFraction) return false
        if (widthFraction !in parameters.lowContrastBodyFallbackMinBlobWidthFraction..
            parameters.lowContrastBodyFallbackMaxBlobWidthFraction
        ) return false

        val upperLimitY = comp.minY +
            (comp.height * parameters.lowContrastBodyFallbackUpperZoneFraction).toInt()
        return mergedRuns.any { run ->
            run.endY >= comp.minY &&
                run.startY <= comp.maxY &&
                run.startY <= upperLimitY &&
                run.length >= parameters.lowContrastBodyFallbackMinLocalRun
        }
    }

    private fun inferMotionDirection(currentCenterX: Float): Boolean {
        val oldestAllowedFrame = frameIndex - parameters.motionDirectionHistoryMaxAgeFrames
        var bestDelta = 0f
        for (sample in motionDirectionHistory) {
            if (sample.frameIndex < oldestAllowedFrame) continue
            val delta = currentCenterX - sample.centerX
            if (abs(delta) > abs(bestDelta)) bestDelta = delta
        }
        return inferMovingLeftToRight(
            currentCenterX = currentCenterX,
            gateColumn = gateColumn.toFloat(),
            strongestHistoricalDelta = bestDelta,
            minimumDeltaPx = parameters.motionDirectionMinDeltaPixels
        )
    }

    private fun recordMotionDirectionSample(centerX: Float) {
        motionDirectionHistory.add(
            MotionDirectionSample(
                frameIndex = frameIndex,
                centerX = centerX
            )
        )
        val oldestAllowedFrame = frameIndex - parameters.motionDirectionHistoryMaxAgeFrames
        motionDirectionHistory.removeAll { it.frameIndex < oldestAllowedFrame }
        while (motionDirectionHistory.size > parameters.motionDirectionHistoryLength) {
            motionDirectionHistory.removeAt(0)
        }
    }

    /** §25 merge adjacent runs with gap ≤ maxGap. */
    private fun mergeRuns(input: List<Run>, maxGap: Int): List<Run> {
        if (input.size < 2) return input
        val merged = ArrayList<Run>(input.size)
        var curStart = input[0].startY
        var curEnd = input[0].endY
        for (i in 1 until input.size) {
            val gap = input[i].startY - curEnd - 1
            if (gap <= maxGap) {
                curEnd = input[i].endY
            } else {
                merged.add(Run(curStart, curEnd))
                curStart = input[i].startY
                curEnd = input[i].endY
            }
        }
        merged.add(Run(curStart, curEnd))
        return merged
    }

    /** iOS §38 support check for thick-band empty-strip fallback. */
    private fun hasGateColumnSupport(comp: Component): Boolean {
        val minY = max(comp.minY, 0)
        val maxY = min(comp.maxY, processHeight - 1)
        if (minY > maxY) return false
        for (y in minY..maxY) {
            if (maskBuf[y * processWidth + gateColumn].toInt() != 0) return true
        }
        return false
    }

    private fun shouldRejectSparseStartupSceneMotion(probe: XAnchorProbe): Boolean {
        return frameIndex <= parameters.sparseStartupSceneMotionFrameLimit &&
            probe.frontTorsoX < 0 &&
            probe.wAtDetY <= parameters.sparseStartupSceneMotionMaxGateWidth
    }

    private fun shouldRejectThinGateRowSceneMotion(
        comp: Component,
        probe: XAnchorProbe,
        hRun: Int,
        stripWidth: Float,
        buildup: Int
    ): Boolean {
        val tallBlob = comp.height.toFloat() / processHeight >=
            parameters.thinSceneMotionMinBlobHeightFraction
        val separatedTorsoProbe = probe.frontTorsoX < 0 ||
            probe.signedDistanceFromGate >= parameters.thinSceneMotionMinTorsoDistance

        return buildup <= parameters.thinSceneMotionMaxBuildup &&
            stripWidth <= parameters.thinSceneMotionMaxStripWidth &&
            hRun <= parameters.thinSceneMotionMaxHorizontalRun &&
            probe.wAtDetY <= parameters.thinSceneMotionMaxGateWidth &&
            tallBlob &&
            separatedTorsoProbe
    }

    private fun gateColumnFragmentCount(comp: Component): Int {
        val minY = max(comp.minY, 0)
        val maxY = min(comp.maxY, processHeight - 1)
        if (minY > maxY) return 0

        var count = 0
        var inRun = false
        for (y in minY..maxY) {
            val hasMask = maskBuf[y * processWidth + gateColumn].toInt() != 0
            if (hasMask && !inRun) count++
            inRun = hasMask
        }
        return count
    }

    private fun xAnchorProbe(
        comp: Component,
        torsoDetY: Int,
        movingLeftToRight: Boolean
    ): XAnchorProbe {
        val minX = max(comp.minX, 0)
        val maxX = min(comp.maxX, processWidth - 1)
        val minY = max(comp.minY, 0)
        val maxY = min(comp.maxY, processHeight - 1)
        if (minX > maxX || minY > maxY) {
            return XAnchorProbe(
                frontTorsoX = -1,
                frontTorsoRun = 0,
                torsoRunMin = 20,
                signedDistanceFromGate = -999,
                wAtDetY = localGateWidth(torsoDetY)
            )
        }

        val blobH = maxY - minY + 1
        val torsoRunMin = max(20, blobH / 3)
        var frontTorsoX = -1
        var frontTorsoRun = 0
        val xRange = if (movingLeftToRight) maxX downTo minX else minX..maxX

        for (x in xRange) {
            var currentRun = 0
            var longestRun = 0
            for (y in minY..maxY) {
                if (maskBuf[y * processWidth + x].toInt() != 0) {
                    currentRun++
                    if (currentRun > longestRun) longestRun = currentRun
                } else {
                    currentRun = 0
                }
            }
            if (longestRun >= torsoRunMin) {
                frontTorsoX = x
                frontTorsoRun = longestRun
                break
            }
        }

        val signedDistance = if (frontTorsoX >= 0) {
            val rawDistance = frontTorsoX - gateColumn
            if (movingLeftToRight) rawDistance else -rawDistance
        } else {
            -999
        }

        return XAnchorProbe(
            frontTorsoX = frontTorsoX,
            frontTorsoRun = frontTorsoRun,
            torsoRunMin = torsoRunMin,
            signedDistanceFromGate = signedDistance,
            wAtDetY = localGateWidth(torsoDetY)
        )
    }

    private fun localGateWidth(row: Int): Int {
        if (row !in 0 until processHeight ||
            maskBuf[row * processWidth + gateColumn].toInt() == 0
        ) {
            return 0
        }

        var lx = gateColumn
        var rx = gateColumn
        var x = gateColumn - 1
        while (x >= 0 && maskBuf[row * processWidth + x].toInt() != 0) {
            lx = x
            x--
        }
        x = gateColumn + 1
        while (x < processWidth && maskBuf[row * processWidth + x].toInt() != 0) {
            rx = x
            x++
        }
        return rx - lx + 1
    }

    private fun xAnchorCurrentX(
        movingLeftToRight: Boolean,
        dBefore: Float,
        dAfter: Float,
        fraction: Double
    ): Float {
        val gateX = gateColumn.toFloat()
        val usedPreviousFrame = fraction < 0.5
        return if (movingLeftToRight) {
            if (usedPreviousFrame) gateX - dBefore else gateX + dAfter
        } else {
            if (usedPreviousFrame) gateX + dBefore else gateX - dAfter
        }
    }

    private fun xAnchorCandidateSlate(
        comp: Component,
        currentX: Float,
        runLeftX: Int,
        runRightX: Int,
        movingLeftToRight: Boolean,
        dBefore: Float,
        fraction: Double,
        dt: Double
    ): List<XAnchorCandidate> {
        val detFrontX = if (movingLeftToRight) runRightX else runLeftX
        val detRearX = if (movingLeftToRight) runLeftX else runRightX
        val detCenterX = (runLeftX + runRightX).toFloat() / 2f
        val bboxFrontX = if (movingLeftToRight) comp.maxX else comp.minX
        val bboxRearX = if (movingLeftToRight) comp.minX else comp.maxX
        val bboxCenterX = (comp.minX + comp.maxX).toFloat() / 2f

        val blobH = max(0, comp.height)
        val looseMin = max(12, blobH / 5)
        val mediumMin = max(16, blobH / 4)
        val strictMin = max(20, blobH / 3)
        val loose = torsoColumnProfile(comp, looseMin, movingLeftToRight)
        val medium = torsoColumnProfile(comp, mediumMin, movingLeftToRight)
        val strict = torsoColumnProfile(comp, strictMin, movingLeftToRight)
        val bodyFront = bodyFrontSubstantialProfile(comp, movingLeftToRight)

        return listOf(
            xAnchorCandidate("current", currentX, movingLeftToRight, dBefore, fraction, dt),
            xAnchorCandidate("detFront", detFrontX.toFloat(), movingLeftToRight, dBefore, fraction, dt),
            xAnchorCandidate("detCenter", detCenterX, movingLeftToRight, dBefore, fraction, dt),
            xAnchorCandidate("detRear", detRearX.toFloat(), movingLeftToRight, dBefore, fraction, dt),
            xAnchorCandidate("bboxFront", bboxFrontX.toFloat(), movingLeftToRight, dBefore, fraction, dt),
            xAnchorCandidate("bboxCenter", bboxCenterX, movingLeftToRight, dBefore, fraction, dt),
            xAnchorCandidate("bboxRear", bboxRearX.toFloat(), movingLeftToRight, dBefore, fraction, dt),
            xAnchorCandidate(
                "torsoFrontLoose",
                loose.frontX?.toFloat(),
                movingLeftToRight,
                dBefore,
                fraction,
                dt,
                minRun = looseMin,
                supportRun = loose.frontRun,
                supportColumns = loose.count,
                supportSpreadPx = loose.spread
            ),
            xAnchorCandidate(
                "torsoFrontMed",
                medium.frontX?.toFloat(),
                movingLeftToRight,
                dBefore,
                fraction,
                dt,
                minRun = mediumMin,
                supportRun = medium.frontRun,
                supportColumns = medium.count,
                supportSpreadPx = medium.spread
            ),
            xAnchorCandidate(
                "torsoCenterMed",
                medium.centerX?.toFloat(),
                movingLeftToRight,
                dBefore,
                fraction,
                dt,
                minRun = mediumMin,
                supportRun = medium.centerRun,
                supportColumns = medium.count,
                supportSpreadPx = medium.spread
            ),
            xAnchorCandidate(
                "torsoRearMed",
                medium.rearX?.toFloat(),
                movingLeftToRight,
                dBefore,
                fraction,
                dt,
                minRun = mediumMin,
                supportRun = medium.rearRun,
                supportColumns = medium.count,
                supportSpreadPx = medium.spread
            ),
            xAnchorCandidate(
                "torsoFrontStrict",
                strict.frontX?.toFloat(),
                movingLeftToRight,
                dBefore,
                fraction,
                dt,
                minRun = strictMin,
                supportRun = strict.frontRun,
                supportColumns = strict.count,
                supportSpreadPx = strict.spread
            ),
            xAnchorCandidate(
                "bodyFrontSubstantial",
                bodyFront.frontX?.toFloat(),
                movingLeftToRight,
                dBefore,
                fraction,
                dt,
                supportRows = bodyFront.supportRows,
                supportRowSpanPx = bodyFront.supportRowSpanPx,
                bodyWidthRefPx = bodyFront.bodyWidthRefPx,
                bodyWidthMedianPx = bodyFront.bodyWidthMedianPx,
                frontSpreadPx = bodyFront.frontSpreadPx,
                rejectReason = bodyFront.rejectReason
            )
        )
    }

    private fun proposedXAnchor(
        candidates: List<XAnchorCandidate>,
        probe: XAnchorProbe,
        comp: Component,
        stripWidth: Float,
        movingLeftToRight: Boolean
    ): XAnchorProposal {
        val current = candidates.firstOrNull { it.name == "current" }

        fun proposal(
            candidate: XAnchorCandidate?,
            name: String? = null,
            valid: String? = null,
            reason: String
        ): XAnchorProposal = XAnchorProposal(
            name = name ?: candidate?.name ?: "current",
            x = candidate?.x,
            fraction = candidate?.fraction,
            valid = valid ?: candidate?.valid ?: "current",
            reason = reason
        )

        val torsoDist = probe.signedDistanceFromGate
        if (torsoDist in 0..8) {
            candidates.firstOrNull { it.name == "torsoFrontStrict" && it.valid == "Y" }
                ?.let { return proposal(it, reason = "strict_torso_near_gate_${torsoDist}px") }
            candidates.firstOrNull { it.name == "torsoFrontMed" && it.valid == "Y" }
                ?.let { return proposal(it, reason = "medium_torso_near_gate_${torsoDist}px") }
        }

        val normalizedAspect = (comp.width.toFloat() / processWidth.toFloat()) /
            max(comp.height.toFloat() / processHeight.toFloat(), 0.001f)
        val futureTorsoSupport = probe.frontTorsoX >= 0 && torsoDist < -10
        val blurOrThinAnchorSymptom = stripWidth <= 8f ||
            probe.wAtDetY <= 8 ||
            normalizedAspect >= 1.20f
        if (futureTorsoSupport && blurOrThinAnchorSymptom) {
            val futureTorso = candidates.firstOrNull {
                (it.name == "torsoFrontLoose" ||
                    it.name == "torsoFrontMed" ||
                    it.name == "torsoFrontStrict") &&
                    it.valid == "future" &&
                    it.x != null
            }
            return proposal(
                futureTorso,
                name = "waitForTorso",
                valid = "wait",
                reason = "torso_future_${if (movingLeftToRight) "LtoR" else "RtoL"}"
            )
        }

        return when {
            probe.frontTorsoX < 0 ->
                proposal(current, reason = "keep_current_no_torso_support")
            torsoDist < -10 ->
                proposal(current, reason = "keep_current_torso_future_control")
            else ->
                proposal(current, reason = "keep_current_stable")
        }
    }

    private fun shouldRejectNoTorsoTinyGateRowAccept(
        proposal: XAnchorProposal,
        probe: XAnchorProbe,
        hRun: Int,
        stripWidth: Float,
        blobHeightFraction: Float
    ): Boolean {
        return parameters.noTorsoTinyGateRowGuardEnabled &&
            proposal.name == "current" &&
            proposal.reason == "keep_current_no_torso_support" &&
            probe.frontTorsoX < 0 &&
            probe.wAtDetY <= parameters.noTorsoTinyGateRowMaxGateWidth &&
            stripWidth <= parameters.noTorsoTinyGateRowMaxStripWidth &&
            hRun <= parameters.noTorsoTinyGateRowMaxHorizontalRun &&
            blobHeightFraction >= parameters.noTorsoTinyGateRowMinBlobHeightFraction
    }

    private fun supportedFutureBodyFrontCandidate(
        candidates: List<XAnchorCandidate>
    ): XAnchorCandidate? {
        val body = candidates.firstOrNull { it.name == "bodyFrontSubstantial" } ?: return null
        val supportRows = body.supportRows ?: return null
        val supportRowSpan = body.supportRowSpanPx ?: return null
        val widthRef = body.bodyWidthRefPx ?: return null
        val frontSpread = body.frontSpreadPx ?: return null
        if (body.valid != "future" || body.x == null ||
            supportRows < FUTURE_BODY_TORSO_WAIT_MIN_BODY_ROWS ||
            supportRowSpan < FUTURE_BODY_TORSO_WAIT_MIN_BODY_ROW_SPAN_PX ||
            widthRef < FUTURE_BODY_TORSO_WAIT_MIN_BODY_WIDTH_REF_PX
        ) return null
        val maxSpread = max(
            FUTURE_BODY_TORSO_WAIT_MAX_BODY_FRONT_SPREAD_PX,
            ceil(widthRef * FUTURE_BODY_TORSO_WAIT_BODY_SPREAD_REF_MULTIPLIER).toInt()
        )
        return body.takeIf { frontSpread <= maxSpread }
    }

    private fun supportedFutureTorsoFrontCandidate(
        candidates: List<XAnchorCandidate>
    ): XAnchorCandidate? {
        return candidates.firstOrNull { candidate ->
            candidate.name in TORSO_FRONT_CANDIDATE_NAMES &&
                candidate.valid == "future" &&
                candidate.x != null &&
                (candidate.supportRun ?: 0) >= FUTURE_BODY_TORSO_WAIT_MIN_SUPPORT_RUN &&
                (candidate.supportColumns ?: 0) >= FUTURE_BODY_TORSO_WAIT_MIN_SUPPORT_COLUMNS
        }
    }

    private fun supportedSameFrameLooseTorsoCandidate(
        candidates: List<XAnchorCandidate>
    ): XAnchorCandidate? {
        return candidates.firstOrNull { candidate ->
            candidate.name == "torsoFrontLoose" &&
                candidate.valid == "Y" &&
                candidate.x != null &&
                candidate.fraction?.let { it.isFinite() && it in 0f..1f } == true &&
                (candidate.supportColumns ?: 0) >= 10
        }
    }

    private fun futureBodyTorsoWaitCandidate(
        candidates: List<XAnchorCandidate>,
        proposal: XAnchorProposal,
        probe: XAnchorProbe,
        stripWidth: Float
    ): XAnchorCandidate? {
        if (probe.signedDistanceFromGate > -FUTURE_BODY_TORSO_WAIT_MIN_DISTANCE_PX) return null
        val futureBody = supportedFutureBodyFrontCandidate(candidates)
        val futureTorso = supportedFutureTorsoFrontCandidate(candidates)
        if (futureBody == null && futureTorso == null) return null

        if (proposal.name == "waitForTorso" && proposal.valid == "wait") {
            if (probe.wAtDetY > FUTURE_BODY_TORSO_WAIT_MAX_GATE_WIDTH ||
                stripWidth > FUTURE_BODY_TORSO_WAIT_MAX_STRIP_WIDTH
            ) return null
            if (futureBody != null) return futureBody
            if (supportedSameFrameLooseTorsoCandidate(candidates) != null) return null
            return futureTorso
        }
        if (proposal.name == "current" &&
            proposal.reason == "keep_current_torso_future_control" &&
            probe.wAtDetY <= FUTURE_BODY_TORSO_CONTROL_WAIT_MAX_GATE_WIDTH &&
            stripWidth <= FUTURE_BODY_TORSO_CONTROL_WAIT_MAX_STRIP_WIDTH
        ) return futureTorso
        return null
    }

    private fun futureTorsoGateRowWaitCandidate(
        candidates: List<XAnchorCandidate>,
        proposal: XAnchorProposal,
        probe: XAnchorProbe,
        stripWidth: Float
    ): XAnchorCandidate? {
        if (proposal.name != "waitForTorso" || proposal.valid != "wait" ||
            probe.wAtDetY > FUTURE_TORSO_GATE_ROW_MAX_GATE_WIDTH ||
            stripWidth > FUTURE_TORSO_GATE_ROW_MAX_STRIP_WIDTH ||
            abs(probe.signedDistanceFromGate) < FUTURE_TORSO_GATE_ROW_MIN_DISTANCE_PX
        ) return null
        return candidates.firstOrNull { candidate ->
            candidate.name in TORSO_FRONT_CANDIDATE_NAMES &&
                candidate.valid == "future" &&
                candidate.x != null &&
                (candidate.supportRun ?: 0) >= FUTURE_TORSO_GATE_ROW_MIN_SUPPORT_RUN &&
                (candidate.supportColumns ?: 0) >= FUTURE_TORSO_GATE_ROW_MIN_SUPPORT_COLUMNS
        }
    }

    private fun xAnchorSelectorV3FutureWaitCandidate(
        candidates: List<XAnchorCandidate>,
        movingLeftToRight: Boolean
    ): XAnchorCandidate? {
        if (!parameters.xAnchorSelectorV3Enabled) return null
        val currentX = candidates.firstOrNull { it.name == "current" }?.x ?: return null
        val loose = candidates.firstOrNull { it.name == "torsoFrontLoose" } ?: return null
        val anchorX = loose.x ?: return null
        if (loose.valid != "future" || !anchorX.isFinite() ||
            (loose.supportColumns ?: 0) < 8 ||
            (loose.supportRun ?: 0) < 40 ||
            (loose.supportSpreadPx ?: Int.MAX_VALUE) > 45
        ) return null
        val shift = xAnchorSignedShiftPct(anchorX, currentX, movingLeftToRight)
        return loose.takeIf { shift in 3f..4.8f }
    }

    private fun noTorsoFutureBodyFrontLTRDefer(
        candidates: List<XAnchorCandidate>,
        proposal: XAnchorProposal,
        probe: XAnchorProbe,
        movingLeftToRight: Boolean
    ): Boolean {
        if (!movingLeftToRight ||
            proposal.name != "current" ||
            proposal.reason != "keep_current_no_torso_support" ||
            probe.frontTorsoX >= 0
        ) return false

        val currentX = candidates.firstOrNull { it.name == "current" }?.x ?: return false
        val body = candidates.firstOrNull { it.name == "bodyFrontSubstantial" } ?: return false
        val anchorX = body.x ?: return false
        val supportRows = body.supportRows ?: return false
        val supportRowSpanPx = body.supportRowSpanPx ?: return false
        val bodyWidthRefPx = body.bodyWidthRefPx ?: return false
        val frontSpreadPx = body.frontSpreadPx ?: return false
        if (body.valid != "future" ||
            !anchorX.isFinite() ||
            anchorX !in 0f..1f ||
            supportRows < NO_TORSO_BODY_FRONT_MIN_ROWS ||
            supportRowSpanPx < NO_TORSO_BODY_FRONT_MIN_ROW_SPAN_PX ||
            bodyWidthRefPx < 8
        ) return false

        val maxFrontSpreadPx = max(
            NO_TORSO_FUTURE_BODY_FRONT_MAX_FRONT_SPREAD_PX,
            ceil(bodyWidthRefPx * NO_TORSO_FUTURE_BODY_FRONT_SPREAD_REF_MULTIPLIER).toInt()
        )
        if (frontSpreadPx > maxFrontSpreadPx) return false

        val behindPx = (currentX - anchorX) * processWidth.toFloat()
        return behindPx >= NO_TORSO_FUTURE_BODY_FRONT_MIN_BEHIND_PX &&
            behindPx <= NO_TORSO_FUTURE_BODY_FRONT_MAX_BEHIND_PX
    }

    private fun nearGateDetFrontRuntimeAdjustment(
        candidates: List<XAnchorCandidate>,
        proposal: XAnchorProposal,
        probe: XAnchorProbe,
        stripWidth: Float
    ): XAnchorRuntimeAdjustment? {
        if (proposal.name != "torsoFrontStrict" ||
            proposal.valid != "Y" ||
            probe.signedDistanceFromGate < 0 ||
            probe.signedDistanceFromGate > NEAR_GATE_TORSO_FRONT_MAX_DISTANCE_PX ||
            probe.wAtDetY < NEAR_GATE_TORSO_FRONT_MIN_GATE_WIDTH ||
            stripWidth < NEAR_GATE_TORSO_FRONT_MIN_STRIP_WIDTH
        ) return null

        val detFront = candidates.firstOrNull { it.name == "detFront" } ?: return null
        return candidateAdjustment(
            rule = "xAnchorNearGateDetFront",
            candidate = detFront
        )
    }

    private fun waitForTorsoFarRuntimeAdjustment(
        candidates: List<XAnchorCandidate>,
        proposal: XAnchorProposal,
        probe: XAnchorProbe
    ): XAnchorRuntimeAdjustment? {
        if (proposal.name != "waitForTorso" ||
            proposal.valid != "wait" ||
            probe.signedDistanceFromGate > -WAIT_FOR_TORSO_SAME_FRAME_MIN_DISTANCE_PX ||
            supportedFutureBodyFrontCandidate(candidates) != null
        ) return null

        val loose = candidates.firstOrNull { it.name == "torsoFrontLoose" } ?: return null
        val supportColumns = loose.supportColumns ?: return null
        if (loose.valid != "Y" || supportColumns < 10) return null
        return candidateAdjustment(
            rule = "xAnchorWaitForTorsoFar",
            candidate = loose
        )
    }

    private fun zeroGateRowBodyFrontR2LRuntimeAdjustment(
        candidates: List<XAnchorCandidate>,
        proposal: XAnchorProposal,
        probe: XAnchorProbe,
        stripWidth: Float,
        movingLeftToRight: Boolean
    ): XAnchorRuntimeAdjustment? {
        if (movingLeftToRight ||
            proposal.name != "current" ||
            proposal.reason != "keep_current_stable" ||
            probe.signedDistanceFromGate < ZERO_GATE_ROW_BODY_FRONT_R2L_MIN_TORSO_PAST_PX ||
            probe.wAtDetY > ZERO_GATE_ROW_BODY_FRONT_R2L_MAX_GATE_WIDTH ||
            stripWidth > ZERO_GATE_ROW_BODY_FRONT_R2L_MAX_STRIP_WIDTH
        ) return null
        val currentX = candidates.firstOrNull { it.name == "current" }?.x ?: return null
        val body = candidates.firstOrNull { it.name == "bodyFrontSubstantial" } ?: return null
        val anchorX = body.x ?: return null
        val supportRows = body.supportRows ?: return null
        val supportRowSpan = body.supportRowSpanPx ?: return null
        val widthRef = body.bodyWidthRefPx ?: return null
        val frontSpread = body.frontSpreadPx ?: return null
        if (body.valid != "Y" || supportRows < NO_TORSO_BODY_FRONT_MIN_ROWS ||
            supportRowSpan < NO_TORSO_BODY_FRONT_MIN_ROW_SPAN_PX || widthRef < 8
        ) return null
        val maxSpread = max(
            24,
            ceil(widthRef * ZERO_GATE_ROW_BODY_FRONT_R2L_SPREAD_REF_MULTIPLIER).toInt()
        )
        val aheadPx = (currentX - anchorX) * processWidth
        if (frontSpread > maxSpread ||
            aheadPx !in ZERO_GATE_ROW_BODY_FRONT_R2L_MIN_AHEAD_PX..
                ZERO_GATE_ROW_BODY_FRONT_R2L_MAX_AHEAD_PX
        ) return null
        return candidateAdjustment("xAnchorZeroGateBodyFrontR2L", body)
    }

    private fun noTorsoValidBodyFrontLTRRuntimeAdjustment(
        candidates: List<XAnchorCandidate>,
        proposal: XAnchorProposal,
        probe: XAnchorProbe,
        stripWidth: Float,
        movingLeftToRight: Boolean
    ): XAnchorRuntimeAdjustment? {
        if (!movingLeftToRight ||
            proposal.name != "current" ||
            proposal.reason != "keep_current_no_torso_support" ||
            probe.frontTorsoX >= 0 ||
            probe.wAtDetY > NO_TORSO_VALID_BODY_FRONT_LTR_MAX_GATE_WIDTH ||
            stripWidth > NO_TORSO_VALID_BODY_FRONT_LTR_MAX_STRIP_WIDTH
        ) return null

        val currentX = candidates.firstOrNull { it.name == "current" }?.x ?: return null
        val body = candidates.firstOrNull { it.name == "bodyFrontSubstantial" } ?: return null
        val anchorX = body.x ?: return null
        val supportRows = body.supportRows ?: return null
        val supportRowSpanPx = body.supportRowSpanPx ?: return null
        val bodyWidthRefPx = body.bodyWidthRefPx ?: return null
        val frontSpreadPx = body.frontSpreadPx ?: return null
        if (body.valid != "Y" ||
            !anchorX.isFinite() ||
            anchorX !in 0f..1f ||
            body.fraction == null ||
            supportRows < NO_TORSO_BODY_FRONT_MIN_ROWS ||
            supportRowSpanPx < NO_TORSO_BODY_FRONT_MIN_ROW_SPAN_PX ||
            bodyWidthRefPx < 8
        ) return null

        val maxFrontSpreadPx = max(
            24,
            ceil(bodyWidthRefPx * NO_TORSO_VALID_BODY_FRONT_LTR_SPREAD_REF_MULTIPLIER).toInt()
        )
        if (frontSpreadPx > maxFrontSpreadPx) return null

        val aheadPx = (anchorX - currentX) * processWidth.toFloat()
        if (aheadPx < NO_TORSO_VALID_BODY_FRONT_LTR_MIN_AHEAD_PX ||
            aheadPx > NO_TORSO_VALID_BODY_FRONT_LTR_MAX_AHEAD_PX
        ) return null

        return candidateAdjustment(
            rule = "xAnchorNoTorsoValidBodyFrontLTR",
            candidate = body
        )
    }

    private fun strongPastTorsoFrontRuntimeAdjustment(
        candidates: List<XAnchorCandidate>,
        proposal: XAnchorProposal,
        probe: XAnchorProbe,
        stripWidth: Float
    ): XAnchorRuntimeAdjustment? {
        if (proposal.name != "current" ||
            proposal.reason != "keep_current_stable" ||
            probe.signedDistanceFromGate < STRONG_PAST_TORSO_FRONT_MIN_DISTANCE_PX ||
            probe.wAtDetY < STRONG_PAST_TORSO_FRONT_MIN_GATE_WIDTH ||
            stripWidth < STRONG_PAST_TORSO_FRONT_MIN_STRIP_WIDTH
        ) return null

        val loose = candidates.firstOrNull { it.name == "torsoFrontLoose" } ?: return null
        val medium = candidates.firstOrNull { it.name == "torsoFrontMed" } ?: return null
        val anchorX = loose.x ?: return null
        val mediumX = medium.x ?: return null
        val supportColumns = loose.supportColumns ?: return null
        val supportRun = loose.supportRun ?: return null

        if (loose.valid != "Y" ||
            medium.valid != "Y" ||
            !anchorX.isFinite() ||
            anchorX !in 0f..1f ||
            !mediumX.isFinite() ||
            abs(anchorX - mediumX) > STRONG_PAST_TORSO_FRONT_MAX_LOOSE_MED_DELTA ||
            loose.fraction == null ||
            supportColumns < STRONG_PAST_TORSO_FRONT_MIN_SUPPORT_COLUMNS ||
            supportRun < STRONG_PAST_TORSO_FRONT_MIN_SUPPORT_RUN
        ) return null

        return candidateAdjustment(
            rule = "xAnchorStrongPastTorsoFront",
            candidate = loose
        )
    }

    private fun xAnchorSelectorV3RuntimeAdjustment(
        candidates: List<XAnchorCandidate>,
        probe: XAnchorProbe,
        dBefore: Float,
        movingLeftToRight: Boolean
    ): XAnchorRuntimeAdjustment? {
        if (!parameters.xAnchorSelectorV3Enabled) return null
        val currentX = candidates.firstOrNull { it.name == "current" }?.x ?: return null

        val detFront = candidates.firstOrNull { it.name == "detFront" }
        if (detFront?.valid == "Y" &&
            candidateShiftIn(detFront, currentX, movingLeftToRight, 12f, 30f) &&
            (detFront.fraction ?: 1f) <= 0.35f &&
            probe.frontTorsoRun > 0 && probe.torsoRunMin >= 60 && dBefore <= 16f
        ) candidateAdjustment("xAnchorSelectorV3DetFrontStrong", detFront)?.let { return it }

        val loose = candidates.firstOrNull { it.name == "torsoFrontLoose" }
        if (loose?.valid == "Y" &&
            candidateShiftIn(loose, currentX, movingLeftToRight, 8f, 23f) &&
            (loose.supportColumns ?: 0) >= 8 &&
            (loose.supportSpreadPx ?: Int.MAX_VALUE) <= 60
        ) candidateAdjustment("xAnchorSelectorV3LooseLate", loose)?.let { return it }

        if (loose?.valid == "Y" &&
            candidateShiftIn(loose, currentX, movingLeftToRight, 4f, 6.5f) &&
            (loose.supportColumns ?: 0) >= 10 &&
            (loose.supportRun ?: 0) >= 55 &&
            (loose.supportSpreadPx ?: Int.MAX_VALUE) <= 55
        ) candidateAdjustment("xAnchorSelectorV3LooseModerate", loose)?.let { return it }

        val medium = candidates.firstOrNull { it.name == "torsoFrontMed" }
        if (medium?.valid == "Y" &&
            candidateShiftIn(medium, currentX, movingLeftToRight, 8f, 12f) &&
            (medium.supportColumns ?: 0) >= 10 &&
            (medium.supportRun ?: 0) >= 60 &&
            (medium.supportSpreadPx ?: Int.MAX_VALUE) <= 45
        ) candidateAdjustment("xAnchorSelectorV3MedLate", medium)?.let { return it }

        if (medium?.valid == "Y" &&
            candidateShiftIn(medium, currentX, movingLeftToRight, 3f, 6f) &&
            (medium.supportColumns ?: 0) >= 12 &&
            (medium.supportRun ?: 0) >= 20 &&
            (medium.supportSpreadPx ?: Int.MAX_VALUE) <= 90
        ) candidateAdjustment("xAnchorSelectorV3MedModerate", medium)?.let { return it }

        val body = candidates.firstOrNull { it.name == "bodyFrontSubstantial" }
        val frontSpread = body?.frontSpreadPx
        val widthRef = body?.bodyWidthRefPx
        if (body?.valid == "Y" && frontSpread != null && widthRef != null && widthRef > 0 &&
            frontSpread.toFloat() / widthRef.toFloat() <= 2.03333f &&
            dBefore <= 21.5f && probe.torsoRunMin > 39
        ) candidateAdjustment("xAnchorSelectorV3BodySupported", body)?.let { return it }

        return null
    }

    private fun candidateShiftIn(
        candidate: XAnchorCandidate,
        currentX: Float,
        movingLeftToRight: Boolean,
        minimum: Float,
        maximum: Float
    ): Boolean {
        val anchorX = candidate.x ?: return false
        return xAnchorSignedShiftPct(anchorX, currentX, movingLeftToRight) in minimum..maximum
    }

    private fun xAnchorSignedShiftPct(
        anchorX: Float,
        currentX: Float,
        movingLeftToRight: Boolean
    ): Float {
        return (if (movingLeftToRight) anchorX - currentX else currentX - anchorX) * 100f
    }

    private fun candidateAdjustment(
        rule: String,
        candidate: XAnchorCandidate
    ): XAnchorRuntimeAdjustment? {
        val x = candidate.x ?: return null
        val fraction = candidate.fraction ?: return null
        if (!x.isFinite() || x !in 0f..1f || !fraction.isFinite() || fraction !in 0f..1f) {
            return null
        }
        return XAnchorRuntimeAdjustment(
            rule = rule,
            anchorName = candidate.name,
            x = x,
            fraction = fraction
        )
    }

    private fun xAnchorCandidate(
        name: String,
        x: Float?,
        movingLeftToRight: Boolean,
        dBefore: Float,
        fraction: Double,
        dt: Double,
        minRun: Int? = null,
        supportRun: Int? = null,
        supportColumns: Int? = null,
        supportSpreadPx: Int? = null,
        supportRows: Int? = null,
        supportRowSpanPx: Int? = null,
        bodyWidthRefPx: Int? = null,
        bodyWidthMedianPx: Int? = null,
        frontSpreadPx: Int? = null,
        rejectReason: String? = null
    ): XAnchorCandidate {
        if (x == null) {
            return XAnchorCandidate(
                name = name,
                x = null,
                fraction = null,
                valid = "noSupport",
                minRun = minRun,
                supportRun = supportRun,
                supportColumns = supportColumns,
                supportSpreadPx = supportSpreadPx,
                supportRows = supportRows,
                supportRowSpanPx = supportRowSpanPx,
                bodyWidthRefPx = bodyWidthRefPx,
                bodyWidthMedianPx = bodyWidthMedianPx,
                frontSpreadPx = frontSpreadPx,
                rejectReason = rejectReason
            )
        }

        val candidatePastGate = if (movingLeftToRight) {
            x - gateColumn.toFloat()
        } else {
            gateColumn.toFloat() - x
        }
        val denominator = dBefore + candidatePastGate
        val candidateFraction: Float?
        val valid: String
        if (name == "current") {
            candidateFraction = fraction.toFloat()
            valid = "current"
        } else if (candidatePastGate >= 0f && denominator > 0f) {
            candidateFraction = (dBefore / denominator)
            valid = "Y"
        } else if (candidatePastGate < 0f) {
            candidateFraction = null
            valid = "future"
        } else {
            candidateFraction = null
            valid = "degenerate"
        }

        return XAnchorCandidate(
            name = name,
            x = x / processWidth.toFloat(),
            fraction = candidateFraction,
            valid = valid,
            minRun = minRun,
            supportRun = supportRun,
            supportColumns = supportColumns,
            supportSpreadPx = supportSpreadPx,
            supportRows = supportRows,
            supportRowSpanPx = supportRowSpanPx,
            bodyWidthRefPx = bodyWidthRefPx,
            bodyWidthMedianPx = bodyWidthMedianPx,
            frontSpreadPx = frontSpreadPx,
            rejectReason = rejectReason
        )
    }

    private fun torsoColumnProfile(
        comp: Component,
        minRun: Int,
        movingLeftToRight: Boolean
    ): TorsoColumnProfile {
        val minX = max(comp.minX, 0)
        val maxX = min(comp.maxX, processWidth - 1)
        val minY = max(comp.minY, 0)
        val maxY = min(comp.maxY, processHeight - 1)
        if (minX > maxX || minY > maxY) return TorsoColumnProfile()

        val qualified = ArrayList<Pair<Int, Int>>()
        for (x in minX..maxX) {
            val run = longestVerticalRun(x, minY, maxY)
            if (run >= minRun) qualified.add(x to run)
        }
        if (qualified.isEmpty()) return TorsoColumnProfile()

        val front = if (movingLeftToRight) qualified.last() else qualified.first()
        val rear = if (movingLeftToRight) qualified.first() else qualified.last()
        val center = qualified[qualified.size / 2]
        val spread = qualified.last().first - qualified.first().first + 1
        return TorsoColumnProfile(
            frontX = front.first,
            frontRun = front.second,
            centerX = center.first,
            centerRun = center.second,
            rearX = rear.first,
            rearRun = rear.second,
            count = qualified.size,
            spread = spread
        )
    }

    private fun longestVerticalRun(x: Int, minY: Int, maxY: Int): Int {
        var currentRun = 0
        var longestRun = 0
        for (y in minY..maxY) {
            if (maskBuf[y * processWidth + x].toInt() != 0) {
                currentRun++
                if (currentRun > longestRun) longestRun = currentRun
            } else {
                currentRun = 0
            }
        }
        return longestRun
    }

    private fun bodyFrontSubstantialProfile(
        comp: Component,
        movingLeftToRight: Boolean
    ): BodyFrontProfile {
        val minX = max(0, min(comp.minX, processWidth - 1))
        val maxX = max(0, min(comp.maxX, processWidth - 1))
        val minY = max(0, min(comp.minY, processHeight - 1))
        val maxY = max(0, min(comp.maxY, processHeight - 1))
        if (minX > maxX || minY > maxY) return BodyFrontProfile.reject("empty_bounds")

        val rowSegments = ArrayList<RowSegment>(maxY - minY + 1)
        for (y in minY..maxY) {
            val selected = selectedSegmentForRow(y, minX, maxX) ?: continue
            rowSegments.add(selected)
        }
        if (rowSegments.size < 3) return BodyFrontProfile.reject("no_body_rows")

        val widthRef = percentile(rowSegments.map { it.width }, 0.75)
        val minSegmentWidth = max(8, ceil(widthRef * 0.45).toInt())
        val substantialRows = rowSegments.filter { it.width >= minSegmentWidth }
        if (substantialRows.isEmpty()) {
            return BodyFrontProfile.reject("no_substantial_rows", widthRef)
        }

        val clusters = ArrayList<List<RowSegment>>()
        var currentCluster = ArrayList<RowSegment>()
        for (row in substantialRows) {
            val last = currentCluster.lastOrNull()
            if (last != null && row.y - last.y <= BODY_FRONT_MAX_GAP + 1) {
                currentCluster.add(row)
            } else {
                if (currentCluster.isNotEmpty()) clusters.add(currentCluster)
                currentCluster = arrayListOf(row)
            }
        }
        if (currentCluster.isNotEmpty()) clusters.add(currentCluster)

        val minClusterSpan = max(18, ceil((maxY - minY + 1) * 0.12).toInt())
        val minClusterRows = max(6, ceil(minClusterSpan * 0.35).toInt())
        val eligibleClusters = clusters.filter { cluster ->
            val first = cluster.firstOrNull()
            val last = cluster.lastOrNull()
            if (first == null || last == null) {
                false
            } else {
                val span = last.y - first.y + 1
                span >= minClusterSpan && cluster.size >= minClusterRows
            }
        }
        val bestCluster = eligibleClusters.maxWithOrNull(
            compareBy<List<RowSegment>> { it.size }
                .thenBy { percentile(it.map(RowSegment::width), 0.50) }
                .thenBy { rowSpan(it) }
        ) ?: return BodyFrontProfile.reject("insufficient_cluster", widthRef)

        val sortedFronts = bestCluster
            .map { if (movingLeftToRight) it.right else it.left }
            .sorted()
        val minFront = sortedFronts.firstOrNull()
        val maxFront = sortedFronts.lastOrNull()
        if (minFront == null || maxFront == null) {
            return BodyFrontProfile.reject("no_front_rows", widthRef)
        }

        val frontSupportRows = min(
            bestCluster.size,
            max(4, ceil(bestCluster.size * 0.35).toInt())
        )
        val frontIndex = if (movingLeftToRight) {
            max(0, sortedFronts.size - frontSupportRows)
        } else {
            min(sortedFronts.size - 1, frontSupportRows - 1)
        }
        val frontX = sortedFronts[frontIndex]
        return BodyFrontProfile(
            frontX = frontX,
            supportRows = bestCluster.size,
            supportRowSpanPx = rowSpan(bestCluster),
            bodyWidthRefPx = widthRef,
            bodyWidthMedianPx = percentile(bestCluster.map(RowSegment::width), 0.50),
            frontSpreadPx = maxFront - minFront + 1,
            rejectReason = null
        )
    }

    private fun selectedSegmentForRow(y: Int, minX: Int, maxX: Int): RowSegment? {
        val segments = ArrayList<RowSegment>()
        var x = minX
        while (x <= maxX) {
            if (maskBuf[y * processWidth + x].toInt() == 0) {
                x++
                continue
            }
            val left = x
            while (x <= maxX && maskBuf[y * processWidth + x].toInt() != 0) {
                x++
            }
            segments.add(RowSegment(y, left, x - 1))
        }
        if (segments.isEmpty()) return null

        segments.filter { it.left <= gateColumn && gateColumn <= it.right }
            .maxByOrNull { it.width }
            ?.let { return it }

        return segments.minWithOrNull(
            compareBy<RowSegment> { min(abs(it.left - gateColumn), abs(it.right - gateColumn)) }
                .thenByDescending { it.width }
        )
    }

    private fun percentile(values: List<Int>, fraction: Double): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val clamped = fraction.coerceIn(0.0, 1.0)
        val index = ((sorted.size - 1) * clamped).roundToInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    private fun rowSpan(rows: List<RowSegment>): Int {
        val first = rows.firstOrNull() ?: return 0
        val last = rows.lastOrNull() ?: return 0
        return last.y - first.y + 1
    }

    /**
     * 8-way connected-component labeling with union-find. Mirrors iOS
     * `findComponents` but uses Kotlin IntArrays. The two-pass approach +
     * preallocated parent/comp buffers keeps per-frame allocation flat.
     */
    private fun findComponents(): List<Component> {
        val w = processWidth
        val h = processHeight
        val count = w * h
        labels.fill(0, 0, count)

        var nextLabel = 1
        val maxLabels = count / 4 + 256
        if (parentBuf.size < maxLabels) parentBuf = IntArray(maxLabels)
        if (compBuf.size < maxLabels) compBuf = Array(maxLabels) { Component() } else {
            // Reset only the slots we'll touch on this frame after we know
            // nextLabel; here we pre-clear the in-use range from the
            // previous call so leftover stats don't bleed in.
            for (i in 0 until min(parentBuf.size, compBuf.size)) compBuf[i] = Component()
        }

        for (y in 0 until h) {
            val rowBase = y * w
            for (xi in 0 until w) {
                val idx = rowBase + xi
                if (maskBuf[idx].toInt() == 0) continue

                var nCount = 0
                var n0 = 0; var n1 = 0; var n2 = 0; var n3 = 0
                if (y > 0 && xi > 0) {
                    val l = labels[(y - 1) * w + (xi - 1)]
                    if (l > 0) { n0 = l; nCount = 1 }
                }
                if (y > 0) {
                    val l = labels[(y - 1) * w + xi]
                    if (l > 0) {
                        when (nCount) { 0 -> n0 = l; 1 -> n1 = l; 2 -> n2 = l; else -> n3 = l }
                        nCount++
                    }
                }
                if (y > 0 && xi < w - 1) {
                    val l = labels[(y - 1) * w + (xi + 1)]
                    if (l > 0) {
                        when (nCount) { 0 -> n0 = l; 1 -> n1 = l; 2 -> n2 = l; else -> n3 = l }
                        nCount++
                    }
                }
                if (xi > 0) {
                    val l = labels[y * w + (xi - 1)]
                    if (l > 0) {
                        when (nCount) { 0 -> n0 = l; 1 -> n1 = l; 2 -> n2 = l; else -> n3 = l }
                        nCount++
                    }
                }

                if (nCount == 0) {
                    val lbl = nextLabel
                    parentBuf[lbl] = lbl
                    labels[idx] = lbl
                    nextLabel++
                } else {
                    val minL = when (nCount) {
                        1 -> n0
                        2 -> min(n0, n1)
                        3 -> min(n0, min(n1, n2))
                        else -> min(n0, min(n1, min(n2, n3)))
                    }
                    labels[idx] = minL
                    if (nCount >= 2) { union(n0, minL); union(n1, minL) }
                    if (nCount >= 3) union(n2, minL)
                    if (nCount >= 4) union(n3, minL)
                }
            }
        }

        // Pass 2: resolve labels and gather stats.
        for (i in 1 until nextLabel) compBuf[i] = Component()
        for (y in 0 until h) {
            val rowBase = y * w
            for (xi in 0 until w) {
                val idx = rowBase + xi
                val lbl = labels[idx]
                if (lbl <= 0) continue
                val root = find(lbl)
                labels[idx] = root
                val c = compBuf[root]
                if (xi < c.minX) c.minX = xi
                if (xi > c.maxX) c.maxX = xi
                if (y < c.minY) c.minY = y
                if (y > c.maxY) c.maxY = y
                c.area += 1
            }
        }

        val result = ArrayList<Component>(8)
        for (i in 1 until nextLabel) {
            val c = compBuf[i]
            if (c.area > 0) result.add(c.copy())
        }
        return result
    }

    private fun find(x: Int): Int {
        var r = x
        while (parentBuf[r] != r) r = parentBuf[r]
        var c = x
        while (c != r) { val n = parentBuf[c]; parentBuf[c] = r; c = n }
        return r
    }

    private fun union(a: Int, b: Int) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parentBuf[ra] = rb
    }

    // ── Types ──────────────────────────────────────────────────────────

    /**
     * Mutable bbox + area accumulator for one connected component. Reset
     * via assignment in pass 2 — never zeroed in place, so we can copy()
     * a frozen snapshot into the public component list.
     */
    private data class Component(
        var minX: Int = Int.MAX_VALUE,
        var maxX: Int = 0,
        var minY: Int = Int.MAX_VALUE,
        var maxY: Int = 0,
        var area: Int = 0
    ) {
        val width: Int get() = maxX - minX + 1
        val height: Int get() = maxY - minY + 1
    }

    private data class Run(val startY: Int, val endY: Int) {
        val length: Int get() = endY - startY + 1
    }
    private data class GateBandMetrics(
        val columns: Int = 0,
        val pixels: Int = 0,
        val totalPixels: Int = 0
    )
    private data class QualProbe(val idx: Int, val centerY: Int, val width: Int)
    private data class Candidate(val comp: Component, val lowContrastFallback: Boolean)
    private data class MotionDirectionSample(
        val frameIndex: Int,
        val centerX: Float
    )
    private data class RowSegment(val y: Int, val left: Int, val right: Int) {
        val width: Int get() = right - left + 1
    }

    private data class BodyFrontProfile(
        val frontX: Int?,
        val supportRows: Int,
        val supportRowSpanPx: Int?,
        val bodyWidthRefPx: Int?,
        val bodyWidthMedianPx: Int?,
        val frontSpreadPx: Int?,
        val rejectReason: String?
    ) {
        companion object {
            fun reject(reason: String, widthRef: Int? = null) = BodyFrontProfile(
                frontX = null,
                supportRows = 0,
                supportRowSpanPx = null,
                bodyWidthRefPx = widthRef,
                bodyWidthMedianPx = null,
                frontSpreadPx = null,
                rejectReason = reason
            )
        }
    }

    private data class TorsoColumnProfile(
        val frontX: Int? = null,
        val frontRun: Int? = null,
        val centerX: Int? = null,
        val centerRun: Int? = null,
        val rearX: Int? = null,
        val rearRun: Int? = null,
        val count: Int = 0,
        val spread: Int? = null
    )

    private data class XAnchorCandidate(
        val name: String,
        val x: Float?,
        val fraction: Float?,
        val valid: String,
        val minRun: Int? = null,
        val supportRun: Int? = null,
        val supportColumns: Int? = null,
        val supportSpreadPx: Int? = null,
        val supportRows: Int? = null,
        val supportRowSpanPx: Int? = null,
        val bodyWidthRefPx: Int? = null,
        val bodyWidthMedianPx: Int? = null,
        val frontSpreadPx: Int? = null,
        val rejectReason: String? = null
    )

    private data class XAnchorProposal(
        val name: String,
        val x: Float?,
        val fraction: Float?,
        val valid: String,
        val reason: String
    )

    private data class XAnchorRuntimeAdjustment(
        val rule: String,
        val anchorName: String,
        val x: Float,
        val fraction: Float
    )

    private data class XAnchorProbe(
        val frontTorsoX: Int,
        val frontTorsoRun: Int,
        val torsoRunMin: Int,
        val signedDistanceFromGate: Int,
        val wAtDetY: Int
    )

    /** Crossing event emitted by [processFrame] when the engine fires. */
    data class Result(
        /** Time of crossing in seconds since session start (sub-frame interpolated). */
        val crossingTimeSeconds: Double,
        /** Same instant in absolute monotonic nanos (SystemClock.elapsedRealtimeNanos). */
        val crossingTimestampNanos: Long,
        /** 0..1 fraction of the way between previous and current frame timestamps. */
        val interpolationFraction: Double,
        val dBeforePx: Float,
        val dAfterPx: Float,
        val movingLeftToRight: Boolean,
        val velocityPxPerSec: Float,
        /** Y row chosen for the leading-edge strip (in process-resolution units). */
        val gateY: Int,
        /** Component bbox normalised to 0..1 for UI overlays. */
        val componentBoundsNorm: NormalizedRect,
        val blobHeightFraction: Float,
        val blobCenterXFraction: Float,
        val buildupFrames: Int,
        val xAnchorRuntimeDisplayX: Float? = null,
        val xAnchorRuntimeRule: String? = null
    )

    data class NormalizedRect(val x: Float, val y: Float, val width: Float, val height: Float)

    companion object {
        const val PROCESS_WIDTH = 180
        const val PROCESS_HEIGHT = 320

        // Frame diff threshold (iOS spec line 114).
        const val DIFF_THRESHOLD = 15

        // §49 (2026-04-18) — height/width prefilter.
        const val HEIGHT_FRACTION = 0.35f
        const val WIDTH_FRACTION = 0.08f

        // §24 two-tier prefilter.
        const val MIN_FILL_STRICT = 0.20f
        const val MIN_FILL_LENIENT = 0.12f
        const val MAX_ASPECT_STRICT = 1.2f
        const val MAX_ASPECT_LENIENT = 1.7f

        // §21 / §48 — chest is ~30% from top of bbox.
        const val TORSO_FRACTION = 0.30f

        // §27 floor cap + §42a PF-PARITY FLOOR.
        const val TORSO_RUN_ABS_MIN = 30
        const val TORSO_RUN_ABS_MAX = 55
        const val TORSO_RUN_HEIGHT_FRAC = 0.25f

        // §54 Low-contrast body fallback (current iOS production values).
        const val LOW_CONTRAST_BODY_FALLBACK_MIN_MERGED_RUN = 18
        const val LOW_CONTRAST_BODY_FALLBACK_MIN_LOCAL_RUN = 18
        const val LOW_CONTRAST_BODY_FALLBACK_MIN_GATE_BAND_COLS = 3
        const val LOW_CONTRAST_BODY_FALLBACK_MIN_GATE_BAND_PIXELS = 45
        const val LOW_CONTRAST_BODY_FALLBACK_MIN_SEQUENCE_FRAMES = 2
        const val LOW_CONTRAST_BODY_FALLBACK_MIN_BLOB_HEIGHT_FRACTION = 0.40f
        const val LOW_CONTRAST_BODY_FALLBACK_MIN_BLOB_WIDTH_FRACTION = 0.10f
        const val LOW_CONTRAST_BODY_FALLBACK_MAX_BLOB_WIDTH_FRACTION = 0.88f
        const val LOW_CONTRAST_BODY_FALLBACK_UPPER_ZONE_FRACTION = 0.55f

        // Temporal direction inference prevents direction inversion after a
        // fast runner's center has already crossed the gate.
        const val MOTION_DIRECTION_MIN_DELTA_PX = 4f
        const val MOTION_DIRECTION_HISTORY_MAX_AGE_FRAMES = 8
        const val MOTION_DIRECTION_HISTORY_LEN = 8

        // §29 — merge adjacent gate-band runs with small gaps.
        const val GATE_RUN_MERGE_MAX_GAP = 2

        // §19 full-frame flash guard.
        const val FLASH_GUARD_COVERAGE = 0.55f
        const val FLASH_GUARD_FILL = 0.70f
        const val FLASH_GUARD_W_FRAC = 0.80f
        const val FLASH_GUARD_H_FRAC = 0.80f

        // §50 Full-width band guard: global horizontal scene changes can span
        // the full frame while real crossings remain materially narrower.
        const val FULL_WIDTH_BAND_W_FRAC = 0.95f

        // §51 Sparse startup scene-motion guard.
        const val SPARSE_STARTUP_SCENE_MOTION_FRAME_LIMIT = 90
        const val SPARSE_STARTUP_SCENE_MOTION_MAX_GATE_WIDTH = 8

        // §52 Thin gate-row scene-motion guard.
        const val THIN_SCENE_MOTION_MAX_BUILDUP = 1
        const val THIN_SCENE_MOTION_MAX_STRIP_WIDTH = 1f
        const val THIN_SCENE_MOTION_MAX_HRUN = 2
        const val THIN_SCENE_MOTION_MAX_GATE_WIDTH = 1
        const val THIN_SCENE_MOTION_MIN_BLOB_HEIGHT_FRACTION = 0.55f
        const val THIN_SCENE_MOTION_MIN_TORSO_DISTANCE = 10

        // §55 (iOS 2026-07-16): reject position-independent, bodyless scene
        // motion only when its gate row is incoherent and its shape is broad
        // or highly fragmented. Buildup and vertical position are purposely
        // excluded because real fast crossings share those signatures.
        const val SCENE_MOTION_MIN_WIDTH_FRACTION = 0.80f
        const val SCENE_MOTION_MIN_TORSO_FRAGMENTS = 24
        const val SCENE_MOTION_WEAK_MAX_GATE_WIDTH = 8
        const val SCENE_MOTION_WEAK_MAX_STRIP_WIDTH = 8f
        const val SCENE_MOTION_WEAK_MAX_HRUN = 8
        const val SCENE_MOTION_BROAD_SHADOW_MIN_WIDTH_FRACTION = 0.90f
        const val SCENE_MOTION_BROAD_SHADOW_MAX_GATE_WIDTH = 14
        const val SCENE_MOTION_BROAD_SHADOW_MAX_STRIP_WIDTH = 14f
        const val SCENE_MOTION_BROAD_SHADOW_MAX_HRUN = 14

        // §53 x-anchor runtime parity with current iOS DetectionEngine.
        const val NEAR_GATE_TORSO_FRONT_MAX_DISTANCE_PX = 8
        const val NEAR_GATE_TORSO_FRONT_MIN_GATE_WIDTH = 8
        const val NEAR_GATE_TORSO_FRONT_MIN_STRIP_WIDTH = 7f
        const val STRONG_PAST_TORSO_FRONT_MIN_DISTANCE_PX = 15
        const val STRONG_PAST_TORSO_FRONT_MIN_GATE_WIDTH = 20
        const val STRONG_PAST_TORSO_FRONT_MIN_STRIP_WIDTH = 20f
        const val STRONG_PAST_TORSO_FRONT_MIN_SUPPORT_COLUMNS = 15
        const val STRONG_PAST_TORSO_FRONT_MIN_SUPPORT_RUN = 40
        const val STRONG_PAST_TORSO_FRONT_MAX_LOOSE_MED_DELTA = 0.02f
        const val NO_TORSO_BODY_FRONT_MIN_ROWS = 8
        const val NO_TORSO_BODY_FRONT_MIN_ROW_SPAN_PX = 18
        const val NO_TORSO_FUTURE_BODY_FRONT_MIN_BEHIND_PX = 3f
        const val NO_TORSO_FUTURE_BODY_FRONT_MAX_BEHIND_PX = 24f
        const val NO_TORSO_FUTURE_BODY_FRONT_MAX_FRONT_SPREAD_PX = 42
        const val NO_TORSO_FUTURE_BODY_FRONT_SPREAD_REF_MULTIPLIER = 3.0f
        const val NO_TORSO_VALID_BODY_FRONT_LTR_MAX_GATE_WIDTH = 17
        const val NO_TORSO_VALID_BODY_FRONT_LTR_MAX_STRIP_WIDTH = 16f
        const val NO_TORSO_VALID_BODY_FRONT_LTR_MIN_AHEAD_PX = 4f
        const val NO_TORSO_VALID_BODY_FRONT_LTR_MAX_AHEAD_PX = 14f
        const val NO_TORSO_VALID_BODY_FRONT_LTR_SPREAD_REF_MULTIPLIER = 1.5f
        const val NO_TORSO_TINY_GATE_ROW_MAX_GATE_WIDTH = 2
        const val NO_TORSO_TINY_GATE_ROW_MAX_STRIP_WIDTH = 2f
        const val NO_TORSO_TINY_GATE_ROW_MAX_HRUN = 3
        const val NO_TORSO_TINY_GATE_ROW_MIN_BLOB_HEIGHT_FRACTION = 0.55f
        const val FUTURE_TORSO_GATE_ROW_MAX_GATE_WIDTH = 0
        const val FUTURE_TORSO_GATE_ROW_MAX_STRIP_WIDTH = 10f
        const val FUTURE_TORSO_GATE_ROW_MIN_DISTANCE_PX = 12
        const val FUTURE_TORSO_GATE_ROW_MIN_SUPPORT_RUN = 30
        const val FUTURE_TORSO_GATE_ROW_MIN_SUPPORT_COLUMNS = 3
        const val FUTURE_BODY_TORSO_WAIT_MAX_GATE_WIDTH = 4
        const val FUTURE_BODY_TORSO_WAIT_MAX_STRIP_WIDTH = 4f
        const val FUTURE_BODY_TORSO_CONTROL_WAIT_MAX_GATE_WIDTH = 19
        const val FUTURE_BODY_TORSO_CONTROL_WAIT_MAX_STRIP_WIDTH = 18f
        const val FUTURE_BODY_TORSO_WAIT_MIN_DISTANCE_PX = 12
        const val FUTURE_BODY_TORSO_WAIT_MIN_SUPPORT_RUN = 30
        const val FUTURE_BODY_TORSO_WAIT_MIN_SUPPORT_COLUMNS = 3
        const val FUTURE_BODY_TORSO_WAIT_MIN_BODY_ROWS = 8
        const val FUTURE_BODY_TORSO_WAIT_MIN_BODY_ROW_SPAN_PX = 18
        const val FUTURE_BODY_TORSO_WAIT_MIN_BODY_WIDTH_REF_PX = 8
        const val FUTURE_BODY_TORSO_WAIT_MAX_BODY_FRONT_SPREAD_PX = 42
        const val FUTURE_BODY_TORSO_WAIT_BODY_SPREAD_REF_MULTIPLIER = 3f
        const val WAIT_FOR_TORSO_SAME_FRAME_MIN_DISTANCE_PX = 12
        const val ZERO_GATE_ROW_BODY_FRONT_R2L_MAX_GATE_WIDTH = 0
        const val ZERO_GATE_ROW_BODY_FRONT_R2L_MAX_STRIP_WIDTH = 0f
        const val ZERO_GATE_ROW_BODY_FRONT_R2L_MIN_TORSO_PAST_PX = 8
        const val ZERO_GATE_ROW_BODY_FRONT_R2L_MIN_AHEAD_PX = 6f
        const val ZERO_GATE_ROW_BODY_FRONT_R2L_MAX_AHEAD_PX = 18f
        const val ZERO_GATE_ROW_BODY_FRONT_R2L_SPREAD_REF_MULTIPLIER = 1.5f
        const val BODY_FRONT_MAX_GAP = 2

        private val TORSO_FRONT_CANDIDATE_NAMES = setOf(
            "torsoFrontLoose",
            "torsoFrontMed",
            "torsoFrontStrict"
        )

        private val VALID_BODY_CANDIDATE_NAMES = setOf(
            "torsoFrontLoose",
            "torsoFrontMed",
            "torsoCenterMed",
            "torsoRearMed",
            "torsoFrontStrict",
            "bodyFrontSubstantial"
        )

        internal fun shouldRejectIncoherentSceneMotion(
            blobWidth: Int,
            processWidth: Int,
            noTorsoSupport: Boolean,
            hasValidBodySupport: Boolean,
            gateRowWidth: Int,
            stripWidth: Float,
            horizontalRun: Int,
            torsoFragmentCount: Int,
            parameters: ReplicaDetectionConfiguration.Parameters =
                ReplicaDetectionConfiguration.Parameters()
        ): Boolean {
            if (processWidth <= 0 || !noTorsoSupport || hasValidBodySupport) return false

            val widthFraction = blobWidth.toFloat() / processWidth.toFloat()
            val weakGate = gateRowWidth <= parameters.sceneMotionWeakMaxGateWidth &&
                stripWidth <= parameters.sceneMotionWeakMaxStripWidth &&
                horizontalRun <= parameters.sceneMotionWeakMaxHorizontalRun
            val sceneShaped = widthFraction >= parameters.sceneMotionMinWidthFraction ||
                torsoFragmentCount >= parameters.sceneMotionMinTorsoFragments
            val broadFragmentedShadow =
                widthFraction >= parameters.sceneMotionBroadShadowMinWidthFraction &&
                    torsoFragmentCount >= parameters.sceneMotionMinTorsoFragments &&
                    gateRowWidth <= parameters.sceneMotionBroadShadowMaxGateWidth &&
                    stripWidth <= parameters.sceneMotionBroadShadowMaxStripWidth &&
                    horizontalRun <= parameters.sceneMotionBroadShadowMaxHorizontalRun

            return (weakGate && sceneShaped) || broadFragmentedShadow
        }

        internal fun inferMovingLeftToRight(
            currentCenterX: Float,
            gateColumn: Float,
            strongestHistoricalDelta: Float,
            minimumDeltaPx: Float = MOTION_DIRECTION_MIN_DELTA_PX
        ): Boolean {
            return if (abs(strongestHistoricalDelta) >= minimumDeltaPx) {
                strongestHistoricalDelta > 0f
            } else {
                currentCenterX <= gateColumn
            }
        }

        internal fun exposureCorrectionSeconds(exposureNanos: Long?): Double {
            val exposureSeconds = (exposureNanos ?: 0L) / 1e9
            return if (exposureSeconds > 0.002) 0.75 * exposureSeconds else 0.0
        }

        // Gate analysis band (single-col ±2 used for the legacy slice
        // analysis; thick band ±4 used for the §34 OR projection).
        const val GATE_BAND_HALF = 2
        const val THICK_GATE_HALF = 4

        // §37 H-LIMB-WAIT-RELEASE — release after this many consecutive
        // suppressed frames.
        const val LIMB_WAIT_RELEASE_AFTER = 3

        // Cooldown + warmup.
        const val DEFAULT_COOLDOWN_SECONDS = 0.3
        const val WARMUP_FRAMES = 10

        private const val LEGACY_MIN_GATE_HEIGHT_FRACTION = 0.08f
        private const val LEGACY_GATE_SLICE_WIDTH = 3

        private const val TAG = "DetectionEngine"
    }
}
