package com.mica.music.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mica.music.data.LyricCell
import com.mica.music.data.LyricLine
import com.mica.music.data.LyricCue
import com.mica.music.data.Song
import com.mica.music.data.TrackMetadata
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String = "",
    val durationSec: Int,
    val containerName: String,
    val sampleRateHz: Int,
    val bitsPerSample: Int?,
    val bitrateKbps: Int,
    val channelCount: Int,
    val playbackMimeType: String,
    val albumArtUri: String?,
    val coverColorArgb: Int,
    val mediaUri: String,
    val fileName: String,
    val sizeBytes: Long,
    val year: Int,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val folderPath: String,
    val filePath: String = "",
    val copyright: String = "",
    val codecLabel: String = "",
    val dateAddedMs: Long,
    val dateModifiedMs: Long,
    val externalLyricsSignature: String = "",
    val playCount: Int,
    val lyricsJson: String,
    val queueOrder: Int,
)

@Entity(tableName = "library_meta")
data class LibraryMetaEntity(
    @PrimaryKey val id: Int = 1,
    val lastScanAtMs: Long,
    val lastScanSource: String,
    val totalSizeMb: Int,
    val songCount: Int,
    val sortField: String = "",
    val sortDirection: String = "",
    val fastScrollSectionsJson: String = "",
)

fun SongEntity.toSong(): Song = Song(
    id = id,
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    durationSec = durationSec,
    metadata = TrackMetadata(
        containerName = containerName,
        sampleRateHz = sampleRateHz,
        bitsPerSample = bitsPerSample,
        bitrateKbps = bitrateKbps,
        channelCount = channelCount,
        playbackMimeType = playbackMimeType,
    ),
    albumArtUri = albumArtUri,
    coverColorArgb = coverColorArgb,
    mediaUri = mediaUri,
    playbackUri = null,
    fileName = fileName,
    sizeBytes = sizeBytes,
    year = year,
    trackNumber = trackNumber,
    discNumber = discNumber,
    folderPath = folderPath,
    filePath = filePath,
    copyright = copyright,
    codecLabel = codecLabel,
    dateAddedMs = dateAddedMs,
    dateModifiedMs = dateModifiedMs,
    externalLyricsSignature = externalLyricsSignature,
    playCount = playCount,
    lyrics = decodeLyrics(lyricsJson),
)

/** 用于增量扫描：元数据或路径变化时判定为「已更新」。 */
fun SongEntity.scanFingerprint(): String = buildString {
    append(title); append('\u0001')
    append(artist); append('\u0001')
    append(album); append('\u0001')
    append(albumArtist); append('\u0001')
    append(durationSec); append('\u0001')
    append(mediaUri); append('\u0001')
    append(dateModifiedMs); append('\u0001')
    append(containerName); append('\u0001')
    append(sampleRateHz); append('\u0001')
    append(bitsPerSample); append('\u0001')
    append(bitrateKbps); append('\u0001')
    append(trackNumber); append('\u0001')
    append(discNumber); append('\u0001')
    append(albumArtUri); append('\u0001')
    append(externalLyricsSignature); append('\u0001')
    append(lyricsJson)
}

fun Song.toEntity(queueOrder: Int): SongEntity = SongEntity(
    id = id,
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    durationSec = durationSec,
    containerName = metadata.containerName,
    sampleRateHz = metadata.sampleRateHz,
    bitsPerSample = metadata.bitsPerSample,
    bitrateKbps = metadata.bitrateKbps,
    channelCount = metadata.channelCount,
    playbackMimeType = metadata.playbackMimeType,
    albumArtUri = albumArtUri,
    coverColorArgb = coverColorArgb,
    mediaUri = mediaUri,
    fileName = fileName,
    sizeBytes = sizeBytes,
    year = year,
    trackNumber = trackNumber,
    discNumber = discNumber,
    folderPath = folderPath,
    filePath = filePath,
    copyright = copyright,
    codecLabel = codecLabel,
    dateAddedMs = dateAddedMs,
    dateModifiedMs = dateModifiedMs,
    externalLyricsSignature = externalLyricsSignature,
    playCount = playCount,
    lyricsJson = encodeLyrics(lyrics),
    queueOrder = queueOrder,
)

private fun encodeLyrics(lines: List<LyricLine>): String {
    if (lines.isEmpty()) return "[]"
    val array = JSONArray()
    lines.forEach { line ->
        val encoded = JSONObject()
                .put("t", line.timeMs)
                .put("x", line.text)
        line.endTimeMs?.let { encoded.put("e", it) }
        if (line.cells.isNotEmpty()) {
            val cells = JSONArray()
            line.cells.forEach { cell ->
                cells.put(
                    JSONObject()
                        .put("s", cell.startTimeMs)
                        .put("e", cell.endTimeMs)
                        .put("x", cell.text)
                        .put("timed", cell.timed),
                )
            }
            encoded.put("cells", cells)
        }
        line.subText?.takeIf { it.isNotBlank() }?.let { encoded.put("sub", it) }
        line.romanizationText?.takeIf { it.isNotBlank() }?.let { encoded.put("rom", it) }
        if (line.cues.isNotEmpty()) {
            val cues = JSONArray()
            line.cues.forEach { cue ->
                cues.put(JSONObject().put("t", cue.timeMs).put("x", cue.text))
            }
            encoded.put("c", cues)
        }
        array.put(encoded)
    }
    return array.toString()
}

private fun decodeLyrics(json: String): List<LyricLine> {
    if (json.isBlank() || json == "[]") return emptyList()
    return runCatching {
        val array = JSONArray(json)
        buildList(array.length()) {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val cues = obj.optJSONArray("c")?.let { cueArray ->
                    buildList(cueArray.length()) {
                        for (cueIndex in 0 until cueArray.length()) {
                            val cue = cueArray.optJSONObject(cueIndex) ?: continue
                            if (!cue.has("t") || !cue.has("x")) continue
                            add(LyricCue(timeMs = cue.getInt("t"), text = cue.getString("x")))
                        }
                    }
                }.orEmpty()
                val cells = obj.optJSONArray("cells")?.let { cellArray ->
                    buildList(cellArray.length()) {
                        for (cellIndex in 0 until cellArray.length()) {
                            val cell = cellArray.optJSONObject(cellIndex) ?: continue
                            if (!cell.has("s") || !cell.has("e") || !cell.has("x")) continue
                            add(
                                LyricCell(
                                    startTimeMs = cell.getInt("s"),
                                    endTimeMs = cell.getInt("e"),
                                    text = cell.getString("x"),
                                    timed = cell.optBoolean("timed", true),
                                ),
                            )
                        }
                    }
                }.orEmpty()
                add(
                    LyricLine(
                        timeMs = obj.getInt("t"),
                        text = obj.getString("x"),
                        cues = cues,
                        endTimeMs = obj.takeIf { it.has("e") }?.getInt("e"),
                        cells = cells,
                        subText = obj.optString("sub").takeIf { it.isNotBlank() },
                        romanizationText = obj.optString("rom").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
}
