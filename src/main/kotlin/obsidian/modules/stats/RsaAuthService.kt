package obsidian.modules.stats

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.*
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.*
import kotlin.math.abs

/**
 * Manages RSA-2048 keys and verifies `SHA256withRSA` signed requests from external web dashboards (`Dash`).
 * Signed payload format: `"${method.uppercase()}\n$path\n$timestampSeconds"`.
 */
class RsaAuthService(
    private var keysDir: Path,
    private var maxClockSkewSeconds: Long = 300L,
) {

    @Volatile
    private var cachedPublicKey: PublicKey? = null

    val publicKeyFile: Path
        get() = keysDir.resolve("public_key.pem")

    val privateKeyFile: Path
        get() = keysDir.resolve("private_key.pem")

    fun configure(newKeysDir: Path, newMaxClockSkewSeconds: Long) {
        keysDir = newKeysDir
        maxClockSkewSeconds = newMaxClockSkewSeconds.coerceAtLeast(1L)
        cachedPublicKey = null
        ensureKeys()
    }

    /** Ensures both PEM files exist, generating a fresh 2048-bit keypair when absent. */
    @Synchronized
    fun ensureKeys(): Boolean {
        if (Files.exists(publicKeyFile) && Files.exists(privateKeyFile)) {
            cachedPublicKey = loadPublicKey(publicKeyFile)
            return false
        }
        generateKeys()
        return true
    }

    /** Generates and writes a new 2048-bit RSA keypair, replacing any existing files. */
    @Synchronized
    fun generateKeys(): KeyPair {
        Files.createDirectories(keysDir)
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val pair = gen.generateKeyPair()

        Files.writeString(publicKeyFile, toPem("PUBLIC KEY", pair.public.encoded), StandardCharsets.UTF_8)
        Files.writeString(privateKeyFile, toPem("PRIVATE KEY", pair.private.encoded), StandardCharsets.UTF_8)
        cachedPublicKey = pair.public
        return pair
    }

    fun getPublicKey(): PublicKey {
        cachedPublicKey?.let { return it }
        ensureKeys()
        return cachedPublicKey ?: error("RSA public key could not be initialized")
    }

    fun verifySignedRequest(
        method: String,
        path: String,
        timestampHeader: String?,
        signatureHeader: String?,
        nowEpochSeconds: Long = Instant.now().epochSecond,
    ): Boolean {
        if (timestampHeader.isNullOrBlank() || signatureHeader.isNullOrBlank()) return false
        val rawTimestamp = timestampHeader.trim()
        val parsed = rawTimestamp.toLongOrNull() ?: return false
        // Support both Unix milliseconds (e.g. Next.js Date.now()) and Unix seconds.
        val timestampSeconds = if (parsed >= 1_000_000_000_000L) parsed / 1000L else parsed
        if (abs(nowEpochSeconds - timestampSeconds) > maxClockSkewSeconds) return false

        val payload = buildPayload(method, path, rawTimestamp)
        val sigBytes = runCatching {
            Base64.getDecoder().decode(signatureHeader.trim())
        }.getOrNull() ?: return false

        return runCatching {
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(getPublicKey())
            verifier.update(payload.toByteArray(StandardCharsets.UTF_8))
            verifier.verify(sigBytes)
        }.getOrDefault(false)
    }

    companion object {
        fun buildPayload(method: String, path: String, timestamp: Long): String =
            buildPayload(method, path, timestamp.toString())

        fun buildPayload(method: String, path: String, timestamp: String): String =
            "${method.uppercase()}\n$path\n$timestamp"

        fun signPayload(privateKey: PrivateKey, method: String, path: String, timestampSeconds: Long): String {
            val signer = Signature.getInstance("SHA256withRSA")
            signer.initSign(privateKey)
            signer.update(buildPayload(method, path, timestampSeconds).toByteArray(StandardCharsets.UTF_8))
            return Base64.getEncoder().encodeToString(signer.sign())
        }

        fun loadPublicKey(path: Path): PublicKey {
            val pem = Files.readString(path, StandardCharsets.UTF_8)
            val base64 = stripPemHeaders(pem)
            val der = Base64.getDecoder().decode(base64)
            return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        }

        fun loadPrivateKey(path: Path): PrivateKey {
            val pem = Files.readString(path, StandardCharsets.UTF_8)
            val base64 = stripPemHeaders(pem)
            val der = Base64.getDecoder().decode(base64)
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        }

        fun toPem(label: String, encoded: ByteArray): String {
            val b64 = Base64.getMimeEncoder(64, "\n".toByteArray(StandardCharsets.UTF_8)).encodeToString(encoded)
            return "-----BEGIN $label-----\n$b64\n-----END $label-----\n"
        }

        private fun stripPemHeaders(pem: String): String =
            pem.replace(Regex("-----BEGIN [A-Z0-9 ]+-----"), "").replace(Regex("-----END [A-Z0-9 ]+-----"), "")
                .replace(Regex("\\s+"), "")
    }
}
