package com.github.libretube.helpers

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.github.libretube.R
import com.github.libretube.api.JsonHelper
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.WatchHistoryItem
import com.github.libretube.enums.ImportFormat
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.obj.FreeTubeImportPlaylist
import com.github.libretube.obj.FreetubeSubscription
import com.github.libretube.obj.FreetubeSubscriptions
import com.github.libretube.obj.NewPipeSubscription
import com.github.libretube.obj.NewPipeSubscriptions
import com.github.libretube.obj.PipedImportPlaylist
import com.github.libretube.obj.PipedPlaylistFile
import com.github.libretube.obj.YouTubeWatchHistoryFileItem
import com.github.libretube.ui.dialogs.ShareDialog
import com.github.libretube.util.TextUtils
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.util.stream.Collectors

object ImportHelper {
    private const val IMPORT_THUMBNAIL_QUALITY = "mqdefault"

    /**
     * Import subscriptions by a file uri
     */
    suspend fun importSubscriptions(activity: Activity, uri: Uri, importFormat: ImportFormat) {
        try {
            SubscriptionHelper.importSubscriptions(getChannelsFromUri(activity, uri, importFormat))
            activity.toastFromMainDispatcher(R.string.importsuccess)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG(), e.toString())
            val type = activity.contentResolver.getType(uri)
            val message = activity.getString(R.string.unsupported_file_format, type)
            activity.toastFromMainDispatcher(message)
        } catch (e: Exception) {
            Log.e(TAG(), e.toString())
            e.localizedMessage?.let {
                activity.toastFromMainDispatcher(it)
            }
        }
    }

    /**
     * Get a list of channel IDs from a file [Uri]
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun getChannelsFromUri(
        activity: Activity,
        uri: Uri,
        importFormat: ImportFormat
    ): List<String> {
        return when (importFormat) {
            ImportFormat.NEWPIPE -> {
                val subscriptions = activity.contentResolver.openInputStream(uri)?.use {
                    JsonHelper.json.decodeFromStream<NewPipeSubscriptions>(it)
                }
                subscriptions?.subscriptions.orEmpty().map {
                    it.url.replace("${ShareDialog.YOUTUBE_FRONTEND_URL}/channel/", "")
                }
            }

            ImportFormat.FREETUBE -> {
                val subscriptions = activity.contentResolver.openInputStream(uri)?.use {
                    JsonHelper.json.decodeFromStream<FreetubeSubscriptions>(it)
                }
                subscriptions?.subscriptions.orEmpty().map {
                    it.url.replace("${ShareDialog.YOUTUBE_FRONTEND_URL}/channel/", "")
                }
            }

            ImportFormat.YOUTUBECSV -> {
                // import subscriptions from Google/YouTube Takeout
                activity.contentResolver.openInputStream(uri)?.use {
                    it.bufferedReader().use { reader ->
                        reader.lines().map { line -> line.substringBefore(",") }
                            .filter { channelId -> channelId.length == 24 }
                            .collect(Collectors.toList())
                    }
                }.orEmpty()
            }

            else -> throw IllegalArgumentException()
        }
    }

    /**
     * Write the text to the document
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun exportSubscriptions(activity: Activity, uri: Uri, importFormat: ImportFormat) {
        val token = PreferenceHelper.getToken()
        val subs = if (token.isNotEmpty()) {
            RetrofitInstance.authApi.subscriptions(token)
        } else {
            val subscriptions = Database.localSubscriptionDao().getAll().map { it.channelId }
            RetrofitInstance.authApi.unauthenticatedSubscriptions(subscriptions)
        }

        when (importFormat) {
            ImportFormat.NEWPIPE -> {
                val newPipeChannels = subs.map {
                    NewPipeSubscription(it.name, 0, "${ShareDialog.YOUTUBE_FRONTEND_URL}${it.url}")
                }
                val newPipeSubscriptions = NewPipeSubscriptions(subscriptions = newPipeChannels)
                activity.contentResolver.openOutputStream(uri)?.use {
                    JsonHelper.json.encodeToStream(newPipeSubscriptions, it)
                }
            }

            ImportFormat.FREETUBE -> {
                val freeTubeChannels = subs.map {
                    FreetubeSubscription(
                        it.name,
                        "",
                        "${ShareDialog.YOUTUBE_FRONTEND_URL}${it.url}"
                    )
                }
                val freeTubeSubscriptions = FreetubeSubscriptions(subscriptions = freeTubeChannels)
                activity.contentResolver.openOutputStream(uri)?.use {
                    JsonHelper.json.encodeToStream(freeTubeSubscriptions, it)
                }
            }

            else -> throw IllegalArgumentException()
        }

        activity.toastFromMainDispatcher(R.string.exportsuccess)
    }

    /**
     * Import Playlists
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun importPlaylists(activity: Activity, uri: Uri, importFormat: ImportFormat) {
        val importPlaylists = mutableListOf<PipedImportPlaylist>()

        when (importFormat) {
            ImportFormat.PIPED -> {
                val playlistFile = activity.contentResolver.openInputStream(uri)?.use {
                    JsonHelper.json.decodeFromStream<PipedPlaylistFile>(it)
                }
                importPlaylists.addAll(playlistFile?.playlists.orEmpty())

                // convert the YouTube URLs to videoIds
                importPlaylists.forEach { playlist ->
                    playlist.videos = playlist.videos.map { it.takeLast(11) }
                }
            }

            ImportFormat.FREETUBE -> {
                val playlistFile =
                    activity.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val text = inputStream.bufferedReader().readText()
                        runCatching {
                            text.lines().map { line ->
                                JsonHelper.json.decodeFromString<FreeTubeImportPlaylist>(line)
                            }
                        }.getOrNull() ?: runCatching {
                            listOf(JsonHelper.json.decodeFromString<FreeTubeImportPlaylist>(text))
                        }.getOrNull()
                    }

                val playlists = playlistFile.orEmpty().map { playlist ->
                    // convert FreeTube videos to list of string
                    // convert FreeTube playlists to piped playlists
                    PipedImportPlaylist(
                        playlist.name,
                        null,
                        null,
                        playlist.videos.map { it.videoId }
                    )
                }
                importPlaylists.addAll(playlists)
            }

            ImportFormat.YOUTUBECSV -> {
                val playlist = PipedImportPlaylist()
                activity.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val lines = inputStream.bufferedReader().readLines()
                    // invalid playlist file, hence returning
                    if (lines.size < 2) return

                    val playlistName = lines[1].split(",").reversed().getOrNull(2)
                    // the playlist name can be undefined in some cases, e.g. watch later lists
                    playlist.name = playlistName ?: TextUtils.defaultPlaylistName

                    // start directly at the beginning if header playlist info such as name is missing
                    val startIndex = if (playlistName == null) {
                        1
                    } else {
                        // seek to the first blank line
                        var splitIndex = lines.indexOfFirst { line -> line.isBlank() }
                        while (lines.getOrElse(splitIndex) { return }.isBlank()) splitIndex++
                        // skip the line containing the names of the columns
                        splitIndex + 2
                    }
                    for (line in lines.subList(startIndex, lines.size)) {
                        if (line.isBlank()) continue

                        val videoId = line.split(",")
                            .firstOrNull()
                            ?.takeIf { it.isNotBlank() }

                        if (videoId != null) {
                            playlist.videos += videoId.trim()
                        }
                    }
                    importPlaylists.add(playlist)
                }

                // convert the YouTube URLs to videoIds
                importPlaylists.forEach { importPlaylist ->
                    importPlaylist.videos = importPlaylist.videos.map { it.takeLast(11) }
                }
            }

            ImportFormat.URLSORIDS -> {
                activity.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val playlist = PipedImportPlaylist(name = TextUtils.defaultPlaylistName)

                    playlist.videos = inputStream.bufferedReader().readLines()
                        .flatMap { it.split(",") }
                        .mapNotNull { videoUrlOrId ->
                            if (videoUrlOrId.length == 11) {
                                videoUrlOrId
                            } else {
                                TextUtils.getVideoIdFromUri(videoUrlOrId.toUri())
                            }
                        }

                    if (playlist.videos.isNotEmpty()) {
                        importPlaylists.add(playlist)
                    }
                }
            }

            else -> throw IllegalArgumentException()
        }

        if (importPlaylists.isEmpty()) {
            activity.toastFromMainDispatcher(R.string.emptyList)
            return
        }

        try {
            PlaylistsHelper.importPlaylists(importPlaylists)
            activity.toastFromMainDispatcher(R.string.success)
        } catch (e: Exception) {
            Log.e(TAG(), e.toString())
            e.localizedMessage?.let {
                activity.toastFromMainDispatcher(it)
            }
        }
    }

    /**
     * Export Playlists
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun exportPlaylists(activity: Activity, uri: Uri, importFormat: ImportFormat) {
        when (importFormat) {
            ImportFormat.PIPED -> {
                val playlists = PlaylistsHelper.exportPipedPlaylists()
                val playlistFile = PipedPlaylistFile(playlists = playlists)

                activity.contentResolver.openOutputStream(uri)?.use {
                    JsonHelper.json.encodeToStream(playlistFile, it)
                }
                activity.toastFromMainDispatcher(R.string.exportsuccess)
            }

            ImportFormat.FREETUBE -> {
                val playlists = PlaylistsHelper.exportFreeTubePlaylists()

                val freeTubeExportDb = playlists.joinToString("\n") { playlist ->
                    JsonHelper.json.encodeToString(playlist)
                }
                activity.contentResolver.openOutputStream(uri)?.use {
                    it.write(freeTubeExportDb.toByteArray())
                }
                activity.toastFromMainDispatcher(R.string.exportsuccess)
            }

            else -> Unit
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun importWatchHistory(context: Context, uri: Uri, importFormat: ImportFormat) {
        val videos = when (importFormat) {
            ImportFormat.YOUTUBEJSON -> {
                context.contentResolver.openInputStream(uri)?.use {
                    JsonHelper.json.decodeFromStream<List<YouTubeWatchHistoryFileItem>>(it)
                }
                    .orEmpty()
                    .filter { it.activityControls.contains("YouTube watch history") && it.subtitles.isNotEmpty() && it.titleUrl.isNotEmpty() }
                    .reversed()
                    .map {
                        val videoId = it.titleUrl.substring(it.titleUrl.length - 11)

                        WatchHistoryItem(
                            videoId = videoId,
                            title = it.title.replaceFirst("Watched ", ""),
                            uploader = it.subtitles.firstOrNull()?.name,
                            uploaderUrl = it.subtitles.firstOrNull()?.url?.let { url ->
                                url.substring(url.length - 24)
                            },
                            thumbnailUrl = "https://img.youtube.com/vi/${videoId}/${IMPORT_THUMBNAIL_QUALITY}.jpg"
                        )
                    }
            }

            else -> emptyList()
        }

        for (video in videos) {
            DatabaseHelper.addToWatchHistory(video)
        }

        if (videos.isEmpty()) {
            context.toastFromMainDispatcher(R.string.emptyList)
        } else {
            context.toastFromMainDispatcher(R.string.success)
        }
    }
}
