import {
    CapabilitiesMeta,
    CurrencyMeta,
    PlayerSpecialty,
    RankTierMeta,
    ServerMeta,
} from "@/types/stats";
import {SPECIALTY_KO} from "@/locales/minecraft-ko";

export const DEFAULT_CURRENCY_META: CurrencyMeta = {
    symbol: "$",
    name: "Dollar",
    namePlural: "Dollars",
    formatPattern: "#,##0.##",
};

export const DEFAULT_CAPABILITIES_META: CapabilitiesMeta = {
    mcmmo: false,
    claims: false,
    economy: true,
    playtime: true,
};

const NEUTRAL_RANK_COLORS = [
    {color: "#9ca3af", lightColor: "#4b5563"},
    {color: "#4ade80", lightColor: "#15803d"},
    {color: "#38bdf8", lightColor: "#0369a1"},
    {color: "#fbbf24", lightColor: "#b45309"},
    {color: "#c084fc", lightColor: "#7e22ce"},
    {color: "#f87171", lightColor: "#b91c1c"},
];

export function resolveServerMeta(meta?: ServerMeta): ServerMeta {
    return {
        serverName: meta?.serverName?.trim() || process.env.NEXT_PUBLIC_SERVER_NAME?.trim() || "Obsidian",
        currency: meta?.currency || DEFAULT_CURRENCY_META,
        ranks: meta?.ranks || [],
        capabilities: meta?.capabilities || DEFAULT_CAPABILITIES_META,
    };
}

export function resolveRankMeta(
    rankLabel: string,
    rankId: string | undefined,
    ranks: RankTierMeta[],
): RankTierMeta {
    const labelNeedle = (rankLabel || "").trim().toLowerCase();
    const idNeedle = (rankId || "").trim().toLowerCase();
    const found = ranks.find(
        (r) =>
            (idNeedle && r.id.toLowerCase() === idNeedle) ||
            (labelNeedle && (r.label.toLowerCase() === labelNeedle || r.id.toLowerCase() === labelNeedle)),
    );
    if (found) return found;

    const fallbackIndex = Math.abs(
        Array.from(labelNeedle || idNeedle || "default").reduce((acc, ch) => acc + ch.charCodeAt(0), 0),
    ) % NEUTRAL_RANK_COLORS.length;
    const palette = NEUTRAL_RANK_COLORS[fallbackIndex];
    return {
        id: rankId || rankLabel || "default",
        label: rankLabel || rankId || "Default",
        nextRank: null,
        requiredPlaytimeSeconds: 0,
        cost: 0,
        color: palette.color,
        lightColor: palette.lightColor,
    };
}

export function resolveNextRankMeta(current: RankTierMeta, ranks: RankTierMeta[]): RankTierMeta | null {
    if (!current.nextRank) return null;
    const needle = current.nextRank.toLowerCase();
    return ranks.find((r) => r.id.toLowerCase() === needle || r.label.toLowerCase() === needle) || null;
}

export function formatSpecialtyTitle(specialty?: PlayerSpecialty): string {
    if (!specialty) return "";
    if (specialty.key && SPECIALTY_KO[specialty.key]) {
        return SPECIALTY_KO[specialty.key];
    }
    return specialty.title || "";
}

export function formatPlaytime(ms: number): string {
    if (!ms || ms <= 0) return "0분";
    const totalSeconds = Math.floor(ms / 1000);
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const seconds = totalSeconds % 60;
    if (hours > 0) {
        return `${hours.toLocaleString()}시간 ${minutes}분`;
    }
    if (minutes > 0) {
        return `${minutes}분 ${seconds}초`;
    }
    return `${seconds}초`;
}

export function formatDistanceCm(cm: number): string {
    if (!cm || cm <= 0) return "0 m";
    const meters = cm / 100;
    if (meters >= 1000) {
        return `${(meters / 1000).toLocaleString(undefined, {
            minimumFractionDigits: 2,
            maximumFractionDigits: 2,
        })} km`;
    }
    return `${Math.round(meters).toLocaleString()} m`;
}

export function formatMoney(amount: number, currency: CurrencyMeta = DEFAULT_CURRENCY_META): string {
    const symbol = currency.symbol || "$";
    return `${symbol}${amount.toLocaleString(undefined, {
        minimumFractionDigits: 0,
        maximumFractionDigits: 2,
    })}`;
}

export function formatTimestamp(ms: number): string {
    if (!ms || ms <= 0) return "기록 없음";
    const d = new Date(ms);
    if (Number.isNaN(d.getTime())) return "기록 없음";
    return d.toLocaleString("ko-KR", {
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
    });
}
