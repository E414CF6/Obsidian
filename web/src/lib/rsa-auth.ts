import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

let cachedPrivateKey: string | null = null;

export function clearRsaKeyCache(): void {
    cachedPrivateKey = null;
}

function loadPrivateKey(): string | null {
    if (cachedPrivateKey) {
        return cachedPrivateKey;
    }

    // 1. Check environment variable containing PEM directly (recommended for Vercel)
    const envKey = process.env.RSA_PRIVATE_KEY?.trim();
    if (envKey) {
        cachedPrivateKey = envKey.replace(/\\n/g, "\n");
        return cachedPrivateKey;
    }

    // 2. Check path specified in environment variable
    const envPath = process.env.RSA_PRIVATE_KEY_PATH?.trim();
    if (envPath) {
        const resolved = path.isAbsolute(envPath) ? envPath : path.resolve(/* turbopackIgnore: true */ process.cwd(), envPath);
        if (fs.existsSync(/* turbopackIgnore: true */ resolved)) {
            cachedPrivateKey = fs.readFileSync(/* turbopackIgnore: true */ resolved, "utf8");
            return cachedPrivateKey;
        }
    }

    // 3. Fallback candidate paths (including Obsidian local Paper run directory)
    const candidatePaths = [
        path.resolve(/* turbopackIgnore: true */ process.cwd(), "keys", "private_key.pem"),
        path.resolve(/* turbopackIgnore: true */ process.cwd(), "..", "run", "plugins", "Obsidian", "keys", "private_key.pem"),
        path.resolve(/* turbopackIgnore: true */ process.cwd(), "..", "plugins", "Obsidian", "keys", "private_key.pem"),
        path.resolve(/* turbopackIgnore: true */ process.cwd(), "..", "keys", "private_key.pem"),
    ];

    for (const candidate of candidatePaths) {
        if (fs.existsSync(/* turbopackIgnore: true */ candidate)) {
            try {
                cachedPrivateKey = fs.readFileSync(/* turbopackIgnore: true */ candidate, "utf8");
                return cachedPrivateKey;
            } catch {
                // ignore and check next
            }
        }
    }

    return null;
}

/**
 * Signs `METHOD\nPATH\nTIMESTAMP` with SHA256withRSA and returns the required auth headers.
 */
export function signRsaPayload(
    privateKeyPem: string,
    method: string,
    requestPath: string,
    timestamp: string = Date.now().toString(),
): Record<string, string> {
    const normalizedPath = requestPath.startsWith("/") ? requestPath : `/${requestPath}`;
    const dataToSign = `${method.toUpperCase()}\n${normalizedPath}\n${timestamp}`;
    const signer = crypto.createSign("SHA256");
    signer.update(dataToSign);
    signer.end();
    const signature = signer.sign(privateKeyPem, "base64");

    return {
        "x-api-signature": signature,
        "x-api-timestamp": timestamp,
    };
}

/**
 * Generates the required RSA-SHA256 signature and timestamp headers for remote API calls.
 *
 * @param method HTTP method (e.g. "GET")
 * @param requestPath Request path without query string (e.g. "/api/stats")
 */
export function getRsaAuthHeaders(method: string, requestPath: string): Record<string, string> {
    const privateKeyPem = loadPrivateKey();
    if (!privateKeyPem) {
        return {};
    }

    try {
        return signRsaPayload(privateKeyPem, method, requestPath);
    } catch (err) {
        console.error("[RSA Auth] Failed to generate RSA signature:", err);
        return {};
    }
}
