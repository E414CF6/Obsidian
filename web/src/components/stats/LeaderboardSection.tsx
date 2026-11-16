"use client";

import React from "react";
import {Flame} from "lucide-react";
import {LeaderboardMetric, MCMMO_SKILLS, PlayerSummary, ServerMeta} from "@/types/stats";
import {
    formatDistanceCm,
    formatMoney,
    formatPlaytime,
    formatSpecialtyTitle,
    resolveRankMeta,
} from "@/lib/formatters";

interface LeaderboardSectionProps {
    players: PlayerSummary[];
    selectedPlayer: PlayerSummary | null;
    leaderboardMetric: LeaderboardMetric;
    onSelectMetric: (metric: LeaderboardMetric) => void;
    onSelectPlayer: (uuid: string) => void;
    meta: ServerMeta;
    isDark: boolean;
    hasMcmmo: boolean;
    hasClaims: boolean;
}

function getPrimaryStatDisplay(
    player: PlayerSummary,
    metric: LeaderboardMetric,
    meta: ServerMeta,
    detailed: boolean,
): string {
    if (metric === "playtime") {
        return formatPlaytime(player.totalPlayTimeMs);
    }
    if (metric === "power") {
        return `Lv. ${player.powerLevel.toLocaleString()}`;
    }
    if (metric === "balance") {
        return formatMoney(player.balance, meta.currency);
    }
    if (metric === "claims") {
        return `${player.claims.usedBlocks.toLocaleString()} 블록² (${player.claims.claimCount}개)`;
    }
    if (metric === "mined") {
        return `${(player.vanillaStats?.totalMined || 0).toLocaleString()} 블록`;
    }
    if (metric === "advancements") {
        return detailed
            ? `${player.advancements?.totalCompleted || 0}개 달성 (바이옴 ${player.advancements?.visitedBiomes.length || 0}곳)`
            : `${player.advancements?.totalCompleted || 0}개 (바이옴 ${player.advancements?.visitedBiomes.length || 0}곳)`;
    }
    if (metric === "distance") {
        return formatDistanceCm(player.vanillaStats?.totalDistanceCm || 0);
    }
    if (metric === "survival") {
        const base = formatPlaytime(player.vanillaStats?.timeSinceDeathMs || 0);
        return detailed ? `${base} (사망 ${player.vanillaStats?.deaths || 0}회)` : base;
    }
    const s = player.skills[metric];
    return `Lv. ${s?.level || 0} (${(s?.xp || 0).toLocaleString()} XP)`;
}

export function LeaderboardSection({
    players,
    selectedPlayer,
    leaderboardMetric,
    onSelectMetric,
    onSelectPlayer,
    meta,
    isDark,
    hasMcmmo,
    hasClaims,
}: LeaderboardSectionProps) {
    const isMcmmoMetric =
        leaderboardMetric === "power" || MCMMO_SKILLS.some((s) => s.key === leaderboardMetric);

    const metricPills = React.useMemo(() => {
        const items: { id: LeaderboardMetric; label: string }[] = [
            {id: "playtime", label: "⏳ 총 플레이타임"},
        ];
        if (hasMcmmo) {
            items.push({id: "power", label: "⚔ 스킬 파워 레벨"});
        }
        items.push({id: "balance", label: "💰 보유 잔액 (Balance)"});
        if (hasClaims) {
            items.push({id: "claims", label: "🛡 영지 사용 면적"});
        }
        items.push(
            {id: "mined", label: "⛏ 누적 블록 채굴"},
            {id: "advancements", label: "🏅 발전 과제 달성"},
            {id: "distance", label: "👟 누적 이동 거리"},
            {id: "survival", label: "💀 무사 생존 시간"},
        );
        return items;
    }, [hasMcmmo, hasClaims]);

    return (
        <section className="space-y-3 sm:space-y-4">
            {/* Metric Filter Pills */}
            <div
                className="rounded-2xl bg-white dark:bg-slate-900/70 border border-slate-200/90 dark:border-slate-800/90 p-3 sm:p-4 space-y-2.5 sm:space-y-3 shadow-xs dark:shadow-none">
                <div className="flex items-center justify-between">
                    <div
                        className="text-[11px] sm:text-xs font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
                        주요 랭킹 부문
                    </div>
                    <span className="text-[10px] text-slate-400 sm:hidden">좌우로 스크롤</span>
                </div>
                <div
                    className="flex overflow-x-auto no-scrollbar pb-0.5 -mx-1 px-1 sm:mx-0 sm:px-0 sm:pb-0 sm:flex-wrap gap-1.5 sm:gap-2">
                    {metricPills.map((item) => {
                        const isActive = item.id === "power" ? isMcmmoMetric : leaderboardMetric === item.id;
                        return (
                            <button
                                key={item.id}
                                onClick={() => onSelectMetric(item.id)}
                                className={`shrink-0 whitespace-nowrap px-3 sm:px-3.5 py-1.5 rounded-xl text-xs font-semibold border transition ${
                                    isActive
                                        ? "bg-cyan-500/15 dark:bg-cyan-500/20 border-cyan-500 dark:border-cyan-400 text-cyan-800 dark:text-cyan-200"
                                        : "bg-slate-100/80 dark:bg-slate-950/60 border-slate-200 dark:border-slate-800 text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200"
                                }`}
                            >
                                {item.label}
                            </button>
                        );
                    })}
                </div>

                {hasMcmmo && isMcmmoMetric && (
                    <div className="pt-2.5 border-t border-slate-200/80 dark:border-slate-800/70">
                        <div
                            className="text-[11px] sm:text-xs font-semibold uppercase tracking-wider text-purple-600 dark:text-purple-400 mb-1.5 sm:mb-2 flex items-center gap-1.5">
                            <Flame className="w-3.5 h-3.5"/>
                            <span>스킬별 랭킹</span>
                        </div>
                        <div
                            className="flex overflow-x-auto no-scrollbar pb-0.5 -mx-1 px-1 sm:mx-0 sm:px-0 sm:pb-0 sm:flex-wrap gap-1.5">
                            <button
                                onClick={() => onSelectMetric("power")}
                                className={`shrink-0 whitespace-nowrap px-2.5 py-1 rounded-lg text-xs font-semibold border transition ${
                                    leaderboardMetric === "power"
                                        ? "bg-purple-500/15 dark:bg-purple-500/25 border-purple-500 dark:border-purple-400 text-purple-800 dark:text-purple-200"
                                        : "bg-slate-100/70 dark:bg-slate-950/50 border-slate-200/80 dark:border-slate-800/80 text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200"
                                }`}
                            >
                                전체 파워 레벨
                            </button>
                            {MCMMO_SKILLS.map((skill) => (
                                <button
                                    key={skill.key}
                                    onClick={() => onSelectMetric(skill.key)}
                                    className={`shrink-0 whitespace-nowrap px-2.5 py-1 rounded-lg text-xs font-medium border transition ${
                                        leaderboardMetric === skill.key
                                            ? "bg-purple-500/15 dark:bg-purple-500/25 border-purple-500 dark:border-purple-400 text-purple-800 dark:text-purple-200"
                                            : "bg-slate-100/70 dark:bg-slate-950/50 border-slate-200/80 dark:border-slate-800/80 text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200"
                                    }`}
                                >
                                    {skill.labelKo} ({skill.labelEn})
                                </button>
                            ))}
                        </div>
                    </div>
                )}
            </div>

            {/* Leaderboard Container: Mobile Cards + Desktop Table */}
            <div
                className="rounded-2xl bg-white dark:bg-slate-900/70 border border-slate-200/90 dark:border-slate-800/90 overflow-hidden shadow-xs dark:shadow-none">
                {/* Mobile Card List (< md) */}
                <div className="md:hidden divide-y divide-slate-200/70 dark:divide-slate-800/60">
                    {players.map((player, idx) => {
                        const rMeta = resolveRankMeta(player.rank, player.rankId, meta.ranks);
                        const rColor = isDark ? rMeta.color : rMeta.lightColor;
                        const isSelected = selectedPlayer?.uuid === player.uuid;
                        const primaryStatDisplay = getPrimaryStatDisplay(player, leaderboardMetric, meta, false);
                        const specialtyTitle = formatSpecialtyTitle(player.specialty);

                        return (
                            <button
                                key={player.uuid}
                                type="button"
                                onClick={() => onSelectPlayer(player.uuid)}
                                className={`w-full text-left p-3.5 transition active:bg-slate-100 dark:active:bg-slate-800/70 ${
                                    isSelected
                                        ? "bg-cyan-500/10 dark:bg-cyan-500/15"
                                        : "hover:bg-slate-50 dark:hover:bg-slate-800/40"
                                }`}
                            >
                                <div className="flex items-center justify-between gap-2.5">
                                    <div className="flex items-center gap-2.5 min-w-0">
                                        <span
                                            className={`inline-flex items-center justify-center w-7 h-7 rounded-lg text-xs font-bold shrink-0 ${
                                                idx === 0
                                                    ? "bg-amber-400/20 text-amber-700 dark:text-amber-300 border border-amber-400/50"
                                                    : idx === 1
                                                        ? "bg-slate-300/40 dark:bg-slate-300/20 text-slate-700 dark:text-slate-200 border border-slate-400/40 dark:border-slate-300/40"
                                                        : idx === 2
                                                            ? "bg-amber-700/15 dark:bg-amber-700/25 text-amber-800 dark:text-amber-400 border border-amber-600/40"
                                                            : "bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-400"
                                            }`}
                                        >
                                            #{idx + 1}
                                        </span>

                                        <div className="relative shrink-0">
                                            <img
                                                src={`https://mc-heads.net/avatar/${encodeURIComponent(player.uuid)}/40`}
                                                alt={player.username}
                                                className="w-9 h-9 rounded-lg bg-slate-200 dark:bg-slate-800 border border-slate-300 dark:border-slate-700"
                                            />
                                            <span
                                                className={`absolute -bottom-0.5 -right-0.5 w-2.5 h-2.5 rounded-full border-2 border-white dark:border-slate-900 ${
                                                    player.isOnline
                                                        ? "bg-emerald-500 dark:bg-emerald-400"
                                                        : "bg-slate-400 dark:bg-slate-600"
                                                }`}
                                            />
                                        </div>

                                        <div className="min-w-0">
                                            <div className="flex items-center gap-1.5 flex-wrap">
                                                <span className="font-bold text-sm text-slate-900 dark:text-white truncate">
                                                    {player.username}
                                                </span>
                                                <span
                                                    className="px-1.5 py-0.5 rounded text-[10px] font-semibold border"
                                                    style={{
                                                        color: rColor,
                                                        borderColor: `${rColor}40`,
                                                        backgroundColor: `${rColor}15`,
                                                    }}
                                                >
                                                    {rMeta.label}
                                                </span>
                                                {player.primaryGroup === "admin" && (
                                                    <span
                                                        className="px-1.5 py-0.5 rounded text-[10px] font-semibold bg-rose-500/15 text-rose-600 dark:text-rose-400 border border-rose-500/30">
                                                        ADMIN
                                                    </span>
                                                )}
                                            </div>
                                            <div className="text-[11px] text-slate-500 dark:text-slate-400 truncate mt-0.5">
                                                {specialtyTitle || `${player.uuid.slice(0, 8)}...`}
                                            </div>
                                        </div>
                                    </div>

                                    <div className="text-right shrink-0">
                                        <div className="text-xs font-bold text-cyan-700 dark:text-cyan-300">
                                            {primaryStatDisplay}
                                        </div>
                                        <div className="text-[10px] text-slate-400 mt-0.5">상세 보기 →</div>
                                    </div>
                                </div>

                                <div
                                    className="mt-2 pt-2 border-t border-slate-100 dark:border-slate-800/60 flex items-center justify-between text-[11px] text-slate-500 dark:text-slate-400">
                                    {hasMcmmo && (
                                        <span>
                                            파워{" "}
                                            <strong className="text-purple-600 dark:text-purple-300">
                                                Lv.{player.powerLevel.toLocaleString()}
                                            </strong>
                                        </span>
                                    )}
                                    <span>
                                        잔액{" "}
                                        <strong className="text-amber-600 dark:text-amber-300">
                                            {formatMoney(player.balance, meta.currency)}
                                        </strong>
                                    </span>
                                    <span>
                                        플레이{" "}
                                        <strong className="text-slate-700 dark:text-slate-300">
                                            {formatPlaytime(player.totalPlayTimeMs)}
                                        </strong>
                                    </span>
                                </div>
                            </button>
                        );
                    })}

                    {players.length === 0 && (
                        <div className="py-10 text-center text-xs text-slate-500">
                            일치하는 플레이어가 없습니다.
                        </div>
                    )}
                </div>

                {/* Desktop Table (md and up) */}
                <div className="hidden md:block overflow-x-auto">
                    <table className="w-full text-left border-collapse">
                        <thead>
                        <tr className="border-b border-slate-200 dark:border-slate-800 bg-slate-100/70 dark:bg-slate-950/60 text-[11px] font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
                            <th className="py-3.5 px-4 w-16">순위</th>
                            <th className="py-3.5 px-4">플레이어</th>
                            <th className="py-3.5 px-4">랭크 / 성향</th>
                            <th className="py-3.5 px-4 text-right">선택 부문</th>
                            {hasMcmmo && (
                                <th className="py-3.5 px-4 text-right hidden md:table-cell">파워 레벨</th>
                            )}
                            <th className="py-3.5 px-4 text-right hidden sm:table-cell">잔액</th>
                            <th className="py-3.5 px-4 text-right hidden lg:table-cell">플레이타임</th>
                        </tr>
                        </thead>
                        <tbody className="divide-y divide-slate-200/70 dark:divide-slate-800/60 text-sm">
                        {players.map((player, idx) => {
                            const rMeta = resolveRankMeta(player.rank, player.rankId, meta.ranks);
                            const rColor = isDark ? rMeta.color : rMeta.lightColor;
                            const isSelected = selectedPlayer?.uuid === player.uuid;
                            const primaryStatDisplay = getPrimaryStatDisplay(player, leaderboardMetric, meta, true);
                            const specialtyTitle = formatSpecialtyTitle(player.specialty);

                            return (
                                <tr
                                    key={player.uuid}
                                    onClick={() => onSelectPlayer(player.uuid)}
                                    className={`cursor-pointer transition ${
                                        isSelected
                                            ? "bg-cyan-500/10 dark:bg-cyan-500/15"
                                            : "hover:bg-slate-100/80 dark:hover:bg-slate-800/50"
                                    }`}
                                >
                                    <td className="py-3.5 px-4 font-bold">
                                        <span
                                            className={`inline-flex items-center justify-center w-7 h-7 rounded-lg text-xs ${
                                                idx === 0
                                                    ? "bg-amber-400/20 text-amber-700 dark:text-amber-300 border border-amber-400/50"
                                                    : idx === 1
                                                        ? "bg-slate-300/40 dark:bg-slate-300/20 text-slate-700 dark:text-slate-200 border border-slate-400/40 dark:border-slate-300/40"
                                                        : idx === 2
                                                            ? "bg-amber-700/15 dark:bg-amber-700/25 text-amber-800 dark:text-amber-400 border border-amber-600/40"
                                                            : "bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-400"
                                            }`}
                                        >
                                            #{idx + 1}
                                        </span>
                                    </td>
                                    <td className="py-3.5 px-4">
                                        <div className="flex items-center gap-3">
                                            <img
                                                src={`https://mc-heads.net/avatar/${encodeURIComponent(player.uuid)}/40`}
                                                alt={player.username}
                                                className="w-9 h-9 rounded-lg bg-slate-200 dark:bg-slate-800 border border-slate-300 dark:border-slate-700"
                                            />
                                            <div>
                                                <div className="flex items-center gap-2 font-semibold text-slate-900 dark:text-white">
                                                    {player.username}
                                                    {player.isOnline && (
                                                        <span
                                                            title="온라인"
                                                            className="w-2 h-2 rounded-full bg-emerald-500 dark:bg-emerald-400"
                                                        />
                                                    )}
                                                </div>
                                                <div className="text-xs text-slate-500 font-mono">
                                                    {player.uuid.slice(0, 8)}...
                                                </div>
                                            </div>
                                        </div>
                                    </td>
                                    <td className="py-3.5 px-4">
                                        <div className="flex items-center gap-1.5 flex-wrap">
                                            <span
                                                className="px-2 py-0.5 rounded-md text-xs font-semibold border"
                                                style={{
                                                    color: rColor,
                                                    borderColor: `${rColor}40`,
                                                    backgroundColor: `${rColor}15`,
                                                }}
                                            >
                                                {rMeta.label}
                                            </span>
                                            {player.primaryGroup === "admin" && (
                                                <span
                                                    className="px-2 py-0.5 rounded-md text-[11px] font-semibold bg-rose-500/15 text-rose-600 dark:text-rose-400 border border-rose-500/30">
                                                    ADMIN
                                                </span>
                                            )}
                                            {specialtyTitle && (
                                                <span className="text-[11px] text-slate-500 dark:text-slate-400">
                                                    {specialtyTitle}
                                                </span>
                                            )}
                                        </div>
                                    </td>
                                    <td className="py-3.5 px-4 text-right font-bold text-cyan-700 dark:text-cyan-300">
                                        {primaryStatDisplay}
                                    </td>
                                    {hasMcmmo && (
                                        <td className="py-3.5 px-4 text-right hidden md:table-cell text-purple-600 dark:text-purple-300 font-medium">
                                            Lv. {player.powerLevel.toLocaleString()}
                                        </td>
                                    )}
                                    <td className="py-3.5 px-4 text-right hidden sm:table-cell text-amber-600 dark:text-amber-300 font-medium">
                                        {formatMoney(player.balance, meta.currency)}
                                    </td>
                                    <td className="py-3.5 px-4 text-right hidden lg:table-cell text-slate-600 dark:text-slate-300">
                                        {formatPlaytime(player.totalPlayTimeMs)}
                                    </td>
                                </tr>
                            );
                        })}

                        {players.length === 0 && (
                            <tr>
                                <td colSpan={hasMcmmo ? 7 : 6} className="py-10 text-center text-xs text-slate-500">
                                    일치하는 플레이어가 없습니다.
                                </td>
                            </tr>
                        )}
                        </tbody>
                    </table>
                </div>
            </div>
        </section>
    );
}
