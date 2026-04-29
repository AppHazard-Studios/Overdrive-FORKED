package com.overdrive.app.surveillance;

import com.overdrive.app.ai.Detection;
import com.overdrive.app.logging.DaemonLogger;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * EventClassifier — combines C++ motion pipeline output with velocity, shape, YOLO, and
 * per-session noise signals to produce a reliable effective threat level.
 *
 * Replaces the raw pipelineV2.getMaxThreatLevel() call in SurveillanceEngineGpu. All
 * signal processing happens in Java with no JNI calls; each frame costs ~0.1ms.
 *
 * Design rules:
 *   - YOLO person confirmation always wins (overrides motion classifier downgrade)
 *   - High centroid speed always downgrades (vehicles cannot loiter)
 *   - Near-zero centroid speed upgrades toward HIGH (standing person = threat)
 *   - Wide flat components (vehicle silhouette) are downgraded
 *   - Blocks that fire continuously for 60s are marked as environmental noise; events
 *     consisting solely of noise-zone blocks require YOLO confirmation before recording
 */
public class EventClassifier {

    private static final DaemonLogger logger = DaemonLogger.getInstance("EventClassifier");

    // Speed thresholds in blocks/frame (1 block = 32 px, quadrant is 320 px wide, 10 fps)
    // Vehicle at 30 km/h, 10 m from camera ≈ 5–8 blocks/frame
    // Person walking briskly ≈ 0.5–1.5 blocks/frame
    // Person standing ≈ 0–0.15 blocks/frame
    private static final float SPEED_LOITER_MAX   = 0.15f;  // near-zero → standing still
    private static final float SPEED_VEHICLE_MIN  = 4.0f;   // road-speed vehicle

    // Component shape thresholds (block grid: GRID_COLS=10, GRID_ROWS=7)
    private static final int   VEHICLE_MIN_WIDTH   = 6;     // ≥6 blocks wide
    private static final float VEHICLE_ASPECT_MIN  = 3.0f;  // width:height ratio ≥ 3:1

    // YOLO confidence thresholds
    private static final float YOLO_PERSON_HIGH = 0.25f;  // force THREAT_HIGH
    private static final float YOLO_PERSON_LOW  = 0.15f;  // force at least THREAT_MEDIUM

    // Centroid history length for speed estimation (frames)
    private static final int HISTORY_SIZE = 10;
    // Speed is computed over a 5-frame displacement (= 500 ms at 10 fps)
    private static final int SPEED_WINDOW = 5;

    // Per-quadrant centroid history (written on main GL thread each frame)
    private final float[][] histX    = new float[MotionPipelineV2.NUM_QUADRANTS][HISTORY_SIZE];
    private final float[][] histY    = new float[MotionPipelineV2.NUM_QUADRANTS][HISTORY_SIZE];
    private final int[]     histIdx  = new int[MotionPipelineV2.NUM_QUADRANTS];
    private final int[]     histCount= new int[MotionPipelineV2.NUM_QUADRANTS];

    // Latest motion-filtered YOLO detections per quadrant.
    // Written by the AI executor thread; read by the main GL thread via classify().
    // AtomicReference provides a last-write-wins guarantee without blocking either thread.
    @SuppressWarnings("unchecked")
    private final AtomicReference<List<Detection>>[] yoloResults =
            new AtomicReference[MotionPipelineV2.NUM_QUADRANTS];

    public EventClassifier() {
        for (int i = 0; i < MotionPipelineV2.NUM_QUADRANTS; i++) {
            yoloResults[i] = new AtomicReference<>(null);
        }
    }

    // --- Public API (called by SurveillanceEngineGpu) ---

    /**
     * Called from the AI executor after YOLO finishes for a quadrant.
     * Thread-safe: replaces the stored list atomically.
     *
     * @param quadrant    0–3
     * @param detections  motion-filtered detections from runAiOnQuadrant
     */
    public void onYoloResult(int quadrant, List<Detection> detections) {
        if (quadrant < 0 || quadrant >= MotionPipelineV2.NUM_QUADRANTS) return;
        yoloResults[quadrant].set(detections);
    }

    /**
     * Called from the main GL thread each frame to maintain per-quadrant centroid history
     * for speed estimation. Must be called before classify().
     */
    public void updateCentroidHistory(MotionPipelineV2.QuadrantResult[] results) {
        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
            MotionPipelineV2.QuadrantResult r = results[q];
            // Only record centroid when a real cluster is present
            if (r.componentSize > 0) {
                histX[q][histIdx[q]] = r.centroidX;
                histY[q][histIdx[q]] = r.centroidY;
                histIdx[q] = (histIdx[q] + 1) % HISTORY_SIZE;
                if (histCount[q] < HISTORY_SIZE) histCount[q]++;
            }
        }
    }

    /**
     * Resets centroid history and cached YOLO result for one quadrant.
     * Call when a motion sequence ends without triggering, or when sentry disarms.
     */
    public void reset(int quadrant) {
        histIdx[quadrant]   = 0;
        histCount[quadrant] = 0;
        yoloResults[quadrant].set(null);
    }

    /** Resets all quadrants. Call on sentry disarm. */
    public void resetAll() {
        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) reset(q);
    }

    /**
     * Computes the effective maximum threat level across all quadrants.
     *
     * Starts from the C++ pipeline's per-quadrant threatLevel and applies:
     *   1. Velocity gate   — high speed → THREAT_LOW; near-zero speed → THREAT_HIGH
     *   2. Shape gate      — vehicle silhouette → THREAT_LOW
     *   3. Noise gate      — if all active blocks are environmentally noisy → require YOLO
     *   4. YOLO override   — person confirmed → raise; no person in noisy event → suppress
     *
     * @param results   C++ pipeline results (updated this frame)
     * @param noiseMap  per-session block noise frequency tracker
     * @return adjusted threat level (MotionPipelineV2.THREAT_*)
     */
    public int classify(MotionPipelineV2.QuadrantResult[] results, BlockNoiseMap noiseMap) {
        int effectiveThreat = MotionPipelineV2.THREAT_NONE;

        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
            MotionPipelineV2.QuadrantResult r = results[q];

            // Skip quadrants with no activity at all
            if (r.threatLevel == MotionPipelineV2.THREAT_NONE && r.componentSize == 0) continue;
            if (!r.motionDetected && r.componentSize == 0) continue;

            int quadrantThreat = r.threatLevel;

            // --- 1. Velocity gate ---
            float speed = computeSpeed(q);
            if (speed >= SPEED_VEHICLE_MIN) {
                // Centroid moves at vehicle pace — downgrade regardless of spatial classification
                quadrantThreat = MotionPipelineV2.THREAT_LOW;
                if (logger.isDebugEnabled()) {
                    logger.debug(String.format("Q%d speed=%.2f ≥ %.1f → vehicle auto-downgrade to LOW",
                            q, speed, SPEED_VEHICLE_MIN));
                }
            } else if (speed >= 0f && speed < SPEED_LOITER_MAX
                    && r.componentSize > 0 && histCount[q] >= SPEED_WINDOW + 1) {
                // Near-zero speed + visible component = object standing still → loitering
                if (quadrantThreat < MotionPipelineV2.THREAT_HIGH) {
                    quadrantThreat = MotionPipelineV2.THREAT_HIGH;
                    if (logger.isDebugEnabled()) {
                        logger.debug(String.format("Q%d speed=%.2f < %.2f (stationary) → upgrade to HIGH",
                                q, speed, SPEED_LOITER_MAX));
                    }
                }
            }

            // --- 2. Component shape gate (only meaningful at MEDIUM or above) ---
            if (quadrantThreat >= MotionPipelineV2.THREAT_MEDIUM) {
                int[] bounds = computeBlockBounds(r);
                if (bounds != null) {
                    int width  = bounds[2] - bounds[0] + 1;
                    int height = bounds[3] - bounds[1] + 1;
                    if (height > 0
                            && width >= VEHICLE_MIN_WIDTH
                            && (float) width / height >= VEHICLE_ASPECT_MIN) {
                        // Very wide, very flat component = vehicle silhouette
                        quadrantThreat = MotionPipelineV2.THREAT_LOW;
                        if (logger.isDebugEnabled()) {
                            logger.debug(String.format(
                                    "Q%d component %dw×%dh (vehicle shape, ratio=%.1f) → downgrade to LOW",
                                    q, width, height, (float) width / height));
                        }
                    }
                }
            }

            // --- 3 & 4. Noise gate + YOLO override ---
            boolean requiresYolo = noiseMap.allBlocksInNoiseZones(q, r);

            List<Detection> detections = yoloResults[q].get();
            float bestPersonConf = 0f;
            if (detections != null) {
                for (Detection det : detections) {
                    if (det.getClassId() == 0 && det.getConfidence() > bestPersonConf) {
                        bestPersonConf = det.getConfidence();
                    }
                }
            }

            if (bestPersonConf >= YOLO_PERSON_HIGH) {
                // YOLO sees a person with high confidence — this is a real event
                // regardless of what the motion classifier decided
                quadrantThreat = MotionPipelineV2.THREAT_HIGH;
                logger.info(String.format("Q%d YOLO person conf=%.2f → force HIGH", q, bestPersonConf));
            } else if (bestPersonConf >= YOLO_PERSON_LOW) {
                // Medium-confidence person: at least MEDIUM
                if (quadrantThreat < MotionPipelineV2.THREAT_MEDIUM) {
                    quadrantThreat = MotionPipelineV2.THREAT_MEDIUM;
                    logger.info(String.format("Q%d YOLO person conf=%.2f → upgrade to MEDIUM",
                            q, bestPersonConf));
                }
            } else if (requiresYolo) {
                // All active blocks are in environmentally-noisy zones and YOLO found
                // no person — this is environmental motion, not a threat
                quadrantThreat = MotionPipelineV2.THREAT_LOW;
                if (logger.isDebugEnabled()) {
                    logger.debug(String.format(
                            "Q%d all blocks in noise zones, no YOLO person → suppress to LOW", q));
                }
            }

            if (quadrantThreat > effectiveThreat) {
                effectiveThreat = quadrantThreat;
            }
        }

        return effectiveThreat;
    }

    // --- Private helpers ---

    /**
     * Centroid speed in blocks/frame, averaged over SPEED_WINDOW frames.
     * Returns -1 if insufficient history to compute a meaningful estimate.
     */
    private float computeSpeed(int quadrant) {
        if (histCount[quadrant] < SPEED_WINDOW + 1) return -1f;
        int cur = (histIdx[quadrant] - 1 + HISTORY_SIZE) % HISTORY_SIZE;
        int old = (histIdx[quadrant] - 1 - SPEED_WINDOW + HISTORY_SIZE) % HISTORY_SIZE;
        float dx = histX[quadrant][cur] - histX[quadrant][old];
        float dy = histY[quadrant][cur] - histY[quadrant][old];
        return (float) Math.sqrt(dx * dx + dy * dy) / (float) SPEED_WINDOW;
    }

    /**
     * Bounding box (in block coordinates) of all confirmed blocks in the result.
     * Returns [minCol, minRow, maxCol, maxRow] or null if no confirmed blocks.
     */
    private int[] computeBlockBounds(MotionPipelineV2.QuadrantResult result) {
        int minCol = Integer.MAX_VALUE, maxCol = Integer.MIN_VALUE;
        int minRow = Integer.MAX_VALUE, maxRow = Integer.MIN_VALUE;
        for (int b = 0; b < MotionPipelineV2.TOTAL_BLOCKS; b++) {
            if (result.blockConfidence[b] >= 0.3f) {
                int col = b % MotionPipelineV2.GRID_COLS;
                int row = b / MotionPipelineV2.GRID_COLS;
                if (col < minCol) minCol = col;
                if (col > maxCol) maxCol = col;
                if (row < minRow) minRow = row;
                if (row > maxRow) maxRow = row;
            }
        }
        if (minCol == Integer.MAX_VALUE) return null;
        return new int[]{minCol, minRow, maxCol, maxRow};
    }
}
