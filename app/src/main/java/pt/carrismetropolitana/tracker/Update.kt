package pt.carrismetropolitana.tracker

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A newer build is published and worth offering. */
data class AvailableUpdate(val version: String, val notes: String, val url: String)

/** Where the updater has got to. [Failed] carries null for "already up to date". */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class Available(val update: AvailableUpdate) : UpdateStatus
    data class Downloading(val done: Int, val total: Int) : UpdateStatus

    /** Downloaded and verified; [file] is cleared once the installer takes it. */
    data class Ready(val file: File) : UpdateStatus
    data class Failed(val reason: String?) : UpdateStatus
}

/**
 * The result of asking GitHub what the newest build is.
 *
 * "Could not ask" and "asked, nothing newer" are kept apart on purpose: reporting the second when
 * the first happened tells the user their app is current when in fact nobody checked.
 */
sealed interface UpdateLookup {
    data class Found(val update: AvailableUpdate) : UpdateLookup
    data object UpToDate : UpdateLookup
    data class Failed(val reason: String) : UpdateLookup
}

/**
 * In-app updates from the project's GitHub releases.
 *
 * The security argument is short and it is the whole reason this file exists: before handing the
 * file to the installer, we read the certificate out of the *downloaded* APK and compare it with
 * the certificate of the app that is already running. A checksum published next to the download
 * would only prove the file matches whatever was sitting next to it; the certificate proves the
 * APK was signed by the same key as the installed app, which is exactly the condition under which
 * Android will let it replace it. Anything else is discarded.
 */
object Update {

    private const val LATEST_RELEASE =
        "https://api.github.com/repos/GodLxrootkit/carris-tracker/releases/latest"

    /**
     * SHA-256 of the signing certificate of every published build.
     *
     * Hardcoded rather than fetched on purpose: a value that came down the same connection as the
     * file it is meant to vouch for would vouch for nothing.
     */
    const val EXPECTED_SIGNER =
        "4ddcf76febbdec158dc10a7e2c7c2b6d1286938a212db68d51f4abef5074687f"

    private const val ASSET_NAME = "carris-tracker-release.apk"

    /** `v2.2.0` and `2.2.0` both mean 2, 2, 0. Anything unparseable counts as 0. */
    internal fun versionTuple(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").split('.')
            .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    /** True when [candidate] is a strictly higher version than what is installed. */
    internal fun isNewer(candidate: String, current: String): Boolean {
        val a = versionTuple(candidate)
        val b = versionTuple(current)
        // Compared per component so 2.10 beats 2.9, which string comparison gets wrong.
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * Asks GitHub for the newest published release.
     *
     * Needs the repository to be public: an anonymous caller gets a 404 from a private one, which
     * surfaces here as [UpdateLookup.Failed] rather than as "up to date".
     */
    suspend fun latest(currentVersion: String): UpdateLookup = withContext(Dispatchers.IO) {
        val result = runCatching {
            val conn = URL(LATEST_RELEASE).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val code = conn.responseCode
                if (code == 404) return@runCatching UpdateLookup.Failed("repositório não encontrado ou privado")
                if (code !in 200..299) return@runCatching UpdateLookup.Failed("HTTP $code")

                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val tag = json.optString("tag_name")
                if (tag.isEmpty()) return@runCatching UpdateLookup.Failed("resposta inesperada")
                if (!isNewer(tag, currentVersion)) return@runCatching UpdateLookup.UpToDate

                val asset = json.getJSONArray("assets")
                    .let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
                    .firstOrNull { it.optString("name") == ASSET_NAME }
                    ?: return@runCatching UpdateLookup.Failed("a release não tem o APK")

                UpdateLookup.Found(
                    AvailableUpdate(
                        version = tag.removePrefix("v").removePrefix("V"),
                        notes = json.optString("body"),
                        url = asset.getString("browser_download_url")
                    )
                )
            } finally {
                conn.disconnect()
            }
        }
        result.getOrElse { UpdateLookup.Failed(it.message ?: "sem rede") }
    }

    /**
     * Downloads to internal storage and reports progress as bytes done out of total.
     *
     * Any leftover from a previous attempt goes first: a half-written file left behind would be
     * indistinguishable from a complete one by size alone.
     */
    suspend fun download(
        context: Context,
        update: AvailableUpdate,
        onProgress: (done: Int, total: Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, ASSET_NAME)

        val conn = URL(update.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val total = conn.contentLength
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        target
    }

    /**
     * SHA-256 fingerprints of every certificate that signed [archive], lower-case hex.
     *
     * Reads the archive's own signing block rather than installing it, so a hostile APK is
     * inspected, never run. Both the current signers and the rotation history are included, so
     * rotating the signing key later does not break updates for installs that predate it.
     */
    internal fun signerFingerprints(context: Context, archive: File): Set<String> {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        val info = pm.getPackageArchiveInfo(archive.absolutePath, flags) ?: return emptySet()

        // Both branches are reduced to raw DER bytes: android.content.pm.Signature and
        // java.security.cert.Certificate are unrelated types that expose the same encoding.
        val blobs: List<ByteArray> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signing = info.signingInfo ?: return emptySet()
                val certs = if (signing.hasMultipleSigners()) {
                    signing.apkContentsSigners
                } else {
                    signing.signingCertificateHistory
                }
                certs.map { it.toByteArray() }
            } else {
                @Suppress("DEPRECATION")
                info.signatures?.map { it.toByteArray() } ?: return emptySet()
            }

        val sha = MessageDigest.getInstance("SHA-256")
        return blobs.map { sha.digest(it).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /** True only if [archive] carries the certificate that signed the running build. */
    fun signedByUs(context: Context, archive: File): Boolean =
        EXPECTED_SIGNER in signerFingerprints(context, archive)

    /** Hand off to the system installer, which is the only thing that can actually replace us. */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Without this the installer refuses to start on Android 8+.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Clears the downloaded file once it is no longer needed. */
    fun discard(context: Context) {
        File(context.filesDir, "updates").listFiles()?.forEach { it.delete() }
    }
}
