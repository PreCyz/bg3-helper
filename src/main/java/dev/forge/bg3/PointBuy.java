package dev.forge.bg3;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/** The purchased scores and the two creation bonuses, before feats or equipment. */
public record PointBuy(int[] base, int plusTwo, int plusOne) {
    public static final int BUDGET = 27;
    public static final List<Integer> COST = List.of(0, 1, 2, 3, 4, 5, 7, 9);
    static final String INVALID = "Choose six base scores from 8 to 15 and assign both creation bonuses.";

    public PointBuy {
        if (base == null || base.length != 6 || !inRange(base) || plusTwo < 0 || plusTwo >= 6 || plusOne < 0 || plusOne >= 6)
            throw new IllegalArgumentException(INVALID);
        base = base.clone();
    }

    private static boolean inRange(int[] scores) {
        return Arrays.stream(scores).allMatch(v -> v >= 8 && v <= 15);
    }

    public int spent() {
        return Arrays.stream(base).map(v -> COST.get(v - 8)).sum();
    }

    public int bonus(int ability) {
        if (ability == plusTwo) return 2;
        return ability == plusOne ? 1 : 0;
    }

    public int[] totals() {
        return IntStream.range(0, 6).map(i -> base[i] + bonus(i)).toArray();
    }

    public int total() {
        return Arrays.stream(totals()).sum();
    }

    public boolean complete() {
        return plusTwo != plusOne && spent() == BUDGET;
    }

    /** Reconstruct a legal allocation from the combined scores used by existing saves and guides. */
    static Optional<PointBuy> exact(int[] totals) {
        if (totals == null || totals.length != 6 || Arrays.stream(totals).anyMatch(v -> v < 8 || v > 17))
            return Optional.empty();
        for (int two = 0; two < 6; two++) {
            for (int one = 0; one < 6; one++) {
                if (two == one) continue;
                int[] base = totals.clone();
                base[two] -= 2;
                base[one]--;
                if (inRange(base)) {
                    var allocation = new PointBuy(base, two, one);
                    if (allocation.complete()) return Optional.of(allocation);
                }
            }
        }
        return Optional.empty();
    }

    static PointBuy recommended(int[] target) {
        return exact(target).orElseGet(() -> {
            // A developed hero's saved scores can exceed creation limits. Keep its ability priorities.
            var priority = IntStream.range(0, 6).boxed().sorted(Comparator.comparingInt((Integer i) -> target[i]).reversed()).toList();
            int[] standard = {15, 14, 13, 12, 10, 8};
            int[] base = new int[6];
            for (int i = 0; i < 6; i++) base[priority.get(i)] = standard[i];
            return new PointBuy(base, priority.get(0), priority.get(1));
        });
    }
}
