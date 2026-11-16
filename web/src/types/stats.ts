export type SkillKey =
    | "mining"
    | "woodcutting"
    | "herbalism"
    | "excavation"
    | "fishing"
    | "swords"
    | "axes"
    | "unarmed"
    | "archery"
    | "taming"
    | "crossbows"
    | "tridents"
    | "maces"
    | "spears"
    | "repair"
    | "acrobatics"
    | "alchemy";

export type SkillCategory = "gathering" | "combat" | "misc";

export interface SkillMeta {
    key: SkillKey;
    labelKo: string;
    labelEn: string;
    category: SkillCategory;
    color: string;
    lightColor: string;
    icon: string;
}

export const MCMMO_SKILLS: SkillMeta[] = [
    // Gathering (채집)
    {key: "mining", labelKo: "채광", labelEn: "Mining", category: "gathering", color: "#38bdf8", lightColor: "#0284c7", icon: "Pickaxe"},
    {key: "woodcutting", labelKo: "벌목", labelEn: "Woodcutting", category: "gathering", color: "#4ade80", lightColor: "#16a34a", icon: "Trees"},
    {key: "herbalism", labelKo: "약초학", labelEn: "Herbalism", category: "gathering", color: "#a3e635", lightColor: "#4d7c0f", icon: "Sprout"},
    {key: "excavation", labelKo: "발굴", labelEn: "Excavation", category: "gathering", color: "#fbbf24", lightColor: "#d97706", icon: "Shovel"},
    {key: "fishing", labelKo: "낚시", labelEn: "Fishing", category: "gathering", color: "#60a5fa", lightColor: "#2563eb", icon: "Fish"},

    // Combat (전투)
    {key: "swords", labelKo: "검술", labelEn: "Swords", category: "combat", color: "#f87171", lightColor: "#dc2626", icon: "Sword"},
    {key: "axes", labelKo: "도끼술", labelEn: "Axes", category: "combat", color: "#fb923c", lightColor: "#ea580c", icon: "Axe"},
    {key: "unarmed", labelKo: "격투", labelEn: "Unarmed", category: "combat", color: "#f43f5e", lightColor: "#e11d48", icon: "HandMetal"},
    {key: "archery", labelKo: "궁술", labelEn: "Archery", category: "combat", color: "#c084fc", lightColor: "#9333ea", icon: "Crosshair"},
    {key: "taming", labelKo: "조련", labelEn: "Taming", category: "combat", color: "#e879f9", lightColor: "#c026d3", icon: "PawPrint"},
    {key: "crossbows", labelKo: "석궁", labelEn: "Crossbows", category: "combat", color: "#a78bfa", lightColor: "#7c3aed", icon: "Target"},
    {key: "tridents", labelKo: "삼지창", labelEn: "Tridents", category: "combat", color: "#2dd4bf", lightColor: "#0d9488", icon: "Anchor"},
    {key: "maces", labelKo: "철퇴", labelEn: "Maces", category: "combat", color: "#fb7185", lightColor: "#e11d48", icon: "Hammer"},
    {key: "spears", labelKo: "창술", labelEn: "Spears", category: "combat", color: "#f97316", lightColor: "#ea580c", icon: "Compass"},

    // Misc (기타/보조)
    {key: "repair", labelKo: "수리", labelEn: "Repair", category: "misc", color: "#94a3b8", lightColor: "#475569", icon: "Wrench"},
    {key: "acrobatics", labelKo: "곡예", labelEn: "Acrobatics", category: "misc", color: "#34d399", lightColor: "#059669", icon: "Feather"},
    {key: "alchemy", labelKo: "연금술", labelEn: "Alchemy", category: "misc", color: "#818cf8", lightColor: "#4f46e5", icon: "FlaskConical"},
];

export interface CurrencyMeta {
    symbol: string;
    name: string;
    namePlural: string;
    formatPattern: string;
}

export interface RankTierMeta {
    id: string;
    label: string;
    nextRank: string | null;
    requiredPlaytimeSeconds: number;
    cost: number;
    color: string;
    lightColor: string;
}

export interface CapabilitiesMeta {
    mcmmo: boolean;
    claims: boolean;
    economy: boolean;
    playtime: boolean;
}

export interface ServerMeta {
    serverName: string;
    currency: CurrencyMeta;
    ranks: RankTierMeta[];
    capabilities: CapabilitiesMeta;
}

export interface ClaimItem {
    id: number;
    world: string;
    minX: number;
    minY: number;
    minZ: number;
    maxX: number;
    maxY: number;
    maxZ: number;
    width: number;
    length: number;
    area: number;
    builders: string[];
    containers: string[];
    accessors: string[];
    managers: string[];
}

export interface PlaytimeDayRecord {
    dateInt: number;
    dateFormatted: string;
    hours: number[]; // length 24, in milliseconds
    totalMs: number;
}

export interface PlayerSkillStat {
    level: number;
    xp: number;
    xpToNext: number;
}

export interface VanillaStatItem {
    id: string;
    count: number;
}

export interface PlayerVanillaStats {
    deaths: number;
    timeSinceDeathMs: number;
    mobKills: number;
    playerKills: number;
    damageDealt: number;
    damageTaken: number;
    jumps: number;
    leaveGameCount: number;
    walkCm: number;
    sprintCm: number;
    swimCm: number;
    flyCm: number;
    vehicleCm: number;
    totalDistanceCm: number;
    totalMined: number;
    topMined: VanillaStatItem[];
    totalUsed: number;
    topUsed: VanillaStatItem[];
    totalCrafted: number;
    topCrafted: VanillaStatItem[];
    totalBroken: number;
    totalPickedUp: number;
}

export interface RecentAdvancementItem {
    key: string;
    category: "story" | "adventure" | "nether" | "end" | "husbandry" | "other";
    completedAt: string;
}

export interface VisitedBiomeItem {
    id: string;
    visitedAt: string;
}

export interface PlayerAdvancementStats {
    totalCompleted: number;
    categories: {
        story: number;
        adventure: number;
        nether: number;
        end: number;
        husbandry: number;
    };
    recent: RecentAdvancementItem[];
    visitedBiomes: VisitedBiomeItem[];
    totalBiomesTarget: number;
}

export interface PlayerSpecialty {
    key?: string;
    title: string;
    category: SkillCategory | "balanced";
    topSkillKey: SkillKey | null;
    gatheringLevel: number;
    combatLevel: number;
    miscLevel: number;
}

export interface PlayerSummary {
    uuid: string;
    username: string;
    nickname: string | null;
    displayName: string | null;
    primaryGroup: string;
    rank: string;
    rankId?: string;
    balance: number;
    totalPlayTimeMs: number;
    lastLoginTime: number;
    lastLogoffTime: number;
    isOnline: boolean;
    votes: number;
    powerLevel: number;
    skills: Record<SkillKey, PlayerSkillStat>;
    specialty: PlayerSpecialty;
    claims: {
        accruedBlocks: number;
        bonusBlocks: number;
        totalAvailableBlocks: number;
        usedBlocks: number;
        remainingBlocks: number;
        claimCount: number;
        items: ClaimItem[];
    };
    playtimeHistory: PlaytimeDayRecord[];
    hourlyDistribution: number[]; // 24 hours aggregated in ms
    vanillaStats: PlayerVanillaStats;
    advancements: PlayerAdvancementStats;
}

export interface ServerOverview {
    totalPlayers: number;
    onlinePlayers: number;
    totalBalance: number;
    totalPlayTimeMs: number;
    totalPowerLevel: number;
    totalClaims: number;
    totalClaimedArea: number;
    totalMinedBlocks: number;
    totalMobKills: number;
    totalDistanceMeters: number;
    totalAdvancements: number;
    rankDistribution: Record<string, number>;
    queriedAt: string;
    sourceMode: "obsidian-native" | "remote-api-proxy";
}

export interface StatsApiResponse {
    overview: ServerOverview;
    players: PlayerSummary[];
    meta?: ServerMeta;
}

export interface PlayerDetailApiResponse {
    player: PlayerSummary;
    queriedAt: string;
    sourceMode: "obsidian-native" | "remote-api-proxy";
    meta?: ServerMeta;
}

export type LeaderboardMetric =
    | "playtime"
    | "power"
    | "balance"
    | "claims"
    | "mined"
    | "advancements"
    | "distance"
    | "survival"
    | SkillKey;
