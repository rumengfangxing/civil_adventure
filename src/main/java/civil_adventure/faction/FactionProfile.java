package civil_adventure.faction;

import java.util.Arrays;

/**
 * 区块级多派系分值紧凑张量与显性统治裁决器。
 * 内存开销极低：仅包含一个与注册派系统计长度相等的 float[] 数组。
 */
public final class FactionProfile {

    private final float[] scores;
    private byte dominantIndex = -1;
    private float dominantScore = 0.0f;
    private float runnerUpScore = 0.0f;

    public FactionProfile(int factionCount) {
        this.scores = new float[factionCount];
    }

    public synchronized void setScore(byte factionIndex, float score) {
        if (factionIndex >= 0 && factionIndex < scores.length) {
            scores[factionIndex] = Math.max(0.0f, score);
            recalculateDominance();
        }
    }

    public synchronized float getScore(byte factionIndex) {
        if (factionIndex >= 0 && factionIndex < scores.length) {
            return scores[factionIndex];
        }
        return 0.0f;
    }

    public synchronized float[] getAllScores() {
        return Arrays.copyOf(scores, scores.length);
    }

    /** 重新裁决区域显性派系（谁是老大） */
    public synchronized void recalculateDominance() {
        byte bestIdx = -1;
        float max = 0.0f;
        float runnerUp = 0.0f;

        for (byte i = 0; i < scores.length; i++) {
            float s = scores[i];
            if (s > max) {
                runnerUp = max;
                max = s;
                bestIdx = i;
            } else if (s > runnerUp) {
                runnerUp = s;
            }
        }

        if (bestIdx >= 0) {
            FactionDefinition def = FactionRegistry.getByIndex(bestIdx);
            double threshold = (def != null) ? def.minDominanceScore() : 1.0;
            if (max >= threshold) {
                this.dominantIndex = bestIdx;
                this.dominantScore = max;
                this.runnerUpScore = runnerUp;
                return;
            }
        }

        this.dominantIndex = -1;
        this.dominantScore = max;
        this.runnerUpScore = runnerUp;
    }

    /** 当前显性统治派系索引，-1 表示荒野/无统治 */
    public synchronized byte getDominantIndex() {
        return dominantIndex;
    }

    public synchronized float getDominantScore() {
        return dominantScore;
    }

    public synchronized float getRunnerUpScore() {
        return runnerUpScore;
    }

    public synchronized float getDominantMargin() {
        return Math.max(0.0f, dominantScore - runnerUpScore);
    }

    public synchronized boolean hasDominance() {
        return dominantIndex >= 0;
    }
}
