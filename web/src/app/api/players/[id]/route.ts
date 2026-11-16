import {NextRequest, NextResponse} from "next/server";
import {PlayerDetailApiResponse} from "@/types/stats";
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

export async function GET(request: NextRequest, context: { params: Promise<{ id: string }> }) {
    try {
        const {id} = await context.params;
        const decodedId = decodeURIComponent(id).trim();
        if (!decodedId) {
            return NextResponse.json({error: "플레이어 식별자가 비어 있습니다."}, {status: 400, headers: CORS_HEADERS});
        }

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
                    details: "Set REMOTE_API_URL in .env.local pointing to Obsidian's stats HTTP API.",
                },
                {status: 503, headers: CORS_HEADERS},
            );
        }

        const refresh = request.nextUrl.searchParams.get("refresh") === "true";
        const signPath = `/api/players/${decodedId}`;
        const requestPath = `/api/players/${encodeURIComponent(decodedId)}${refresh ? "?refresh=true" : ""}`;
        const rsaHeaders = getRsaAuthHeaders("GET", signPath);

        const res = await fetch(`${remoteApiUrl.replace(/\/$/, "")}${requestPath}`, {
            headers: {
                Accept: "application/json",
                ...rsaHeaders,
                ...(apiSecretKey ? {"x-api-key": apiSecretKey} : {}),
            },
            cache: "no-store",
        });

        if (res.status === 404) {
            return NextResponse.json({error: "해당 플레이어를 찾을 수 없습니다."}, {status: 404, headers: CORS_HEADERS});
        }

        if (!res.ok) {
            throw new Error(`Remote API responded with status ${res.status}`);
        }

        const detail = (await res.json()) as PlayerDetailApiResponse;
        return NextResponse.json(
            {
                ...detail,
                sourceMode: "remote-api-proxy",
            },
            {headers: CORS_HEADERS},
        );
    } catch (error) {
        console.error("[GET /api/players/[id]] Error:", error);
        return NextResponse.json(
            {
                error: "플레이어 데이터를 불러오지 못했습니다.",
                details: error instanceof Error ? error.message : String(error),
            },
            {status: 500, headers: CORS_HEADERS},
        );
    }
}
