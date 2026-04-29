package com.overdrive.app.surveillance;

import com.overdrive.app.logging.DaemonLogger;

/**
 * BlockNoiseMap — tracks per-block activation frequency within a sentry session.
 *
 * Each block's activation rate is maintained as an exponential moving average
 * (EMA, alpha=0.01, ~100-frame half-life). After a 60-second warmup period,
 * blocks that activate more than 30% of the time are flagged as "noise zones" —
 * environmental motion sources (trees, shadows, reflections) that fire persistently.
 *
 * When all confirmed blocks in a detection event fall within noise zones,
 * EventClassifier withholds recording unless YOLO confirms a person.
 *
 * The map resets on sentry disarm so each new parking session builds fresh
 * knowledge of its environment.
 */
public class BlockNoiseMap {

    private static final DaemonLogger logger = DaemonLogger.getInstance("BlockNoiseMap");

    private static final int QUADRANTS          = MotionPipelineV2.NUM_QUADRANTS;
    private static final int BLOCKS             = MotionPipelineV2.TOTAL_BLOCKS;   // 70 (10×7)
    private static final int WARMUP_FRAMES      = 600;   // 60s at 10fps before noise zones take effect
    private static final float NOISE_THRESHOLD  = 0.30f; // block fires >30% of frames → noise zone
    private static final float EMA_ALPHA        = 0.01f; // slow decay: ~100-frame half-life
    private static final float CONFIRMED_MIN    = 0.3f;  // blockConfidence threshold for "active"

    // Per-quadrant, per-block EMA activation frequency [0..1]
    private final float[][] frequency = new float[QUADRANTS][BLOCKS];

    private int frameCount = 0;

    // --- Public API ---

    /**
     * Called every frame from the main GL thread, before EventClassifier.classify().
     * Updates the EMA for every block based on whether it is confirmed this frame.
     */
    public void update(MotionPipelineV2.QuadrantResult[] results) {
        frameCount++;
        for (int q = 0; q < QUADRANTS; q++) {
            float[] blockConf = results[q].blockConfidence;
            for (int b = 0; b < BLOCKS; b++) {
                float activated = (blockConf[b] >= CONFIRMED_MIN) ? 1.0f : 0.0f;
                frequency[q][b] = frequency[q][b] * (1.0f - EMA_ALPHA) + activated * EMA_ALPHA;
            }
        }
    }

    /**
     * Returns true if the given block has been persistently active (environmental noise).
     * Always returns false during the warmup period.
     */
    public boolean isNoiseZone(int quadrant, int blockIndex) {
        return frameCount >= WARMUP_FRAMES && frequency[quadrant][blockIndex] > NOISE_THRESHOLD;
    }

    /**
     * Returns true if every confirmed block in the given quadrant result falls within a
     * noise zone, and at least one block is confirmed.
     *
     * If this returns true, the detection event should be treated as environmental motion
     * and requires YOLO person confirmation before recording.
     */
    public boolean allBlocksInNoiseZones(int quadrant, MotionPipelineV2.QuadrantResult result) {
        if (frameCount < WARMUP_FRAMES) return false;

        int confirmedCount = 0;
        for (int b = 0; b < BLOCKS; b++) {
            if (result.blockConfidence[b] >= CONFIRMED_MIN) {
                confirmedCount++;
                if (!isNoiseZone(quadrant, b)) {
                    // At least one confirmed block is NOT a noise zone → genuine event
                    return false;
                }
            }
        }
        boolean allNoisy = confirmedCount > 0;
        if (allNoisy) {
            logger.debug(String.format(
                    "Q%d: %d confirmed blocks, all in noise zones (warmup=%d)", quadrant, confirmedCount, frameCount));
        }
        return allNoisy;
    }

    /**
     * Returns the number of frames observed since the last reset.
     * Values below WARMUP_FRAMES mean noise suppression is not yet active.
     */
    public int getFrameCount() {
        return frameCount;
    }

    /**
     * Resets the noise map for a new sentry session.
     * Call on sentry disarm so the next parking session builds fresh environmental knowledge.
     */
    public void reset() {
        for (int q = 0; q < QUADRANTS; q++) {
            for (int b = 0; b < BLOCKS; b++) {
                frequency[q][b] = 0.0f;
            }
        }
        frameCount = 0;
        logger.info("BlockNoiseMap reset for new sentry session");
    }
}
