"use client";

import React, {useCallback, useEffect, useMemo, useState} from "react";
import {Activity, Database, Monitor, Moon, RefreshCw, Search, Sparkles, Sun, Trophy, X} from "lucide-react";
import {LeaderboardMetric, PlayerSummary, StatsApiResponse} from "@/types/stats";
import {resolveServerMeta} from "@/lib/formatters";
import {OverviewSection} from "@/components/stats/OverviewSection";
import {LeaderboardSection} from "@/components/stats/LeaderboardSection";
import {PlayerDetailModal} from "@/components/stats/PlayerDetailModal";

type ThemeMode = "light" | "dark" | "system";

export default function StatsDashboard() {
    const [data, setData] = useState<StatsApiResponse | null>(null);
    const [loading, setLoading] = useState<boolean>(true);
    const [refreshing, setRefreshing] = useState<boolean>(false);
    const [error, setError] = useState<string | null>(null);

    const [searchQuery, setSearchQuery] = useState<string>("");
    const [selectedUuid, setSelectedUuid] = useState<string | null>(null);
    const [leaderboardMetric, setLeaderboardMetric] = useState<LeaderboardMetric>("playtime");

    const [themeMode, setThemeMode] = useState<ThemeMode>("system");
    const [isDark, setIsDark] = useState<boolean>(true);

    useEffect(() => {
        const saved = (localStorage.getItem("obsidian-theme") ||
            localStorage.getItem("everest-theme")) as ThemeMode | null;
        if (saved === "light" || saved === "dark" || saved === "system") {
            setThemeMode(saved);
        }
    }, []);

    useEffect(() => {
        const mediaQuery = window.matchMedia("(prefers-color-scheme: dark)");

        const applyTheme = (mode: ThemeMode) => {
            const resolvedDark = mode === "dark" || (mode === "system" && mediaQuery.matches);
            setIsDark(resolvedDark);
            document.documentElement.classList.toggle("dark", resolvedDark);
        };

        applyTheme(themeMode);
        localStorage.setItem("obsidian-theme", themeMode);

        const handleSystemChange = () => {
            if (themeMode === "system") {
                applyTheme("system");
            }
        };

        mediaQuery.addEventListener("change", handleSystemChange);
        return () => mediaQuery.removeEventListener("change", handleSystemChange);
    }, [themeMode]);

    const apiBase = process.env.NEXT_PUBLIC_API_BASE_URL?.replace(/\/$/, "") || "";

    const fetchStats = useCallback(
        async (isManualRefresh = false) => {
            try {
                if (isManualRefresh) {
                    setRefreshing(true);
                } else {
                    setLoading(true);
                }
                setError(null);

                const url = isManualRefresh ? `${apiBase}/api/stats?refresh=true` : `${apiBase}/api/stats`;
                const res = await fetch(url, {cache: "no-store"});

                if (!res.ok) {
                    const errJson = await res.json().catch(() => ({}));
                    throw new Error(errJson.details || errJson.error || `HTTP ${res.status}`);
                }

                const json = (await res.json()) as StatsApiResponse;
                setData(json);

                setSelectedUuid((prev) => {
                    if (prev && json.players.some((p) => p.uuid === prev)) {
                        return prev;
                    }
                    return null;
                });
            } catch (err) {
                setError(err instanceof Error ? err.message : "데이터를 불러오지 못했습니다.");
            } finally {
                setLoading(false);
                setRefreshing(false);
            }
        },
        [apiBase],
    );

    useEffect(() => {
        fetchStats(false);
        const timer = setInterval(() => {
            fetchStats(false);
        }, 30000);
        return () => clearInterval(timer);
    }, [fetchStats]);

    const meta = useMemo(() => resolveServerMeta(data?.meta), [data?.meta]);

    useEffect(() => {
        if (meta.serverName) {
            document.title = `${meta.serverName} · Live Stats`;
        }
    }, [meta.serverName]);

    const hasMcmmo = useMemo(
        () => meta.capabilities.mcmmo || (data?.overview.totalPowerLevel ?? 0) > 0,
        [meta.capabilities.mcmmo, data?.overview.totalPowerLevel],
    );

    const hasClaims = useMemo(
        () => meta.capabilities.claims || (data?.overview.totalClaims ?? 0) > 0,
        [meta.capabilities.claims, data?.overview.totalClaims],
    );

    const filteredPlayers = useMemo(() => {
        if (!data) return [];
        const q = searchQuery.trim().toLowerCase();
        if (!q) return data.players;
        return data.players.filter(
            (p) =>
                p.username.toLowerCase().includes(q) ||
                (p.nickname && p.nickname.toLowerCase().includes(q)) ||
                p.uuid.toLowerCase().includes(q) ||
                p.rank.toLowerCase().includes(q),
        );
    }, [data, searchQuery]);

    const sortedLeaderboard = useMemo(() => {
        const list = [...filteredPlayers];
        list.sort((a, b) => {
            if (leaderboardMetric === "playtime") return b.totalPlayTimeMs - a.totalPlayTimeMs;
            if (leaderboardMetric === "power") return b.powerLevel - a.powerLevel;
            if (leaderboardMetric === "balance") return b.balance - a.balance;
            if (leaderboardMetric === "claims") return b.claims.usedBlocks - a.claims.usedBlocks;
            if (leaderboardMetric === "mined") {
                return (b.vanillaStats?.totalMined || 0) - (a.vanillaStats?.totalMined || 0);
            }
            if (leaderboardMetric === "advancements") {
                return (b.advancements?.totalCompleted || 0) - (a.advancements?.totalCompleted || 0);
            }
            if (leaderboardMetric === "distance") {
                return (b.vanillaStats?.totalDistanceCm || 0) - (a.vanillaStats?.totalDistanceCm || 0);
            }
            if (leaderboardMetric === "survival") {
                return (b.vanillaStats?.timeSinceDeathMs || 0) - (a.vanillaStats?.timeSinceDeathMs || 0);
            }
            const levelDiff = (b.skills[leaderboardMetric]?.level || 0) - (a.skills[leaderboardMetric]?.level || 0);
            if (levelDiff !== 0) return levelDiff;
            return (b.skills[leaderboardMetric]?.xp || 0) - (a.skills[leaderboardMetric]?.xp || 0);
        });
        return list;
    }, [filteredPlayers, leaderboardMetric]);

    const selectedPlayer: PlayerSummary | null = useMemo(() => {
        if (!data || !selectedUuid) return null;
        return data.players.find((p) => p.uuid === selectedUuid) || null;
    }, [data, selectedUuid]);

    useEffect(() => {
        if (!selectedPlayer) return;
        const handleKeyDown = (e: KeyboardEvent) => {
            if (e.key === "Escape") {
                setSelectedUuid(null);
            }
        };
        const prevOverflow = document.body.style.overflow;
        document.body.style.overflow = "hidden";
        window.addEventListener("keydown", handleKeyDown);
        return () => {
            document.body.style.overflow = prevOverflow;
            window.removeEventListener("keydown", handleKeyDown);
        };
    }, [selectedPlayer]);

    if (loading && !data) {
        return (
            <div
                className="min-h-screen flex flex-col items-center justify-center bg-slate-50 dark:bg-[#090d16] text-slate-800 dark:text-slate-200 p-6 transition-colors">
                <div
                    className="relative flex items-center justify-center w-16 h-16 rounded-2xl bg-cyan-500/10 border border-cyan-500/30 mb-4">
                    <RefreshCw className="w-8 h-8 text-cyan-600 dark:text-cyan-400 animate-spin"/>
                </div>
                <h2 className="text-lg font-semibold tracking-tight">{meta.serverName}</h2>
                <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">데이터를 불러오는 중</p>
            </div>
        );
    }

    if (error && !data) {
        return (
            <div
                className="min-h-screen flex flex-col items-center justify-center bg-slate-50 dark:bg-[#090d16] text-slate-800 dark:text-slate-200 p-6 transition-colors">
                <div
                    className="max-w-md w-full rounded-2xl bg-rose-50 dark:bg-rose-950/30 border border-rose-200 dark:border-rose-500/40 p-6 text-center shadow-sm">
                    <Database className="w-10 h-10 text-rose-500 dark:text-rose-400 mx-auto mb-3"/>
                    <h2 className="text-lg font-bold text-rose-800 dark:text-rose-200">API Connection Error</h2>
                    <p className="text-sm text-rose-600 dark:text-rose-300/90 mt-2 wrap-break-word">{error}</p>
                    <button
                        onClick={() => fetchStats(false)}
                        className="mt-5 inline-flex items-center gap-2 px-4 py-2 rounded-xl bg-rose-600 hover:bg-rose-500 dark:bg-rose-500 dark:hover:bg-rose-400 text-white text-sm font-medium transition"
                    >
                        <RefreshCw className="w-4 h-4"/>
                        Retry
                    </button>
                </div>
            </div>
        );
    }

    const overview = data?.overview;

    return (
        <div
            className="min-h-screen bg-slate-50 dark:bg-[#090d16] text-slate-900 dark:text-slate-100 pb-28 sm:pb-32 transition-colors duration-200">
            {/* Floating Bottom Pill Navigation Bar */}
            <nav
                aria-label="하단 플로팅 컨트롤 바"
                className="fixed bottom-4 sm:bottom-6 inset-x-0 z-40 px-3 sm:px-6 pointer-events-none flex justify-center"
            >
                <div
                    className="pointer-events-auto w-full max-w-xl rounded-full backdrop-blur-2xl bg-white/90 dark:bg-slate-900/90 border border-slate-200/90 dark:border-slate-700/80 shadow-[0_12px_36px_-6px_rgba(15,23,42,0.18)] dark:shadow-[0_12px_40px_-6px_rgba(0,0,0,0.75)] px-2.5 sm:px-3.5 py-1.5 sm:py-2 flex items-center gap-1.5 sm:gap-2.5 transition-colors">
                    <div
                        className="w-8 h-8 sm:w-9 sm:h-9 rounded-full bg-gradient-to-br from-cyan-500 to-blue-600 flex items-center justify-center shadow-md shadow-cyan-500/25 shrink-0"
                        title={meta.serverName}
                    >
                        <Sparkles className="w-4 h-4 text-white"/>
                    </div>

                    <div className="relative flex-1 min-w-0">
                        <Search
                            className="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none"/>
                        <input
                            type="text"
                            value={searchQuery}
                            onChange={(e) => setSearchQuery(e.target.value)}
                            placeholder="플레이어 / UUID 검색..."
                            className="w-full pl-9 pr-7 py-1.5 rounded-full bg-slate-100/90 dark:bg-slate-950/80 border border-slate-200/80 dark:border-slate-800 text-base sm:text-sm text-slate-900 dark:text-slate-100 placeholder-slate-400 dark:placeholder-slate-500 focus:outline-none focus:border-cyan-500/60 transition"
                        />
                        {searchQuery && (
                            <button
                                type="button"
                                onClick={() => setSearchQuery("")}
                                aria-label="검색어 지우기"
                                className="absolute right-2 top-1/2 -translate-y-1/2 p-1 rounded-full text-slate-400 hover:text-slate-600 dark:hover:text-slate-200"
                            >
                                <X className="w-3.5 h-3.5"/>
                            </button>
                        )}
                    </div>

                    <div className="h-5 w-px bg-slate-200 dark:bg-slate-800 shrink-0"/>

                    <div
                        className="inline-flex items-center p-0.5 rounded-full bg-slate-100 dark:bg-slate-950/90 border border-slate-200/80 dark:border-slate-800 shrink-0"
                        role="group"
                        aria-label="테마 모드 선택"
                    >
                        <button
                            type="button"
                            onClick={() => setThemeMode("light")}
                            title="라이트 모드"
                            className={`p-1.5 rounded-full transition ${
                                themeMode === "light"
                                    ? "bg-white dark:bg-slate-800 text-amber-500 shadow-xs"
                                    : "text-slate-500 hover:text-slate-800 dark:text-slate-400 dark:hover:text-slate-200"
                            }`}
                        >
                            <Sun className="w-3.5 h-3.5"/>
                        </button>
                        <button
                            type="button"
                            onClick={() => setThemeMode("dark")}
                            title="다크 모드"
                            className={`p-1.5 rounded-full transition ${
                                themeMode === "dark"
                                    ? "bg-white dark:bg-slate-800 text-cyan-500 dark:text-cyan-400 shadow-xs"
                                    : "text-slate-500 hover:text-slate-800 dark:text-slate-400 dark:hover:text-slate-200"
                            }`}
                        >
                            <Moon className="w-3.5 h-3.5"/>
                        </button>
                        <button
                            type="button"
                            onClick={() => setThemeMode("system")}
                            title="시스템 설정 따르기"
                            className={`p-1.5 rounded-full transition ${
                                themeMode === "system"
                                    ? "bg-white dark:bg-slate-800 text-blue-600 dark:text-blue-400 shadow-xs"
                                    : "text-slate-500 hover:text-slate-800 dark:text-slate-400 dark:hover:text-slate-200"
                            }`}
                        >
                            <Monitor className="w-3.5 h-3.5"/>
                        </button>
                    </div>

                    <button
                        onClick={() => fetchStats(true)}
                        disabled={refreshing}
                        title="새로고침"
                        className="inline-flex items-center gap-1.5 px-3 py-2 sm:py-1.5 rounded-full bg-cyan-500/15 hover:bg-cyan-500/25 active:scale-95 border border-cyan-500/30 text-xs font-semibold text-cyan-700 dark:text-cyan-300 transition disabled:opacity-50 shrink-0"
                    >
                        <RefreshCw
                            className={`w-3.5 h-3.5 text-cyan-600 dark:text-cyan-400 ${refreshing ? "animate-spin" : ""}`}
                        />
                        <span className="hidden sm:inline">새로고침</span>
                    </button>
                </div>
            </nav>

            <main className="max-w-7xl mx-auto px-3.5 sm:px-6 lg:px-8 pt-4 sm:pt-7 space-y-4 sm:space-y-6">
                {/* Top Brand & Status Banner */}
                <div className="flex items-center justify-between gap-3">
                    <div className="flex items-center gap-2.5 sm:gap-3 min-w-0">
                        <div
                            className="w-9 h-9 sm:w-10 sm:h-10 rounded-xl bg-gradient-to-br from-cyan-500 to-blue-600 flex items-center justify-center shadow-lg shadow-cyan-500/20 shrink-0">
                            <Sparkles className="w-4 h-4 sm:w-5 sm:h-5 text-white"/>
                        </div>
                        <div className="min-w-0">
                            <div className="flex items-center gap-2">
                                <h1 className="text-base sm:text-xl font-extrabold tracking-tight text-slate-900 dark:text-white truncate">
                                    {meta.serverName}
                                </h1>
                                <span
                                    className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] sm:text-[11px] font-semibold bg-emerald-500/10 text-emerald-700 dark:text-emerald-400 border border-emerald-500/25 shrink-0">
                                    <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 dark:bg-emerald-400 animate-pulse"/>
                                    LIVE
                                </span>
                            </div>
                            <p className="text-[11px] sm:text-xs text-slate-500 dark:text-slate-400">
                                Live Stats Dashboard
                            </p>
                        </div>
                    </div>

                    {overview && (
                        <div
                            className="text-[11px] sm:text-xs text-slate-500 flex items-center gap-1.5 shrink-0 bg-white dark:bg-slate-900/80 border border-slate-200/80 dark:border-slate-800 px-2.5 py-1.5 rounded-full">
                            <Activity className="w-3.5 h-3.5 text-cyan-600 dark:text-cyan-400 shrink-0"/>
                            <span className="hidden sm:inline">최근 업데이트:</span>
                            <span className="text-slate-700 dark:text-slate-300 font-medium">
                                {new Date(overview.queriedAt).toLocaleTimeString("ko-KR")}
                            </span>
                        </div>
                    )}
                </div>

                {overview && (
                    <OverviewSection
                        overview={overview}
                        meta={meta}
                        isDark={isDark}
                        hasMcmmo={hasMcmmo}
                        hasClaims={hasClaims}
                    />
                )}

                <div
                    className="flex items-center justify-between flex-wrap gap-2 border-b border-slate-200 dark:border-slate-800/80 pb-2.5 sm:pb-3">
                    <div className="flex items-center gap-2.5">
                        <div
                            className="w-8 h-8 rounded-lg bg-cyan-500/15 border border-cyan-500/30 flex items-center justify-center shrink-0">
                            <Trophy className="w-4 h-4 text-cyan-600 dark:text-cyan-400"/>
                        </div>
                        <div>
                            <h2 className="text-sm sm:text-base font-bold text-slate-900 dark:text-white">
                                리더보드 & 플레이어 스탯
                            </h2>
                            <p className="text-[11px] sm:text-xs text-slate-500 dark:text-slate-400">
                                플레이어를 클릭하면 상세 스탯을 확인할 수 있습니다.
                            </p>
                        </div>
                    </div>
                </div>

                <LeaderboardSection
                    players={sortedLeaderboard}
                    selectedPlayer={selectedPlayer}
                    leaderboardMetric={leaderboardMetric}
                    onSelectMetric={setLeaderboardMetric}
                    onSelectPlayer={setSelectedUuid}
                    meta={meta}
                    isDark={isDark}
                    hasMcmmo={hasMcmmo}
                    hasClaims={hasClaims}
                />

                {selectedPlayer && (
                    <PlayerDetailModal
                        player={selectedPlayer}
                        meta={meta}
                        isDark={isDark}
                        hasMcmmo={hasMcmmo}
                        hasClaims={hasClaims}
                        onClose={() => setSelectedUuid(null)}
                    />
                )}
            </main>
        </div>
    );
}
