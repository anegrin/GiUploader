package io.github.giuploader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

enum class FirmwareKind(val label: String, val asset: String) {
    C1CAN("C1CAN", "GiUCAN_C1CAN.elf"),
    BHCAN("BHCAN", "GiUCAN_BHCAN.elf"),
    SLCAN("SLCAN", "GiUCAN_SLCAN.elf")
}

data class Firmware(val bytes: ByteArray, val name: String)

data class FirmwareRelease(
    val tagName: String,
    val assetUrls: Map<String, String>,
    val assetSha256: Map<String, String> = emptyMap(),
    val name: String = tagName,
    val publishedAt: String = "",
    val apiUrl: String = ""
)

object FirmwareRepository {
    private const val RELEASES_API = "https://api.github.com/repos/anegrin/GiUCAN/releases"

    fun listReleases(): List<FirmwareRelease> {
        val releases = mutableListOf<FirmwareRelease>()
        var page = 1
        while (true) {
            val json = URL("$RELEASES_API?per_page=100&page=$page").openConnection().readText()
            val pageReleases = JSONArray(json)
            if (pageReleases.length() == 0) break

            for (index in 0 until pageReleases.length()) {
                val release = pageReleases.getJSONObject(index)
                val assets = release.getJSONArray("assets")
                val assetUrls = buildMap {
                    for (assetIndex in 0 until assets.length()) {
                        val asset = assets.getJSONObject(assetIndex)
                        put(asset.getString("name"), asset.getString("browser_download_url"))
                    }
                }
                val assetSha256 = buildMap {
                    for (assetIndex in 0 until assets.length()) {
                        val asset = assets.getJSONObject(assetIndex)
                        val digest = asset.optString("digest", "")
                        if (digest.startsWith("sha256:", ignoreCase = true)) {
                            put(asset.getString("name"), digest.substringAfter(':'))
                        }
                    }
                }
                val tagName = release.getString("tag_name")
                releases += FirmwareRelease(
                    tagName = tagName,
                    assetUrls = assetUrls,
                    assetSha256 = assetSha256,
                    name = release.optionalString("name").ifBlank { tagName },
                    publishedAt = release.optionalString("published_at"),
                    apiUrl = release.optionalString("url")
                )
            }
            if (pageReleases.length() < 100) break
            page++
        }
        require(releases.isNotEmpty()) { "No GiUCAN releases were found" }
        return releases
    }

    fun download(context: Context, kind: FirmwareKind, release: FirmwareRelease): Firmware {
        val assetUrl = release.assetUrls[kind.asset]
        require(assetUrl != null) {
            "${kind.asset} was not found in release ${release.tagName}"
        }
        val expectedSha256 = release.assetSha256[kind.asset]
        require(expectedSha256 != null && SHA256_PATTERN.matches(expectedSha256)) {
            "GitHub did not provide a valid SHA-256 digest for ${kind.asset} in release ${release.tagName}"
        }

        val safeTag = release.tagName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(context.cacheDir, "$safeTag-${kind.asset}")
        val temporaryFile = File(context.cacheDir, "$safeTag-${kind.asset}.download")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            URL(assetUrl).openConnection().apply {
                connectTimeout = 15000
                readTimeout = 30000
            }.getInputStream().use { input ->
                FileOutputStream(temporaryFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val actualSha256 = digest.digest().toHexString()
            require(actualSha256.equals(expectedSha256, ignoreCase = true)) {
                "Downloaded ${kind.asset} failed GitHub SHA-256 verification"
            }
            if (file.exists()) {
                require(file.delete()) { "Could not replace cached firmware ${kind.asset}" }
            }
            require(temporaryFile.renameTo(file)) {
                "Could not store verified firmware ${kind.asset}"
            }
            return Firmware(file.readBytes(), kind.asset)
        } finally {
            if (temporaryFile.exists()) temporaryFile.delete()
        }
    }

    fun fetchReleaseBody(release: FirmwareRelease): String {
        require(release.apiUrl.isNotBlank()) { "GitHub release URL is unavailable" }
        return JSONObject(URL(release.apiUrl).openConnection().readText())
            .optionalString("body")
    }

    private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")

    private fun org.json.JSONObject.optionalString(key: String): String {
        return if (isNull(key)) "" else optString(key, "")
    }

    private fun ByteArray.toHexString(): String = joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private fun java.net.URLConnection.readText(): String {
        connectTimeout = 15000
        readTimeout = 30000
        return getInputStream().bufferedReader().use { it.readText() }
    }
}
