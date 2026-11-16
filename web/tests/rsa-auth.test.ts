import assert from "node:assert/strict";
import crypto from "node:crypto";
import {afterEach, describe, it} from "node:test";
import {clearRsaKeyCache, getRsaAuthHeaders, signRsaPayload} from "../src/lib/rsa-auth.ts";

describe("RSA auth helper", () => {
    const origEnvKey = process.env.RSA_PRIVATE_KEY;
    const origEnvPath = process.env.RSA_PRIVATE_KEY_PATH;

    afterEach(() => {
        clearRsaKeyCache();
        if (origEnvKey === undefined) delete process.env.RSA_PRIVATE_KEY;
        else process.env.RSA_PRIVATE_KEY = origEnvKey;
        if (origEnvPath === undefined) delete process.env.RSA_PRIVATE_KEY_PATH;
        else process.env.RSA_PRIVATE_KEY_PATH = origEnvPath;
    });

    it("signs METHOD\\nPATH\\nTIMESTAMP verifiable by corresponding RSA public key", () => {
        const {publicKey, privateKey} = crypto.generateKeyPairSync("rsa", {
            modulusLength: 2048,
            publicKeyEncoding: {type: "spki", format: "pem"},
            privateKeyEncoding: {type: "pkcs8", format: "pem"},
        });

        const ts = "1763270400000";
        const headers = signRsaPayload(privateKey, "get", "api/players/Alice", ts);
        assert.equal(headers["x-api-timestamp"], ts);
        assert.ok(headers["x-api-signature"].length > 20);

        const verifier = crypto.createVerify("SHA256");
        verifier.update(`GET\n/api/players/Alice\n${ts}`);
        verifier.end();
        assert.equal(verifier.verify(publicKey, headers["x-api-signature"], "base64"), true);
    });

    it("normalizes escaped newlines from RSA_PRIVATE_KEY env var", () => {
        const {publicKey, privateKey} = crypto.generateKeyPairSync("rsa", {
            modulusLength: 2048,
            publicKeyEncoding: {type: "spki", format: "pem"},
            privateKeyEncoding: {type: "pkcs8", format: "pem"},
        });

        clearRsaKeyCache();
        process.env.RSA_PRIVATE_KEY = privateKey.replace(/\n/g, "\\n");
        const headers = getRsaAuthHeaders("GET", "/api/stats");
        assert.ok(headers["x-api-signature"]);
        assert.ok(headers["x-api-timestamp"]);

        const verifier = crypto.createVerify("SHA256");
        verifier.update(`GET\n/api/stats\n${headers["x-api-timestamp"]}`);
        verifier.end();
        assert.equal(verifier.verify(publicKey, headers["x-api-signature"], "base64"), true);
    });
});
