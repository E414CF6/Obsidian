import assert from "node:assert/strict";
import {describe, it} from "node:test";
import {
    formatAdvancementName,
    formatBiomeName,
    formatMcIdentifier,
    SPECIALTY_KO,
} from "../src/locales/minecraft-ko.ts";

describe("Minecraft Korean locale helpers", () => {
    it("translates known items, biomes, advancements and formats unknown identifiers", () => {
        assert.equal(formatMcIdentifier("diamond_ore"), "다이아몬드 광석 (diamond_ore)");
        assert.equal(formatMcIdentifier("copper_bulb"), "Copper Bulb");
        assert.equal(formatBiomeName("cherry_grove"), "벚나무 숲");
        assert.equal(formatBiomeName("custom_valley"), "custom valley");
        assert.equal(formatAdvancementName("story/mine_diamond"), "다이아몬드!");
        assert.equal(formatAdvancementName("adventure/custom_quest"), "Custom Quest");
        assert.equal(SPECIALTY_KO.newcomer, "신입 모험가");
    });
});
