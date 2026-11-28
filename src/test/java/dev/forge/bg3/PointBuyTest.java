package dev.forge.bg3;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PointBuyTest {
    @Test
    void acceptsBothExtremesAndRejectsInvalidFinalArrays() {
        assertThat(PointBuy.exact(new int[]{17,16,15,8,8,8}).orElseThrow().total()).isEqualTo(72);
        assertThat(PointBuy.exact(new int[]{15,14,13,13,12,11}).orElseThrow().total()).isEqualTo(78);
        assertThat(PointBuy.exact(new int[]{15,15,15,15,8,8})).isEmpty();
        assertThat(PointBuy.exact(new int[]{18,13,13,12,10,8})).isEmpty();
        assertThat(new PointBuy(new int[]{8,8,8,8,8,8}, 0, 1).complete()).isFalse();
        assertThatThrownBy(() -> new PointBuy(new int[]{16,8,8,8,8,8}, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PointBuy(new int[]{7,8,8,8,8,8}, 0, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsLegalGuideScoresAndAdaptsDevelopedHeroesForCreation() {
        int[] cleric = {10,14,16,8,16,10};
        assertThat(PointBuy.recommended(cleric).totals()).containsExactly(cleric);
        int[] developed = {8,14,18,10,20,12};
        var start = PointBuy.recommended(developed);
        assertThat(start.complete()).isTrue();
        assertThat(start.plusTwo()).isEqualTo(4);
        assertThat(start.totals()[4]).isEqualTo(17);
        assertThat(developed[4]).isEqualTo(20);
    }
}
