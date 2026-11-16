import {NextRequest, NextResponse} from "next/server";
import {StatsApiResponse} from "@/types/stats";
import {getRsaAuthHeaders} from "@/lib/rsa-auth";

export const dynamic = "force-dynamic";

const CORS_HEADERS = {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, x-api-key, x-api-signature, x-api-timestamp",
};

export async function OPTIONS() {
    return new NextResponse(null, {status: 204, headers: CORS_HEADERS});
}

export async function GET(request: NextRequest) {
    try {
        const remoteApiUrl = process.env.REMOTE_API_URL?.trim();
        const apiSecretKey = process.env.API_SECRET_KEY?.trim();

        if (process.env.REQUIRE_API_KEY === "true" && apiSecretKey) {
            const providedKey = request.headers.get("x-api-key");
            if (providedKey !== apiSecretKey) {
                return NextResponse.json(
                    {error: "Unauthorized: Invalid x-api-key"},
                    {status: 401, headers: CORS_HEADERS},
                );
            }
        }

        if (!remoteApiUrl) {
            return NextResponse.json(
                {
                    error: "REMOTE_API_URL is not configured.",
                    details: "Set REMOTE_API_URL in .env.local (e.g., http://127.0.0.1:4000) pointing to Obsidian's stats HTTP API.",
                },
                {status: 503, headers: CORS_HEADERS},
            );
        }

        const refresh = request.nextUrl.searchParams.get("refresh") === "true";
        const search = request.nextUrl.searchParams.get("q")?.trim();
        const upstreamParams = new URLSearchParams();
        if (refresh) upstreamParams.set("refresh", "true");
        if (search) upstreamParams.set("q", search);
        const queryString = upstreamParams.toString();

        const targetUrl = `${remoteApiUrl.replace(/\/$/, "")}/api/stats${queryString ? `?${queryString}` : ""}`;
        const rsaHeaders = getRsaAuthHeaders("GET", "/api/stats");
        const res = await fetch(targetUrl, {
            headers: {
                Accept: "application/json",
                ...rsaHeaders,
                ...(apiSecretKey ? {"x-api-key": apiSecretKey} : {}),
            },
            cache: "no-store",
        });

        if (!res.ok) {
            throw new Error(`Remote API responded with status ${res.status}`);
        }

        const remoteData = (await res.json()) as StatsApiResponse;
        const data: StatsApiResponse = {
            ...remoteData,
            overview: {
                ...remoteData.overview,
                sourceMode: "remote-api-proxy",
            },
        };

        return NextResponse.json(data, {headers: CORS_HEADERS});
    } catch (error) {
        console.error("[GET /api/stats] Error:", error);
        return NextResponse.json(
            {
                error: "통계 데이터를 불러오는 중 오류가 발생했습니다.",
                details: error instanceof Error ? error.message : String(error),
            },
            {status: 500, headers: CORS_HEADERS},
        );
    }
}
