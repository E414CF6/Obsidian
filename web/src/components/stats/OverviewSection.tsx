"use client";

import React from "react";
import {Clock, Coins, Flame, Footprints, Medal, Pickaxe, Shield, Users} from "lucide-react";
import {ServerMeta, ServerOverview} from "@/types/stats";
import {formatDistanceCm, formatMoney, formatPlaytime, resolveRankMeta} from "@/lib/formatters";

interface OverviewSectionProps {
    overview: ServerOverview;
    meta: ServerMeta;
    isDark: boolean;
    hasMcmmo: boolean;
    hasClaims: boolean;
}

export function OverviewSection({
    overview,
    meta,
    isDark,
    hasMcmmo,
    hasClaims,
}: OverviewSectionProps) {
    const rankEntries = React.useMemo(() => {
        const seen = new Set<string>();
        const items: { key: string; label: string; count: number; color: string }[] = [];

        for (const r of meta.ranks) {
            const count = overview.rankDistribution?.[r.label] ?? overview.rankDistribution?.[r.id] ?? 0;
            seen.add(r.label.toLowerCase());
            seen.add(r.id.toLowerCase());
            items.push({
                key: r.id,
                label: r.label,
                count,
                color: isDark ? r.color : r.lightColor,
            });
        }

        for (const [label, count] of Object.entries(overview.rankDistribution || {})) {
            if (seen.has(label.toLowerCase())) continue;
            const resolved = resolveRankMeta(label, label, meta.ranks);
            items.push({
                key: label,
                label: resolved.label,
                count,
                color: isDark ? resolved.color : resolved.lightColor,
            });
        }

        return items;
    }, [meta.ranks, overview.rankDistribution, isDark]);

    const visibleKpiCount = 3 + (hasMcmmo ? 1 : 0) + (hasClaims ? 1 : 0);
    const gridColsClass =
        visibleKpiCount >= 5
            ? "grid-cols-2 md:grid-cols-3 lg:grid-cols-5"
            : visibleKpiCount === 4
                ? "grid-cols-2 lg:grid-cols-4"
                : "grid-cols-1 sm:grid-cols-3";

    return (
        <section className="space-y-2.5 sm:space-y-3.5">
            <div className={`grid ${gridColsClass} gap-2.5 sm:gap-3.5`}>
                <div
                    className="rounded-2xl bg-white dark:bg-slate-900/70 border border-slate-200/90 dark:border-slate-800/90 p-3 sm:p-4 flex flex-col justify-between shadow-xs dark:shadow-none">
                    <div
                        className="flex items-center justify-between text-slate-500 dark:text-slate-400 text-[11px] sm:text-xs font-medium">
                        <span>등록 플레이어</span>
                        <Users className="w-3.5 h-3.5 sm:w-4 sm:h-4 text-cyan-600 dark:text-cyan-400 shrink-0"/>
                    </div>
                    <div className="mt-1.5 sm:mt-2 flex items-baseline gap-1.5 sm:gap-2 flex-wrap">
                        <span className="text-lg sm:text-2xl font-bold text-slate-900 dark:text-white">
                            {overview.totalPlayers.toLocaleString()}명
                        </span>
                        <span className="text-[11px] sm:text-xs font-medium text-emerald-600 dark:text-emerald-400">
                            온라인 {overview.onlinePlayers}명
                        </span>
                    </div>
                </div>

                <div
                    className="rounded-2xl bg-white dark:bg-slate-900/70 border border-slate-200/90 dark:border-slate-800/90 p-3 sm:p-4 flex flex-col justify-between shadow-xs dark:shadow-none">
                    <div
                        className="flex items-center justify-between text-slate-500 dark:text-slate-400 text-[11px] sm:text-xs font-medium">
                        <span>누적 플레이타임</span>
                        <Clock className="w-3.5 h-3.5 sm:w-4 sm:h-4 text-blue-600 dark:text-blue-400 shrink-0"/>
                    </div>
                    <div className="mt-1.5 sm:mt-2">
                        <span className="text-base sm:text-xl font-bold text-slate-900 dark:text-white">
                            {formatPlaytime(overview.totalPlayTimeMs)}
                        </span>
                        <p className="text-[10px] sm:text-[11px] text-slate-500 mt-0.5">전체 기록</p>
                    </div>
                </div>

                <div
                    className="rounded-2xl bg-white dark:bg-slate-900/70 border border-slate-200/90 dark:border-slate-800/90 p-3 sm:p-4 flex flex-col justify-between shadow-xs dark:shadow-none">
                    <div
                        className="flex items-center justify-between text-slate-500 dark:text-slate-400 text-[11px] sm:text-xs font-medium">
                        <span>총 유통 화폐</span>
                        <Coins className="w-3.5 h-3.5 sm:w-4 sm:h-4 text-amber-500 dark:text-amber-400 shrink-0"/>
                    </div>
                    <div className="mt-1.5 sm:mt-2">
                        <span className="text-base sm:text-xl font-bold text-amber-600 dark:text-amber-300 break-all">
                            {formatMoney(overview.totalBalance, meta.currency)}
                        </span>
                        <p className="text-[10px] sm:text-[11px] text-slate-500 mt-0.5">
                            전체 유저 보유 잔액 합계
                        </p>
                    </div>
                </div>

                {hasMcmmo && (
                    <div
                        className="rounded-2xl bg-white dark:bg-slate-900/70 border border-slate-200/90 dark:border-slate-800/90 p-3 sm:p-4 flex flex-col justify-between shadow-xs dark:shadow-none">
                        <div
                            className="flex items-center justify-between text-slate-500 dark:text-slate-400 text-[11px] sm:text-xs font-medium">
                            <span>총 파워 레벨</span>
                            <Flame className="w-3.5 h-3.5 sm:w-4 sm:h-4 text-purple-600 dark:text-purple-400 shrink-0"/>
                        </div>
                        <div className="mt-1.5 sm:mt-2">
                            <span className="text-lg sm:text-2xl font-bold text-purple-600 dark:text-purple-300">
                                Lv. {overview.totalPowerLevel.toLocaleString()}
                            </span>
                            <p className="text-[10px] sm:text-[11px] text-slate-500 mt-0.5">
                                17종 스킬 레벨 총합
                            </p>
                        </div>
                    </div>
                )}

                {hasClaims && (
                    <div
                        className="col-span-2 md:col-span-1 rounded-2xl bg-white dark:bg-slate-900/70 border border-slate-200/90 dark:border-slate-800/90 p-3 sm:p-4 flex flex-col justify-between shadow-xs dark:shadow-none">
                        <div
                            className="flex items-center justify-between text-slate-500 dark:text-slate-400 text-[11px] sm:text-xs font-medium">
                            <span>보호된 영지</span>
                            <Shield className="w-3.5 h-3.5 sm:w-4 sm:h-4 text-emerald-600 dark:text-emerald-400 shrink-0"/>
                        </div>
                        <div className="mt-1.5 sm:mt-2 flex items-baseline gap-2">
                            <span className="text-lg sm:text-2xl font-bold text-emerald-600 dark:text-emerald-300">
                                {overview.totalClaims}개
                            </span>
                            <span className="text-xs text-slate-500 dark:text-slate-400">
                                ({overview.totalClaimedArea.toLocaleString()} 블록²)
                            </span>
                        </div>
                    </div>
                )}
            </div>

            {/* Secondary Server Activity & Dynamic Rank Distribution Bar */}
            <div
                className="rounded-2xl bg-white dark:bg-slate-900/70 border border-slate-200/90 dark:border-slate-800/90 p-3 sm:px-4 sm:py-3 flex flex-col lg:flex-row lg:items-center justify-between gap-2.5 sm:gap-4 shadow-xs dark:shadow-none">
                <div className="flex items-center gap-1.5 sm:gap-2 overflow-x-auto no-scrollbar sm:flex-wrap">
                    <span
                        className="text-[11px] sm:text-xs font-semibold text-slate-500 dark:text-slate-400 mr-0.5 shrink-0">
                        서버 등급 분포:
                    </span>
                    {rankEntries.map((r) => (
                        <span
                            key={r.key}
                            className="inline-flex items-center gap-1.5 px-2 sm:px-2.5 py-0.5 rounded-lg text-[11px] sm:text-xs font-medium border shrink-0"
                            style={{
                                color: r.color,
                                borderColor: `${r.color}40`,
                                backgroundColor: `${r.color}12`,
                            }}
                        >
                            <span>{r.label}</span>
                            <strong className="font-bold">{r.count}</strong>
                        </span>
                    ))}
                    {rankEntries.length === 0 && (
                        <span className="text-xs text-slate-400">등록된 등급 없음</span>
                    )}
                </div>

                <div
                    className="flex flex-wrap items-center gap-x-4 sm:gap-x-5 gap-y-1 text-[11px] sm:text-xs text-slate-600 dark:text-slate-400 pt-2 border-t border-slate-200/70 dark:border-slate-800/70 lg:pt-0 lg:border-t-0">
                    <span className="inline-flex items-center gap-1.5">
                        <Pickaxe className="w-3.5 h-3.5 text-sky-500 shrink-0"/>
                        총 채굴 블록:{" "}
                        <strong className="text-slate-900 dark:text-white">
                            {(overview.totalMinedBlocks || 0).toLocaleString()}개
                        </strong>
                    </span>
                    <span className="inline-flex items-center gap-1.5">
                        <Footprints className="w-3.5 h-3.5 text-emerald-500 shrink-0"/>
                        누적 이동 거리:{" "}
                        <strong className="text-slate-900 dark:text-white">
                            {formatDistanceCm((overview.totalDistanceMeters || 0) * 100)}
                        </strong>
                    </span>
                    <span className="inline-flex items-center gap-1.5">
                        <Medal className="w-3.5 h-3.5 text-amber-500 shrink-0"/>
                        달성 발전 과제:{" "}
                        <strong className="text-slate-900 dark:text-white">
                            {(overview.totalAdvancements || 0).toLocaleString()}건
                        </strong>
                    </span>
                </div>
            </div>
        </section>
    );
}
