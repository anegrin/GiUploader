package io.github.giuploader

import android.content.Context
import org.json.JSONArray
import java.net.URL
import java.io.File
import java.io.FileOutputStream

enum class FirmwareKind(val label: String, val asset: String) {
    C1CAN("C1CAN", "GiUCAN_C1CAN.elf"),
    BHCAN("BHCAN", "GiUCAN_BHCAN.elf"),
    SLCAN("SLCAN", "GiUCAN_SLCAN.elf")
}

data class Firmware(val bytes: ByteArray, val name: String)

data class FirmwareRelease(
    val tagName: String,
    val assetUrls: Map<String, String>
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
                releases += FirmwareRelease(release.getString("tag_name"), assetUrls)
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

        val safeTag = release.tagName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(context.cacheDir, "$safeTag-${kind.asset}")
        URL(assetUrl).openConnection().getInputStream().use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        return Firmware(file.readBytes(), kind.asset)
    }

    private fun java.net.URLConnection.readText(): String {
        connectTimeout = 15000
        readTimeout = 30000
        return getInputStream().bufferedReader().use { it.readText() }
    }
}
