"use client";

import React, {useMemo, useState} from "react";
import {
    Award,
    Check,
    Clock,
    Coins,
    Compass,
    Copy,
    Flame,
    Footprints,
    HeartCrack,
    MapPin,
    Medal,
    Shield,
    Skull,
    Sword,
    Wrench,
    X,
} from "lucide-react";
import {MCMMO_SKILLS, PlayerSummary, ServerMeta, SkillCategory} from "@/types/stats";
import {formatAdvancementName, formatBiomeName, formatMcIdentifier} from "@/locales/minecraft-ko";
import {
    formatDistanceCm,
    formatMoney,
    formatPlaytime,
    formatSpecialtyTitle,
    formatTimestamp,
    resolveNextRankMeta,
    resolveRankMeta,
} from "@/lib/formatters";

interface PlayerDetailModalProps {
    player: PlayerSummary;
    meta: ServerMeta;
    isDark: boolean;
    hasMcmmo: boolean;
    hasClaims: boolean;
    onClose: () => void;
}

export function PlayerDetailModal({
    player,
    meta,
    isDark,
    hasMcmmo,
    hasClaims,
    onClose,
}: PlayerDetailModalProps) {
    const [skillCategory, setSkillCategory] = useState<"all" | SkillCategory>("all");
    const [copiedClaimId, setCopiedClaimId] = useState<number | null>(null);

    const visibleSkills = useMemo(() => {
        if (skillCategory === "all") return MCMMO_SKILLS;
        return MCMMO_SKILLS.filter((s) => s.category === skillCategory);
    }, [skillCategory]);

    const rankMeta = resolveRankMeta(player.rank, player.rankId, meta.ranks);
    const nextRankMeta = resolveNextRankMeta(rankMeta, meta.ranks);
    const activeRankColor = isDark ? rankMeta.color : rankMeta.lightColor;
    const activeNextRankColor = nextRankMeta ? (isDark ? nextRankMeta.color : nextRankMeta.lightColor) : "";
    const specialtyTitle = formatSpecialtyTitle(player.specialty);

    const handleCopyClaimCoords = (claimId: number, x: number, y: number, z: number) => {
        navigator.clipboard?.writeText(`${x} ${y} ${z}`);
        setCopiedClaimId(claimId);
        setTimeout(() => setCopiedClaimId(null), 1800);
    };

    const showPlayerMcmmo = hasMcmmo || player.powerLevel > 0;
    const showPlayerClaims = hasClaims || player.claims.claimCount > 0 || player.claims.totalAvailableBlocks > 0;

    return (
        <div
            className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-6 bg-slate-950/60 dark:bg-black/75 backdrop-blur-sm animate-in fade-in duration-150"
            onClick={onClose}
            role="dialog"
            aria-modal="true"
            aria-label={`${player.username} 플레이어 상세 스탯`}
        >
            <div
                className="relative w-full max-w-5xl max-h-[92dvh] sm:max-h-[90vh] overflow-y-auto overscroll-contain rounded-t-3xl sm:rounded-3xl bg-slate-50 dark:bg-[#0b101b] border-t sm:border border-slate-200 dark:border-slate-800 shadow-2xl p-3.5 sm:p-6 pb-8 sm:pb-6 space-y-4 sm:space-y-6"
                onClick={(e) => e.stopPropagation()}
            >
                {/* Modal Top Header Bar */}
                <div
                    className="sticky top-0 z-20 -mx-3.5 sm:-mx-6 -mt-3.5 sm:-mt-6 px-4 sm:px-6 pt-2.5 sm:pt-3.5 pb-3 bg-slate-50/95 dark:bg-[#0b101b]/95 backdrop-blur-md border-b border-slate-200/80 dark:border-slate-800/80">
                    <div className="w-10 h-1 rounded-full bg-slate-300 dark:bg-slate-700 mx-auto mb-2.5 sm:hidden"/>
                    <div className="flex items-center justify-between gap-3">
                        <div className="flex items-center gap-2.5 min-w-0">
                            <img
                                src={`https://mc-heads.net/avatar/${encodeURIComponent(player.uuid)}/32`}
                                alt={player.username}
                                className="w-7 h-7 rounded-lg bg-slate-200 dark:bg-slate-800 border border-slate-300 dark:border-slate-700 shrink-0"
                            />
                            <span className="text-sm font-bold text-slate-900 dark:text-white truncate">
                                {player.username}
                            </span>
                            <span className="text-xs text-slate-500 dark:text-slate-400 shrink-0">상세 스탯</span>
                        </div>
                        <button
                            type="button"
                            onClick={onClose}
                            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-slate-200/80 hover:bg-slate-300/80 dark:bg-slate-800 dark:hover:bg-slate-700 text-slate-700 dark:text-slate-200 text-xs font-medium transition shrink-0"
                            aria-label="닫기"
                        >
                            <X className="w-4 h-4"/>
                            <span>닫기</span>
                        </button>
                    </div>
                </div>

                {/* 1. Player Hero Card & Core Stats */}
                <div
                    className="rounded-2xl bg-gradient-to-br from-white via-slate-50/90 to-slate-100/80 dark:from-slate-900/95 dark:via-slate-900/80 dark:to-slate-950 border border-slate-200/90 dark:border-slate-800/90 p-4 sm:p-6 relative overflow-hidden shadow-xs dark:shadow-none">
                    <div className="flex flex-col md:flex-row items-start md:items-center justify-between gap-4 sm:gap-6">
                        <div className="flex items-center gap-3.5 sm:gap-5 min-w-0 w-full md:w-auto">
                            <div
                                className="w-16 h-28 sm:w-24 sm:h-36 rounded-2xl bg-slate-100 dark:bg-slate-950/80 border border-slate-200 dark:border-slate-800 flex items-center justify-center p-1.5 sm:p-2 shrink-0">
                                <img
                                    src={`https://mc-heads.net/body/${encodeURIComponent(player.uuid)}/140`}
                                    alt={player.username}
                                    className="max-h-full w-auto object-contain drop-shadow-[0_6px_12px_rgba(0,0,0,0.25)] dark:drop-shadow-[0_6px_12px_rgba(0,0,0,0.6)]"
                                />
                            </div>

                            <div className="space-y-1.5 sm:space-y-2 min-w-0 flex-1">
                                <div className="flex flex-wrap items-center gap-1.5 sm:gap-2">
                                    <span
                                        className={`inline-flex items-center gap-1.5 px-2 sm:px-2.5 py-0.5 rounded-full text-[11px] sm:text-xs font-semibold border ${
                                            player.isOnline
                                                ? "bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/30"
                                                : "bg-slate-200/80 dark:bg-slate-800 text-slate-600 dark:text-slate-400 border-slate-300 dark:border-slate-700"
                                        }`}
                                    >
                                        <span
                                            className={`w-1.5 h-1.5 rounded-full ${
                                                player.isOnline
                                                    ? "bg-emerald-500 dark:bg-emerald-400 animate-pulse"
                                                    : "bg-slate-400 dark:bg-slate-500"
                                            }`}
                                        />
                                        {player.isOnline ? "현재 온라인" : "오프라인"}
                                    </span>

                                    <span
                                        className="px-2 sm:px-2.5 py-0.5 rounded-full text-[11px] sm:text-xs font-bold border"
                                        style={{
                                            color: activeRankColor,
                                            borderColor: `${activeRankColor}50`,
                                            backgroundColor: `${activeRankColor}15`,
                                        }}
                                    >
                                        🏆 {rankMeta.label} 등급
                                    </span>

                                    {player.primaryGroup === "admin" ? (
                                        <span
                                            className="px-2 sm:px-2.5 py-0.5 rounded-full text-[11px] sm:text-xs font-bold bg-rose-500/15 text-rose-600 dark:text-rose-300 border border-rose-500/30">
                                            👑 ADMIN
                                        </span>
                                    ) : (
                                        <span
                                            className="px-2 sm:px-2.5 py-0.5 rounded-full text-[11px] sm:text-xs font-medium bg-slate-200/80 dark:bg-slate-800 text-slate-700 dark:text-slate-300 border border-slate-300 dark:border-slate-700">
                                            {player.primaryGroup?.toUpperCase() || "USER"}
                                        </span>
                                    )}

                                    {specialtyTitle && (
                                        <span
                                            className="px-2 sm:px-2.5 py-0.5 rounded-full text-[11px] sm:text-xs font-semibold bg-purple-500/15 text-purple-700 dark:text-purple-300 border border-purple-500/30">
                                            ✨ {specialtyTitle}
                                        </span>
                                    )}
                                </div>

                                <div>
                                    <h2 className="text-xl sm:text-3xl font-extrabold text-slate-900 dark:text-white tracking-tight truncate">
                                        {player.username}
                                    </h2>
                                    <p className="text-[11px] sm:text-xs font-mono text-slate-500 dark:text-slate-400 mt-0.5 break-all">
                                        UUID: {player.uuid}
                                    </p>
                                </div>

                                <div
                                    className="flex flex-wrap items-center gap-x-3 sm:gap-x-4 gap-y-1 text-[11px] sm:text-xs text-slate-500 dark:text-slate-400 pt-0.5 sm:pt-1">
                                    <span>
                                        최근 접속:{" "}
                                        <strong className="text-slate-800 dark:text-slate-200">
                                            {formatTimestamp(player.lastLoginTime)}
                                        </strong>
                                    </span>
                                    <span>
                                        최근 로그오프:{" "}
                                        <strong className="text-slate-800 dark:text-slate-200">
                                            {formatTimestamp(player.lastLogoffTime)}
                                        </strong>
                                    </span>
                                    {player.votes > 0 && (
                                        <span>
                                            추천(Votes):{" "}
                                            <strong className="text-cyan-700 dark:text-cyan-300">
                                                {player.votes}회
                                            </strong>
                                        </span>
                                    )}
                                </div>
                            </div>
                        </div>

                        {/* Quick Highlight Stat Boxes */}
                        <div className="grid grid-cols-2 gap-2.5 sm:gap-3 w-full md:w-auto shrink-0">
                            <div
                                className="rounded-xl bg-white/90 dark:bg-slate-950/70 border border-slate-200/90 dark:border-slate-800/80 p-3 sm:p-3.5 min-w-0 sm:min-w-[140px]">
                                <div className="text-[11px] text-slate-500 dark:text-slate-400 flex items-center gap-1">
                                    <Coins className="w-3.5 h-3.5 text-amber-500 dark:text-amber-400 shrink-0"/>
                                    <span className="truncate">보유 화폐 (Balance)</span>
                                </div>
                                <div className="text-base sm:text-lg font-bold text-amber-600 dark:text-amber-300 mt-1 truncate">
                                    {formatMoney(player.balance, meta.currency)}
                                </div>
                            </div>

                            <div
                                className="rounded-xl bg-white/90 dark:bg-slate-950/70 border border-slate-200/90 dark:border-slate-800/80 p-3 sm:p-3.5 min-w-0 sm:min-w-[140px]">
                                <div className="text-[11px] text-slate-500 dark:text-slate-400 flex items-center gap-1">
                                    <Clock className="w-3.5 h-3.5 text-cyan-600 dark:text-cyan-400 shrink-0"/>
                                    <span className="truncate">총 플레이타임</span>
                                </div>
                                <div className="text-base sm:text-lg font-bold text-cyan-700 dark:text-cyan-300 mt-1 truncate">
                                    {formatPlaytime(player.totalPlayTimeMs)}
                                </div>
                            </div>

                            {showPlayerMcmmo && (
                                <div
                                    className="rounded-xl bg-white/90 dark:bg-slate-950/70 border border-slate-200/90 dark:border-slate-800/80 p-3 sm:p-3.5 min-w-0 sm:min-w-[140px]">
                                    <div className="text-[11px] text-slate-500 dark:text-slate-400 flex items-center gap-1">
                                        <Flame className="w-3.5 h-3.5 text-purple-600 dark:text-purple-400 shrink-0"/>
                                        <span className="truncate">스킬 파워 레벨</span>
                                    </div>
                                    <div className="text-base sm:text-lg font-bold text-purple-600 dark:text-purple-300 mt-1 truncate">
                                        Lv. {player.powerLevel.toLocaleString()}
                                    </div>
                                </div>
                            )}

                            {showPlayerClaims && (
                                <div
                                    className="rounded-xl bg-white/90 dark:bg-slate-950/70 border border-slate-200/90 dark:border-slate-800/80 p-3 sm:p-3.5 min-w-0 sm:min-w-[140px]">
                                    <div className="text-[11px] text-slate-500 dark:text-slate-400 flex items-center gap-1">
                                        <Shield className="w-3.5 h-3.5 text-emerald-600 dark:text-emerald-400 shrink-0"/>
                                        <span className="truncate">보유 클레임 블록</span>
                                    </div>
                                    <div className="text-base sm:text-lg font-bold text-emerald-600 dark:text-emerald-300 mt-1 truncate">
                                        {player.claims.totalAvailableBlocks.toLocaleString()}{" "}
                                        <span className="text-xs font-normal text-slate-500 dark:text-slate-400">블록</span>
                                    </div>
                                </div>
                            )}
                        </div>
                    </div>

                    {/* Dynamic Next Rank Progress Bar from ranks.yml */}
                    {nextRankMeta && (
                        <div className="mt-5 pt-4 border-t border-slate-200/90 dark:border-slate-800/80">
                            <div className="flex items-center justify-between text-xs mb-2 flex-wrap gap-1">
                                <span className="font-semibold text-slate-700 dark:text-slate-300 flex items-center gap-1.5">
                                    <Award className="w-4 h-4 text-amber-500 dark:text-amber-400"/>
                                    다음 승급 목표:{" "}
                                    <span style={{color: activeNextRankColor}}>{nextRankMeta.label} 랭크</span>
                                </span>
                                <span className="text-slate-500 dark:text-slate-400">
                                    비용 {formatMoney(nextRankMeta.cost, meta.currency)} · 플레이타임{" "}
                                    {formatPlaytime(nextRankMeta.requiredPlaytimeSeconds * 1000)}
                                </span>
                            </div>
                            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
                                {(() => {
                                    const pct =
                                        nextRankMeta.cost > 0
                                            ? Math.min(100, Math.round((player.balance / nextRankMeta.cost) * 100))
                                            : 100;
                                    return (
                                        <div
                                            className="bg-white/80 dark:bg-slate-950/60 rounded-lg p-2.5 border border-slate-200/80 dark:border-slate-800/70">
                                            <div className="flex justify-between text-[11px] text-slate-500 dark:text-slate-400 mb-1">
                                                <span>Balance ({formatMoney(player.balance, meta.currency)} / {formatMoney(nextRankMeta.cost, meta.currency)})</span>
                                                <span className="text-amber-600 dark:text-amber-300 font-semibold">{pct}%</span>
                                            </div>
                                            <div className="w-full h-1.5 bg-slate-200 dark:bg-slate-800 rounded-full overflow-hidden">
                                                <div
                                                    className="h-full bg-amber-500 dark:bg-amber-400 rounded-full transition-all"
                                                    style={{width: `${pct}%`}}
                                                />
                                            </div>
                                        </div>
                                    );
                                })()}

                                {(() => {
                                    const reqMs = nextRankMeta.requiredPlaytimeSeconds * 1000;
                                    const pct =
                                        reqMs > 0
                                            ? Math.min(100, Math.round((player.totalPlayTimeMs / reqMs) * 100))
                                            : 100;
                                    return (
                                        <div
                                            className="bg-white/80 dark:bg-slate-950/60 rounded-lg p-2.5 border border-slate-200/80 dark:border-slate-800/70">
                                            <div className="flex justify-between text-[11px] text-slate-500 dark:text-slate-400 mb-1">
                                                <span>Playtime ({formatPlaytime(player.totalPlayTimeMs)} / {formatPlaytime(reqMs)})</span>
                                                <span className="text-cyan-600 dark:text-cyan-300 font-semibold">{pct}%</span>
                                            </div>
                                            <div className="w-full h-1.5 bg-slate-200 dark:bg-slate-800 rounded-full overflow-hidden">
                                                <div
                                                    className="h-full bg-cyan-500 dark:bg-cyan-400 rounded-full transition-all"
                                                    style={{width: `${pct}%`}}
                                                />
                                            </div>
                                        </div>
                                    );
                                })()}
                            </div>
                        </div>
                    )}
                </div>

                {/* 2. Vanilla Minecraft Survival, Combat, Movement & Top Blocks/Items */}
                {player.vanillaStats && (
                    <div
                        className="rounded-2xl bg-white dark:bg-slate-900/70 border border-slate-200/90 dark:border-slate-800/90 p-5 sm:p-6 space-y-5 shadow-xs dark:shadow-none">
                        <div className="flex items-center justify-between flex-wrap gap-2">
                            <div>
                                <h3 className="text-base font-bold text-slate-900 dark:text-white flex items-center gap-2">
                                    <Footprints className="w-4 h-4 text-emerald-600 dark:text-emerald-400"/>
                                    인게임 생존 · 전투 · 이동 & 장인 기록
                                </h3>
                                <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
                                    마인크래프트 바닐라 통계
                                </p>
                            </div>
                            <div className="text-xs text-slate-500 dark:text-slate-400">
                                총 채굴:{" "}
                                <strong className="text-slate-900 dark:text-white">
                                    {player.vanillaStats.totalMined.toLocaleString()}블록
                                </strong>{" "}
                                · 도구/블록 사용:{" "}
                                <strong className="text-slate-900 dark:text-white">
                                    {player.vanillaStats.totalUsed.toLocaleString()}회
                                </strong>{" "}
                                · 아이템 획득:{" "}
                                <strong className="text-slate-900 dark:text-white">
                                    {player.vanillaStats.totalPickedUp.toLocaleString()}개
                                </strong>
                            </div>
                        </div>

                        {/* Survival & Combat Highlight Row */}
                        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-2.5">
                            <div className="rounded-xl bg-slate-50 dark:bg-slate-950/60 border border-slate-200/80 dark:border-slate-800 p-3">
                                <div className="text-[11px] text-slate-500 dark:text-slate-400 flex items-center gap-1">
                                    <Skull className="w-3.5 h-3.5 text-rose-500"/>
                                    사망 횟수
                                </div>
                                <div className="text-base font-bold text-slate-900 dark:text-white mt-1">
                                    {player.vanillaStats.deaths.toLocaleString()}회
                                </div>
                                <div className="text-[10px] text-slate-500">
                                    파괴한 도구 {player.vanillaStats.totalBroken}개
                                </div>
                            </div>

                            <div className="rounded-xl bg-slate-50 dark:bg-slate-950/60 border border-slate-200/80 dark:border-slate-800 p-3">
                                <div className="text-[11px] text-slate-500 dark:text-slate-400 flex items-center gap-1">
                                    <Clock className="w-3.5 h-3.5 text-emerald-500"/>
                                    무사 생존 시간
                                </div>
                                <div className="text-base font-bold text-emerald-600 dark:text-emerald-300 mt-1">
                                    {formatPlaytime(player.vanillaStats.timeSinceDeathMs)}
                                </div>
                                <div className="text-[10px] text-slate-500">마지막 사망 이후</div>
                            </div>

                            <div className="rounded-xl bg-slate-50 dark:bg-slate-950/60 border border-slate-200/80 dark:border-slate-800 p-3">
                                <div className="text-[11px] text-slate-500 dark:text-slate-400 flex items-center gap-1">
                                    <Sword className="w-3.5 h-3.5 text-amber-500"/>
                                    몬스터 / 유저 처치
                                </div>
                                <div className="text-base font-bold text-slate-900 dark:text-white mt-1">
                                    {player.vanillaStats.mobKills.toLocaleString()}{" "}
                                    <span className="text-xs font-normal text-slate-400">
                                        / {player.vanillaStats.playerKills}
                                    </span>
                                </div>
                                <div className="text-[10px] text-slate-500">몹 처치 / PvP 처치</div>
                            </div>

                            <div className="rounded-xl bg-slate-50 dark:bg-slate-950/60 border border-slate-200/80 dark:border-slate-800 p-3">
                                <div className="text-[11px] text-slate-500 dark:text-slate-400 flex items-center gap-1">
                                    <HeartCrack className="w-3.5 h-3.5 text-purple-500"/>
                                    가한 / 받은 피해
                                </div>
                                <div className="text-base font-bold text-purple-600 dark:text-purple-300 mt-1">
                                    {player.vanillaStats.damageDealt.toLocaleString()}{" "}
                                    <span className="text-xs font-normal text-slate-400">
                                        / {player.vanillaStats.damageTaken.toLocaleString()}
                                    </span>
                                </div>
                                <div className="text-[10px] text-slate-500">HP 데미지 환산</div>
                            </div>

                            <div className="rounded-xl bg-slate-50 dark:bg-slate-950/60 border border-slate-200/80 dark:border-slate-800 p-3">
                                <div className="text-[11px] text-slate-500 dark:text-slate-400 flex items-center gap-1">
                                    <Footprints className="w-3.5 h-3.5 text-cyan-500"/>
                                    총 이동 거리
                                </div>
                                <div className="text-base font-bold text-cyan-700 dark:text-cyan-300 mt-1">
                                    {formatDistanceCm(player.vanillaStats.totalDistanceCm)}
                                </div>
                                <div className="text-[10px] text-slate-500">
                                    점프 {player.vanillaStats.jumps.toLocaleString()}회
                                </div>
                            </div>

                            <div className="rounded-xl bg-slate-50 dark:bg-slate-950/60 border border-slate-200/80 dark:border-slate-800 p-3">
                                <div className="text-[11px] text-slate-500 dark:text-slate-400 flex items-center gap-1">
                                    <Wrench className="w-3.5 h-3.5 text-blue-500"/>
                                    아이템 제작 횟수
                                </div>
                                <div className="text-base font-bold text-slate-900 dark:text-white mt-1">
                                    {player.vanillaStats.totalCrafted.toLocaleString()}개
                                </div>
                                <div className="text-[10px] text-slate-500">
                                    재접속 {player.vanillaStats.leaveGameCount}회
                                </div>
                            </div>
                        </div>

                        {/* Movement Breakdown Bar */}
                        {player.vanillaStats.totalDistanceCm > 0 && (
                            <div
                                className="rounded-xl bg-slate-50 dark:bg-slate-950/50 border border-slate-200/80 dark:border-slate-800/80 p-3.5 space-y-2">
                                <div className="flex items-center justify-between text-xs">
                                    <span className="font-semibold text-slate-700 dark:text-slate-300">
                                        이동 수단별 거리 비중
                                    </span>
                                    <div className="flex flex-wrap items-center gap-3 text-[11px] text-slate-500 dark:text-slate-400">
                                        <span>
                                            🚶 도보:{" "}
                                            <strong className="text-emerald-600 dark:text-emerald-300">
                                                {formatDistanceCm(player.vanillaStats.walkCm)}
                                            </strong>
                                        </span>
                                        <span>
                                            🏃 질주:{" "}
                                            <strong className="text-cyan-600 dark:text-cyan-300">
                                                {formatDistanceCm(player.vanillaStats.sprintCm)}
                                            </strong>
                                        </span>
                                        <span>
                                            🏊 수영/수중:{" "}
                                            <strong className="text-blue-600 dark:text-blue-300">
                                                {formatDistanceCm(player.vanillaStats.swimCm)}
                                            </strong>
                                        </span>
                                        <span>
                                            🪽 비행/겉날개:{" "}
                                            <strong className="text-purple-600 dark:text-purple-300">
                                                {formatDistanceCm(player.vanillaStats.flyCm)}
                                            </strong>
                                        </span>
                                        {player.vanillaStats.vehicleCm > 0 && (
                                            <span>
                                                🚣 탈것:{" "}
                                                <strong className="text-amber-600 dark:text-amber-300">
                                                    {formatDistanceCm(player.vanillaStats.vehicleCm)}
                                                </strong>
                                            </span>
                                        )}
                                    </div>
                                </div>
                                {(() => {
                                    const tot = Math.max(1, player.vanillaStats.totalDistanceCm);
                                    const wPct = (player.vanillaStats.walkCm / tot) * 100;
                                    const spPct = (player.vanillaStats.sprintCm / tot) * 100;
                                    const swPct = (player.vanillaStats.swimCm / tot) * 100;
                                    const flPct = (player.vanillaStats.flyCm / tot) * 100;
                                    const vPct = (player.vanillaStats.vehicleCm / tot) * 100;
                                    return (
                                        <div className="w-full h-2 rounded-full bg-slate-200 dark:bg-slate-800 overflow-hidden flex">
                                            <div className="h-full bg-emerald-500" style={{width: `${wPct}%`}}
                                                 title={`도보: ${formatDistanceCm(player.vanillaStats.walkCm)}`}/>
                                            <div className="h-full bg-cyan-500" style={{width: `${spPct}%`}}
                                                 title={`질주: ${formatDistanceCm(player.vanillaStats.sprintCm)}`}/>
                                            <div className="h-full bg-blue-500" style={{width: `${swPct}%`}}
                                                 title={`수영/수중: ${formatDistanceCm(player.vanillaStats.swimCm)}`}/>
                                            <div className="h-full bg-purple-500" style={{width: `${flPct}%`}}
                                                 title={`비행: ${formatDistanceCm(player.vanillaStats.flyCm)}`}/>
                                            <div className="h-full bg-amber-500" style={{width: `${vPct}%`}}
                                                 title={`탈것: ${formatDistanceCm(player.vanillaStats.vehicleCm)}`}/>
                                        </div>
                                    );
                                })()}
                            </div>
                        )}

                        {/* Top Mined & Top Used */}
                        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                            <div
                                className="rounded-xl bg-slate-50 dark:bg-slate-950/50 border border-slate-200/80 dark:border-slate-800/80 p-3.5 space-y-2.5">
                                <div className="text-xs font-semibold text-slate-700 dark:text-slate-300 flex items-center justify-between">
                                    <span>⛏ 최다 채굴 블록 TOP 5</span>
                                    <span className="text-[11px] font-normal text-slate-500">
                                        총 {player.vanillaStats.totalMined.toLocaleString()}개
                                    </span>
                                </div>
                                {player.vanillaStats.topMined.length > 0 ? (
                                    <div className="space-y-1.5">
                                        {player.vanillaStats.topMined.map((item, idx) => (
                                            <div
                                                key={item.id}
                                                className="flex items-center justify-between text-xs px-2.5 py-1.5 rounded-lg bg-white dark:bg-slate-900/80 border border-slate-200/70 dark:border-slate-800/70"
                                            >
                                                <span className="text-slate-700 dark:text-slate-200 font-medium truncate">
                                                    <strong className="text-sky-600 dark:text-sky-400 mr-1.5">
                                                        #{idx + 1}
                                                    </strong>
                                                    {formatMcIdentifier(item.id)}
                                                </span>
                                                <span className="font-mono font-semibold text-sky-700 dark:text-sky-300 shrink-0">
                                                    {item.count.toLocaleString()}개
                                                </span>
                                            </div>
                                        ))}
                                    </div>
                                ) : (
                                    <div className="text-xs text-slate-500 py-3 text-center">
                                        채굴한 블록 기록이 없습니다.
                                    </div>
                                )}
                            </div>

                            <div
                                className="rounded-xl bg-slate-50 dark:bg-slate-950/50 border border-slate-200/80 dark:border-slate-800/80 p-3.5 space-y-2.5">
                                <div className="text-xs font-semibold text-slate-700 dark:text-slate-300 flex items-center justify-between">
                                    <span>🔨 최다 사용 도구·블록 TOP 5</span>
                                    <span className="text-[11px] font-normal text-slate-500">
                                        총 {player.vanillaStats.totalUsed.toLocaleString()}회
                                    </span>
                                </div>
                                {player.vanillaStats.topUsed.length > 0 ? (
                                    <div className="space-y-1.5">
                                        {player.vanillaStats.topUsed.map((item, idx) => (
                                            <div
                                                key={item.id}
                                                className="flex items-center justify-between text-xs px-2.5 py-1.5 rounded-lg bg-white dark:bg-slate-900/80 border border-slate-200/70 dark:border-slate-800/70"
                                            >
                                                <span className="text-slate-700 dark:text-slate-200 font-medium truncate">
                                                    <strong className="text-amber-600 dark:text-amber-400 mr-1.5">
                                                        #{idx + 1}
                                                    </strong>
                                                    {formatMcIdentifier(item.id)}
                                                </span>
                                                <span className="font-mono font-semibold text-amber-700 dark:text-amber-300 shrink-0">
                                                    {item.count.toLocaleString()}회
                                                </span>
                                            </div>
                                        ))}
                                    </div>
                                ) : (
                                    <div className="text-xs text-slate-500 py-3 text-center">
                                        사용한 도구·블록 기록이 없습니다.
                                    </div>
                                )}
                            </div>
                        </div>
                    </div>
                )}

                {/* 3. Optional Skill Panel (only shown when mcMMO integration is active or player has skill levels) */}
                {showPlayerMcmmo && (
                    <div
                        className="rounded-2xl bg-white dark:bg-slate-900/70 border border-slate-200/90 dark:border-slate-800/90 p-4 sm:p-6 space-y-4 shadow-xs dark:shadow-none">
                        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                            <div>
                                <h3 className="text-base font-bold text-slate-900 dark:text-white flex items-center gap-2">
                                    <Sword className="w-4 h-4 text-purple-600 dark:text-purple-400"/>
                                    스킬 숙련도 통계
                                </h3>
                                <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
                                    스킬별 레벨 및 다음 레벨업 필요 경험치(XP) 진행률
                                </p>
                            </div>

                            <div
                                className="grid grid-cols-4 sm:inline-flex p-1 rounded-xl bg-slate-100 dark:bg-slate-950 border border-slate-200 dark:border-slate-800 w-full sm:w-auto">
                                {[
                                    {id: "all", label: "전체 (17)"},
                                    {id: "gathering", label: "🌿 채집 (5)"},
                                    {id: "combat", label: "⚔ 전투 (9)"},
                                    {id: "misc", label: "🔧 기타 (3)"},
                                ].map((tab) => (
                                    <button
                                        key={tab.id}
                                        onClick={() => setSkillCategory(tab.id as "all" | SkillCategory)}
                                        className={`px-1.5 sm:px-2.5 py-1.5 sm:py-1 rounded-lg text-[11px] sm:text-xs font-medium text-center transition ${
                                            skillCategory === tab.id
                                                ? "bg-purple-500/15 dark:bg-purple-500/25 text-purple-800 dark:text-purple-200 border border-purple-500/40"
                                                : "text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200"
                                        }`}
                                    >
                                        {tab.label}
                                    </button>
                                ))}
                            </div>
                        </div>

                        {player.specialty && (
                            <div
                                className="rounded-xl bg-slate-50 dark:bg-slate-950/60 border border-slate-200/80 dark:border-slate-800/80 p-3 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                                <div className="flex items-center gap-3 text-xs flex-wrap">
                                    <span className="font-semibold text-slate-700 dark:text-slate-300">
                                        스킬 성향 비중:
                                    </span>
                                    <span className="text-emerald-600 dark:text-emerald-400 font-medium">
                                        🌿 채집 Lv.{player.specialty.gatheringLevel}
                                    </span>
                                    <span className="text-rose-600 dark:text-rose-400 font-medium">
                                        ⚔ 전투 Lv.{player.specialty.combatLevel}
                                    </span>
                                    <span className="text-indigo-600 dark:text-indigo-400 font-medium">
                                        🔧 기타 Lv.{player.specialty.miscLevel}
                                    </span>
                                </div>
                                <div className="w-full sm:w-64 h-2 rounded-full bg-slate-200 dark:bg-slate-800 overflow-hidden flex">
                                    {(() => {
                                        const tot = Math.max(
                                            1,
                                            player.specialty.gatheringLevel +
                                                player.specialty.combatLevel +
                                                player.specialty.miscLevel,
                                        );
                                        return (
                                            <>
                                                <div className="h-full bg-emerald-500"
                                                     style={{width: `${(player.specialty.gatheringLevel / tot) * 100}%`}}/>
                                                <div className="h-full bg-rose-500"
                                                     style={{width: `${(player.specialty.combatLevel / tot) * 100}%`}}/>
                                                <div className="h-full bg-indigo-500"
                                                     style={{width: `${(player.specialty.miscLevel / tot) * 100}%`}}/>
                                            </>
                                        );
                                    })()}
                                </div>
                            </div>
                        )}

                        <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-3">
                            {visibleSkills.map((metaItem) => {
                                const stat = player.skills[metaItem.key] || {level: 0, xp: 0, xpToNext: 1020};
                                const xpToNext = stat.xpToNext || 1020 + stat.level * 20;
                                const xpPct = Math.min(100, Math.round((stat.xp / Math.max(1, xpToNext)) * 100));
                                const skillColor = isDark ? metaItem.color : metaItem.lightColor;

                                return (
                                    <div
                                        key={metaItem.key}
                                        className="rounded-xl bg-slate-50/80 dark:bg-slate-950/65 border border-slate-200/80 dark:border-slate-800/80 p-3.5 hover:border-slate-300 dark:hover:border-slate-700 transition"
                                    >
                                        <div className="flex items-center justify-between">
                                            <div className="flex items-center gap-2">
                                                <span className="w-2.5 h-2.5 rounded-full"
                                                      style={{backgroundColor: skillColor}}/>
                                                <span className="font-semibold text-sm text-slate-900 dark:text-white">
                                                    {metaItem.labelKo}
                                                </span>
                                                <span className="text-xs text-slate-500">{metaItem.labelEn}</span>
                                            </div>
                                            <span className="text-sm font-bold" style={{color: skillColor}}>
                                                Lv. {stat.level.toLocaleString()}
                                            </span>
                                        </div>

                                        <div className="mt-2.5 w-full h-1.5 rounded-full bg-slate-200 dark:bg-slate-800/90 overflow-hidden">
                                            <div
                                                className="h-full rounded-full transition-all duration-500"
                                                style={{width: `${xpPct}%`, backgroundColor: skillColor}}
                                            />
                                        </div>

                                        <div
                                            className="mt-2 flex items-center justify-between text-[11px] text-slate-500 dark:text-slate-400">
                                            <span>
                                                다음 레벨 진행률:{" "}
                                                <strong className="text-slate-700 dark:text-slate-200">{xpPct}%</strong>
                                            </span>
                                            <span className="font-mono text-slate-700 dark:text-slate-300">
                                                {stat.xp.toLocaleString()} / {xpToNext.toLocaleString()} XP
                                            </span>
                                        </div>
                                    </div>
                                );
                            })}
                        </div>
                    </div>
                )}

                {/* 4. Advancements & Biome Exploration Panel */}
                {player.advancements && (
                    <div
                        className="rounded-2xl bg-white dark:bg-slate-900/70 border border-slate-200/90 dark:border-slate-800/90 p-5 sm:p-6 space-y-4 shadow-xs dark:shadow-none">
                        <div className="flex items-center justify-between flex-wrap gap-2">
                            <div>
                                <h3 className="text-base font-bold text-slate-900 dark:text-white flex items-center gap-2">
                                    <Medal className="w-4 h-4 text-amber-500 dark:text-amber-400"/>
                                    발전 과제 & 바이옴 탐험 진행도
                                </h3>
                                <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
                                    레시피 해금을 제외한 실제 인게임 발전 과제 및 오버월드 바이옴 탐험 기록
                                </p>
                            </div>
                            <span
                                className="px-3 py-1 rounded-xl text-xs font-bold bg-amber-500/15 text-amber-700 dark:text-amber-300 border border-amber-500/30">
                                총 {player.advancements.totalCompleted}개 발전 과제 달성
                            </span>
                        </div>

                        <div className="grid grid-cols-2 sm:grid-cols-5 gap-2.5">
                            {[
                                {key: "story", label: "⛏ 스토리 (Story)", val: player.advancements.categories.story},
                                {key: "adventure", label: "🗺 모험 (Adventure)", val: player.advancements.categories.adventure},
                                {key: "husbandry", label: "🌾 농사 (Husbandry)", val: player.advancements.categories.husbandry},
                                {key: "nether", label: "🔥 네더 (Nether)", val: player.advancements.categories.nether},
                                {key: "end", label: "🐉 엔드 (End)", val: player.advancements.categories.end},
                            ].map((cat, idx) => (
                                <div
                                    key={cat.key}
                                    className={`rounded-xl bg-slate-50 dark:bg-slate-950/60 border border-slate-200/80 dark:border-slate-800 p-3 flex items-center justify-between ${
                                        idx === 4 ? "col-span-2 sm:col-span-1" : ""
                                    }`}
                                >
                                    <span className="text-xs text-slate-600 dark:text-slate-400 font-medium">
                                        {cat.label}
                                    </span>
                                    <span className="text-sm font-bold text-slate-900 dark:text-white">
                                        {cat.val}개
                                    </span>
                                </div>
                            ))}
                        </div>

                        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                            <div
                                className="rounded-xl bg-slate-50 dark:bg-slate-950/50 border border-slate-200/80 dark:border-slate-800/80 p-3.5 space-y-2.5">
                                <div className="flex items-center justify-between text-xs">
                                    <span className="font-semibold text-slate-700 dark:text-slate-300 flex items-center gap-1.5">
                                        <Compass className="w-3.5 h-3.5 text-emerald-500"/>
                                        모험의 시간 바이옴 탐험
                                    </span>
                                    <span className="font-mono font-bold text-emerald-600 dark:text-emerald-400">
                                        {player.advancements.visitedBiomes.length} /{" "}
                                        {player.advancements.totalBiomesTarget} 곳
                                    </span>
                                </div>
                                <div className="w-full h-1.5 rounded-full bg-slate-200 dark:bg-slate-800 overflow-hidden">
                                    <div
                                        className="h-full bg-emerald-500 rounded-full transition-all"
                                        style={{
                                            width: `${Math.min(
                                                100,
                                                Math.round(
                                                    (player.advancements.visitedBiomes.length /
                                                        Math.max(1, player.advancements.totalBiomesTarget)) *
                                                        100,
                                                ),
                                            )}%`,
                                        }}
                                    />
                                </div>
                                {player.advancements.visitedBiomes.length > 0 ? (
                                    <div className="flex flex-wrap gap-1.5 pt-1 max-h-36 overflow-y-auto">
                                        {player.advancements.visitedBiomes.map((b) => (
                                            <span
                                                key={b.id}
                                                title={`방문 시각: ${b.visitedAt}`}
                                                className="px-2 py-0.5 rounded-md text-[11px] font-medium bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 text-slate-700 dark:text-slate-300"
                                            >
                                                {formatBiomeName(b.id)}
                                            </span>
                                        ))}
                                    </div>
                                ) : (
                                    <div className="text-xs text-slate-500 py-2 text-center">
                                        방문한 바이옴 기록이 없습니다.
                                    </div>
                                )}
                            </div>

                            <div
                                className="rounded-xl bg-slate-50 dark:bg-slate-950/50 border border-slate-200/80 dark:border-slate-800/80 p-3.5 space-y-2.5">
                                <div className="text-xs font-semibold text-slate-700 dark:text-slate-300">
                                    🏅 최근 달성한 발전 과제
                                </div>
                                {player.advancements.recent.length > 0 ? (
                                    <div className="space-y-1.5 max-h-40 overflow-y-auto pr-1">
                                        {player.advancements.recent.map((adv) => (
                                            <div
                                                key={adv.key}
                                                className="flex items-center justify-between gap-2 text-xs px-2.5 py-1.5 rounded-lg bg-white dark:bg-slate-900/80 border border-slate-200/70 dark:border-slate-800/70"
                                            >
                                                <div className="truncate">
                                                    <span className="font-semibold text-amber-600 dark:text-amber-300">
                                                        {formatAdvancementName(adv.key)}
                                                    </span>
                                                    <span className="text-[11px] text-slate-400 ml-1.5 font-mono">
                                                        ({adv.key})
                                                    </span>
                                                </div>
                                                <span className="text-[10px] font-mono text-slate-500 shrink-0">
                                                    {adv.completedAt.slice(0, 16)}
                                                </span>
                                            </div>
                                        ))}
                                    </div>
                                ) : (
                                    <div className="text-xs text-slate-500 py-4 text-center">
                                        아직 달성한 발전 과제가 없습니다.
                                    </div>
                                )}
                            </div>
                        </div>
                    </div>
                )}

                {/* 5. Optional Claims & Hourly Playtime Distribution */}
                <div className={`grid grid-cols-1 ${showPlayerClaims ? "md:grid-cols-2" : ""} gap-6`}>
                    {showPlayerClaims && (
                        <div
                            className="rounded-2xl bg-white dark:bg-slate-900/70 border border-slate-200/90 dark:border-slate-800/90 p-5 sm:p-6 space-y-4 shadow-xs dark:shadow-none">
                            <div>
                                <h3 className="text-base font-bold text-slate-900 dark:text-white flex items-center gap-2">
                                    <Shield className="w-4 h-4 text-emerald-600 dark:text-emerald-400"/>
                                    영지 & 클레임 블록
                                </h3>
                                <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
                                    보유 클레임 블록 한도 및 보호 구역 좌표 · 신뢰 멤버 정보
                                </p>
                            </div>

                            <div className="grid grid-cols-3 gap-2.5">
                                <div className="rounded-xl bg-slate-50 dark:bg-slate-950/60 border border-slate-200/80 dark:border-slate-800 p-3">
                                    <div className="text-[11px] text-slate-500 dark:text-slate-400">누적+보너스</div>
                                    <div className="text-base font-bold text-slate-900 dark:text-white mt-0.5">
                                        {player.claims.totalAvailableBlocks.toLocaleString()}
                                    </div>
                                    <div className="text-[10px] text-slate-500">
                                        기본 {player.claims.accruedBlocks} / 보너스 {player.claims.bonusBlocks}
                                    </div>
                                </div>

                                <div className="rounded-xl bg-slate-50 dark:bg-slate-950/60 border border-slate-200/80 dark:border-slate-800 p-3">
                                    <div className="text-[11px] text-slate-500 dark:text-slate-400">사용 중 면적</div>
                                    <div className="text-base font-bold text-emerald-600 dark:text-emerald-300 mt-0.5">
                                        {player.claims.usedBlocks.toLocaleString()}
                                    </div>
                                    <div className="text-[10px] text-slate-500">
                                        영지 {player.claims.claimCount}개
                                    </div>
                                </div>

                                <div className="rounded-xl bg-slate-50 dark:bg-slate-950/60 border border-slate-200/80 dark:border-slate-800 p-3">
                                    <div className="text-[11px] text-slate-500 dark:text-slate-400">남은 블록</div>
                                    <div className="text-base font-bold text-cyan-600 dark:text-cyan-300 mt-0.5">
                                        {player.claims.remainingBlocks.toLocaleString()}
                                    </div>
                                    <div className="text-[10px] text-slate-500">추가 확장 가능</div>
                                </div>
                            </div>

                            <div className="space-y-2.5">
                                <div className="text-xs font-semibold text-slate-700 dark:text-slate-300">
                                    생성된 영지 목록 ({player.claims.items.length})
                                </div>
                                {player.claims.items.length > 0 ? (
                                    <div className="space-y-2 max-h-60 overflow-y-auto pr-1">
                                        {player.claims.items.map((claim) => {
                                            const centerX = Math.round((claim.minX + claim.maxX) / 2);
                                            const centerZ = Math.round((claim.minZ + claim.maxZ) / 2);
                                            const trustedList = Array.from(
                                                new Set([
                                                    ...claim.builders,
                                                    ...claim.containers,
                                                    ...claim.accessors,
                                                    ...claim.managers,
                                                ]),
                                            );
                                            return (
                                                <div
                                                    key={claim.id}
                                                    className="rounded-xl bg-slate-50 dark:bg-slate-950/70 border border-slate-200/90 dark:border-slate-800/90 p-3 space-y-2"
                                                >
                                                    <div className="flex items-center justify-between gap-3">
                                                        <div className="space-y-1">
                                                            <div className="flex items-center gap-2 flex-wrap">
                                                                <span
                                                                    className="px-2 py-0.5 rounded-md text-[11px] font-bold bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border border-emerald-500/30">
                                                                    Claim #{claim.id}
                                                                </span>
                                                                <span
                                                                    className="text-xs font-medium text-slate-700 dark:text-slate-300 flex items-center gap-1">
                                                                    <MapPin className="w-3 h-3 text-slate-500 dark:text-slate-400"/>
                                                                    월드: {claim.world}
                                                                </span>
                                                                <button
                                                                    type="button"
                                                                    onClick={() =>
                                                                        handleCopyClaimCoords(claim.id, centerX, claim.minY, centerZ)
                                                                    }
                                                                    className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-medium bg-slate-200/80 dark:bg-slate-800 text-slate-600 dark:text-slate-300 hover:bg-cyan-500/20 hover:text-cyan-600 dark:hover:text-cyan-300 transition"
                                                                    title="중심 좌표 복사"
                                                                >
                                                                    {copiedClaimId === claim.id ? (
                                                                        <>
                                                                            <Check className="w-2.5 h-2.5 text-emerald-500"/>
                                                                            복사됨
                                                                        </>
                                                                    ) : (
                                                                        <>
                                                                            <Copy className="w-2.5 h-2.5"/>
                                                                            좌표 복사
                                                                        </>
                                                                    )}
                                                                </button>
                                                            </div>
                                                            <div className="text-xs font-mono text-slate-500 dark:text-slate-400">
                                                                ({claim.minX}, {claim.minY}, {claim.minZ}) ~ (
                                                                {claim.maxX}, {claim.maxY}, {claim.maxZ})
                                                            </div>
                                                        </div>

                                                        <div className="text-right shrink-0">
                                                            <div className="text-sm font-bold text-emerald-600 dark:text-emerald-300">
                                                                {claim.area.toLocaleString()} 블록²
                                                            </div>
                                                            <div className="text-[11px] text-slate-500">
                                                                {claim.width} × {claim.length}
                                                            </div>
                                                        </div>
                                                    </div>

                                                    {trustedList.length > 0 && (
                                                        <div
                                                            className="pt-1.5 border-t border-slate-200/70 dark:border-slate-800/70 flex items-center gap-1.5 flex-wrap text-[11px]">
                                                            <span className="text-slate-500">신뢰(Trust) 멤버:</span>
                                                            {trustedList.map((member) => (
                                                                <span
                                                                    key={member}
                                                                    className="px-1.5 py-0.5 rounded bg-cyan-500/10 text-cyan-700 dark:text-cyan-300 border border-cyan-500/20"
                                                                >
                                                                    {member}
                                                                </span>
                                                            ))}
                                                        </div>
                                                    )}
                                                </div>
                                            );
                                        })}
                                    </div>
                                ) : (
                                    <div
                                        className="rounded-xl bg-slate-50 dark:bg-slate-950/40 border border-slate-200/70 dark:border-slate-800/60 p-6 text-center text-xs text-slate-500">
                                        아직 생성한 영지가 없습니다.
                                    </div>
                                )}
                            </div>
                        </div>
                    )}

                    {/* Hourly Playtime Heatmap & Daily Log */}
                    <div
                        className="rounded-2xl bg-white dark:bg-slate-900/70 border border-slate-200/90 dark:border-slate-800/90 p-5 sm:p-6 space-y-4 shadow-xs dark:shadow-none">
                        <div>
                            <h3 className="text-base font-bold text-slate-900 dark:text-white flex items-center gap-2">
                                <Clock className="w-4 h-4 text-cyan-600 dark:text-cyan-400"/>
                                시간대별 플레이 활동 분포
                            </h3>
                            <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
                                시간대별 접속 밀도 분석
                            </p>
                        </div>

                        {(() => {
                            const maxHourMs = Math.max(1, ...player.hourlyDistribution);
                            return (
                                <div className="pt-2">
                                    <div
                                        className="grid grid-cols-[repeat(24,minmax(0,1fr))] gap-1 items-end h-28 bg-slate-50 dark:bg-slate-950/60 border border-slate-200/80 dark:border-slate-800/80 rounded-xl p-3">
                                        {player.hourlyDistribution.map((ms, hour) => {
                                            const heightPct = ms > 0 ? Math.max(12, Math.round((ms / maxHourMs) * 100)) : 4;
                                            return (
                                                <div
                                                    key={hour}
                                                    className="flex flex-col items-center justify-end h-full group relative"
                                                    title={`${hour}시: ${formatPlaytime(ms)}`}
                                                >
                                                    <div
                                                        className={`w-full rounded-t transition-all ${
                                                            ms > 0
                                                                ? "bg-gradient-to-t from-cyan-600 to-cyan-400 group-hover:from-cyan-500 group-hover:to-cyan-300"
                                                                : "bg-slate-200 dark:bg-slate-800/70"
                                                        }`}
                                                        style={{height: `${heightPct}%`}}
                                                    />
                                                </div>
                                            );
                                        })}
                                    </div>
                                    <div className="flex justify-between text-[10px] text-slate-500 mt-1 px-1">
                                        <span>00시</span>
                                        <span>06시</span>
                                        <span>12시</span>
                                        <span>18시</span>
                                        <span>23시</span>
                                    </div>
                                </div>
                            );
                        })()}

                        <div className="space-y-2 pt-1">
                            <div className="text-xs font-semibold text-slate-700 dark:text-slate-300">
                                최근 일자별 플레이타임 기록
                            </div>
                            {player.playtimeHistory.length > 0 ? (
                                <div className="space-y-1.5 max-h-40 overflow-y-auto pr-1">
                                    {player.playtimeHistory.map((day) => (
                                        <div
                                            key={day.dateInt}
                                            className="flex items-center justify-between px-3 py-2 rounded-lg bg-slate-50 dark:bg-slate-950/60 border border-slate-200/80 dark:border-slate-800/70 text-xs"
                                        >
                                            <span className="font-mono text-slate-700 dark:text-slate-300">
                                                {day.dateFormatted}
                                            </span>
                                            <span className="font-semibold text-cyan-700 dark:text-cyan-300">
                                                {formatPlaytime(day.totalMs)}
                                            </span>
                                        </div>
                                    ))}
                                </div>
                            ) : (
                                <div className="text-xs text-slate-500 py-4 text-center">
                                    일자별 플레이타임 세부 기록이 없습니다.
                                </div>
                            )}
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
}
