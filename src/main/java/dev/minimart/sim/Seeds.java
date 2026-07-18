package dev.minimart.sim;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Every random choice in the simulation is a pure function of
 * (runId, agentId, tick, step). Nothing calls Math.random or UUID.randomUUID.
 *
 * That is what makes two runs of the same seed comparable, which is the whole
 * basis of running an experiment: if the population differs between arms, any
 * difference you measure is noise wearing a lab coat.
 */
public final class Seeds {

    private Seeds() {}

    private static long mix(String runId, int agentId, int tick, String step) {
        long h = 0xcbf29ce484222325L;                       // FNV-1a over the tuple
        for (byte b : (runId + '|' + agentId + '|' + tick + '|' + step).getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xffL);
            h *= 0x100000001b3L;
        }
        // splitmix64 finaliser, so neighbouring tuples do not correlate
        h ^= (h >>> 30); h *= 0xbf58476d1ce4e5b9L;
        h ^= (h >>> 27); h *= 0x94d049bb133111ebL;
        h ^= (h >>> 31);
        return h;
    }

    /** A stable value in [0,1). */
    public static double unit(String runId, int agentId, int tick, String step) {
        return (mix(runId, agentId, tick, step) >>> 11) / (double) (1L << 53);
    }

    public static int intIn(String runId, int agentId, int tick, String step, int bound) {
        return (int) Math.floor(unit(runId, agentId, tick, step) * bound);
    }

    /** A derived id: the same tuple always yields the same order id, which is
     *  what makes a replayed tick idempotent rather than duplicating orders. */
    public static UUID uuid(String runId, int agentId, int tick, String step) {
        return UUID.nameUUIDFromBytes((runId + '|' + agentId + '|' + tick + '|' + step)
                .getBytes(StandardCharsets.UTF_8));
    }
}
