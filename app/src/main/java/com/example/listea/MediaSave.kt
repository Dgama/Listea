package com.example.listea

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copying a single file out of wherever it lives and into an album of Listea's own.
 *
 * The second place in the app that writes to storage, and the opposite of [FileManagement] in
 * every way that matters: that one only ever deletes, from the folder the user granted; this one
 * only ever creates, and never inside that folder. The source is opened read-only and is not
 * touched — a save leaves the reviewed file exactly where it was, which is what makes this safe
 * to offer from a viewer that is otherwise unable to change anything.
 *
 * The destination is DCIM/Listea, and that choice is the whole feature: a gallery app builds its
 * albums out of the media-store directories it finds, so a folder under DCIM surfaces as an album
 * called "Listea" in Photos, in Gallery, and in anything else that reads MediaStore. Listea does
 * not create an album through some album API — there is no such API — it writes a file into a
 * directory and lets the media store draw the conclusion.
 *
 * DCIM rather than Pictures because it is the one primary directory that accepts both images and
 * video, so a saved photograph and a saved clip land in the *same* album instead of two albums
 * that merely share a name.
 *
 * Deliberately knows nothing about lists, items, tags or actions. Saving is a decision about a
 * file, made in front of that file, and it is not recorded anywhere: it is not an action, it does
 * not affect completion, and no webhook hears about it.
 */

/** What the album is called wherever it surfaces — the directory name and the gallery's label. */
const val ListeaAlbumName = "Listea"

/**
 * The primary directory the album lives under, spelled out rather than read from [Environment].
 *
 * Same string either way, but a literal is a compile-time constant that plain JVM tests can
 * assert against, and `Environment.DIRECTORY_DCIM` is a framework field that is not populated off
 * device.
 */
private const val AlbumPrimaryDirectory = "DCIM"

/** DCIM/Listea, as MediaStore's RELATIVE_PATH wants it and as the legacy path spells it too. */
const val ListeaAlbumRelativePath = "$AlbumPrimaryDirectory/$ListeaAlbumName"

/**
 * The primary directory a `txt` export is written under.
 *
 * A second constant rather than a reuse of [AlbumPrimaryDirectory], because the two answer to
 * different rules and folding them together is what produced the bug [ListeaExportRelativePath]
 * describes: the album's directory is chosen for what a gallery will show, and this one is
 * constrained by what the platform will accept. Nothing about them is shared but the app's name.
 */
private const val ExportPrimaryDirectory = "Download"

/**
 * Download/Listea/txt — where a `txt` export is written.
 *
 * **Not under the album, and it cannot be.** A gallery album is made by `MediaStore.Images` and
 * `MediaStore.Video`, which do accept `DCIM/`; a `text/plain` file has neither to go through and
 * must be inserted into `MediaStore.Files`, which since Android 11 accepts a non-media row under
 * two primary directories and no others — `Download` and `Documents`. A `DCIM/Listea/txt/` insert
 * fails on the device with *"Primary directory DCIM not allowed … allowed directories are
 * [Download, Documents]"*, whatever permission the app holds. It was written that way first and
 * the release's device pass is what found it.
 *
 * `Download` rather than `Documents` because of what the two already mean to a user: this is
 * output an app produced for them, which is what a download is, and *Documents* is where their own
 * files live. The folder keeps the app's own name underneath, so the whole path still reads as
 * Listea's rather than as a stray file among yesterday's downloads.
 */
const val ListeaExportRelativePath = "$ExportPrimaryDirectory/$ListeaAlbumName/txt"

/** The two things a gallery album can hold. Anything else is not offered a Save at all. */
enum class SavedMediaKind { Image, Video }

/**
 * What the file is, from the provider's answer if there is one and from the extension if not.
 *
 * Null means "not media", which is a refusal rather than a failure: a .txt sitting in a reviewed
 * folder has no business in an album, and writing it into one would produce an entry no gallery
 * can show.
 */
fun mediaKindOf(mimeType: String?, name: String): SavedMediaKind? {
    val mime = mimeType ?: mimeTypeFromName(name)
    return when {
        mime == null -> null
        mime.startsWith("image/") -> SavedMediaKind.Image
        mime.startsWith("video/") -> SavedMediaKind.Video
        else -> null
    }
}

private fun mimeTypeFromName(name: String): String? = MimeTypeMap.getSingleton()
    .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

/** How a save ended, in the terms the Info sheet reports it. */
sealed interface SaveToAlbumResult {
    /** The copy is in the album and the gallery knows about it. */
    data class Saved(val displayName: String) : SaveToAlbumResult

    /** A file of that name is already in the album, so nothing was copied a second time. */
    data class AlreadySaved(val displayName: String) : SaveToAlbumResult

    /** Not an image and not a video, so there is nothing an album could do with it. */
    data object Unsupported : SaveToAlbumResult

    /** Android 9 and below only: the storage permission was asked for and refused. */
    data object PermissionDenied : SaveToAlbumResult

    /** The source could not be read, or the album could not be written. */
    data class Failed(val reason: String) : SaveToAlbumResult
}

/** One sentence under the button, saying what just happened. */
fun saveOutcomeMessage(result: SaveToAlbumResult): String = when (result) {
    is SaveToAlbumResult.Saved -> "Saved to the $ListeaAlbumName album."
    is SaveToAlbumResult.AlreadySaved ->
        "${result.displayName} is already in the $ListeaAlbumName album."

    SaveToAlbumResult.Unsupported -> "Only images and videos can be saved to an album."
    SaveToAlbumResult.PermissionDenied ->
        "Listea needs permission to write to storage before it can save here."

    is SaveToAlbumResult.Failed -> "Could not save: ${result.reason}"
}

/**
 * Whether this device is old enough that saving needs a runtime permission.
 *
 * From Android 10 an app may write its own media into the shared collections with no permission
 * at all. Below that the only way in is the legacy filesystem, which needs WRITE_EXTERNAL_STORAGE
 * — so the permission is asked for on those devices and on no others.
 */
fun needsLegacyStoragePermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) != PackageManager.PERMISSION_GRANTED

/**
 * Copies [sourceUri] into the Listea album under the name it already has.
 *
 * Suspends onto IO: this is a whole-file copy through two content-provider streams, and the
 * caller is a bottom sheet sitting over a photograph.
 *
 * Saving the same file twice is a no-op rather than a second copy. The check is by display name
 * within the album, which is what the user sees and what they would call a duplicate — two
 * different files that happen to share a name are indistinguishable once they are both in one
 * album, so the second is reported as already there rather than silently landing as "name (1)".
 */
suspend fun saveToListeaAlbum(
    context: Context,
    sourceUri: String,
    name: String
): SaveToAlbumResult = withContext(Dispatchers.IO) {
    val parsed = runCatching { sourceUri.toUri() }.getOrNull()
        ?: return@withContext SaveToAlbumResult.Failed("the file could not be located")
    val mimeType = runCatching { context.contentResolver.getType(parsed) }.getOrNull()
        ?: mimeTypeFromName(name)
    val kind = mediaKindOf(mimeType, name) ?: return@withContext SaveToAlbumResult.Unsupported

    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveThroughMediaStore(context, parsed, name, mimeType, kind)
        } else {
            saveThroughLegacyFile(context, parsed, name)
        }
    }.getOrElse { SaveToAlbumResult.Failed(it.message ?: "the copy did not finish") }
}

/**
 * Android 10 and up: the media store owns the directory, so the row is created first and the
 * bytes are written into whatever it hands back.
 *
 * IS_PENDING is what keeps a half-written file out of the gallery. It is set while copying and
 * cleared on success, so a save interrupted by anything at all leaves an entry nobody sees rather
 * than a truncated photograph in the album.
 */
private fun saveThroughMediaStore(
    context: Context,
    source: Uri,
    name: String,
    mimeType: String?,
    kind: SavedMediaKind
): SaveToAlbumResult {
    val resolver = context.contentResolver
    val collection = when (kind) {
        SavedMediaKind.Image ->
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        SavedMediaKind.Video ->
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }

    if (albumHolds(context, collection, name)) return SaveToAlbumResult.AlreadySaved(name)

    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        mimeType?.let { put(MediaStore.MediaColumns.MIME_TYPE, it) }
        put(MediaStore.MediaColumns.RELATIVE_PATH, ListeaAlbumRelativePath)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val target = resolver.insert(collection, values)
        ?: return SaveToAlbumResult.Failed("the album would not accept a new file")

    try {
        resolver.openInputStream(source).use { input ->
            if (input == null) return SaveToAlbumResult.Failed("the file could not be read")
            resolver.openOutputStream(target).use { output ->
                if (output == null) {
                    return SaveToAlbumResult.Failed("the album could not be written to")
                }
                input.copyTo(output)
            }
        }
    } catch (error: Exception) {
        // The pending row is the only trace a failed copy leaves, and it is ours to clean up.
        runCatching { resolver.delete(target, null, null) }
        return SaveToAlbumResult.Failed(error.message ?: "the copy did not finish")
    }

    resolver.update(
        target,
        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
        null,
        null
    )
    return SaveToAlbumResult.Saved(name)
}

/** Whether the album already holds a file under this display name, in this collection. */
private fun albumHolds(context: Context, collection: Uri, name: String): Boolean =
    runCatching {
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf("$ListeaAlbumRelativePath%", name),
            null
        )?.use { it.moveToFirst() } ?: false
    }.getOrDefault(false)

/**
 * Whether a file of this name is already at this path.
 *
 * The same query [albumHolds] makes, over the same collection, and used for the opposite decision:
 * the album treats a name that is taken as *nothing to do*, and an export treats it as *try the next
 * name*. So this answers a question and the caller decides — which is why it is not folded into
 * [writeTextToPublicStorage].
 *
 * A miss answers false, so a provider that cannot be queried produces a write rather than a refusal.
 * Writing into a name that turns out to be taken is the media store's own rename, which still leaves
 * the user's file on disk; refusing to write would leave them with nothing.
 */
suspend fun exportFileExists(
    context: Context,
    relativePath: String,
    name: String
): Boolean = withContext(Dispatchers.IO) {
    val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val directory = relativePath.trimEnd('/') + "/"

    val throughMediaStore = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@runCatching false
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(directory, name),
            null
        )?.use { it.moveToFirst() } ?: false
    }.getOrDefault(false)
    if (throughMediaStore) return@withContext true

    @Suppress("DEPRECATION")
    val onDisk = File(
        Environment.getExternalStoragePublicDirectory(ExportPrimaryDirectory),
        relativePath.removePrefix("$ExportPrimaryDirectory/")
    )
    File(onDisk, name).exists()
}

/**
 * Android 9 and below: there is no media store to insert into, so the directory is created by
 * hand and the scanner is told about the file afterwards.
 *
 * Without that scan the copy exists on disk and in no gallery, which from the user's point of
 * view is the feature not working at all.
 */
private fun saveThroughLegacyFile(
    context: Context,
    source: Uri,
    name: String
): SaveToAlbumResult {
    @Suppress("DEPRECATION")
    val album = File(
        Environment.getExternalStoragePublicDirectory(AlbumPrimaryDirectory),
        ListeaAlbumName
    )
    if (!album.exists() && !album.mkdirs()) {
        return SaveToAlbumResult.Failed("the album folder could not be created")
    }

    val target = File(album, name)
    if (target.exists()) return SaveToAlbumResult.AlreadySaved(name)

    try {
        context.contentResolver.openInputStream(source).use { input ->
            if (input == null) return SaveToAlbumResult.Failed("the file could not be read")
            target.outputStream().use { output -> input.copyTo(output) }
        }
    } catch (error: Exception) {
        runCatching { target.delete() }
        return SaveToAlbumResult.Failed(error.message ?: "the copy did not finish")
    }

    MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
    return SaveToAlbumResult.Saved(name)
}

/**
 * Writes [text] to `<relativePath>/<name>` in shared storage. Returns **null on success** and a
 * sentence saying what went wrong otherwise.
 *
 * A plain `String?` rather than a result type, because there is exactly one way for this to fail —
 * the file did not get written — and the caller has nowhere to branch on it. [SaveToAlbumResult]
 * would be the wrong shape: it carries [SaveToAlbumResult.Unsupported] and
 * [SaveToAlbumResult.PermissionDenied], which are answers about *media*, and a caller that had to
 * name the wrong problem would eventually handle it wrongly.
 *
 * **A name that is taken is not an error here.** The album's own rule is that a second file with a
 * name already present is [SaveToAlbumResult.AlreadySaved] and nothing is copied, which is right for
 * a photograph the user can see they already have. A text export is not a thing the user holds — it
 * is a report of one round — and two rounds a second apart can legitimately collide. So the caller
 * picks a free name and this writes it; it never decides that there is nothing to do.
 *
 * The path goes through `MediaStore.Files` on Android 10 and up — the only collection that will
 * take a non-media file at all — and through the legacy filesystem below that, and
 * [ListeaExportRelativePath] explains what that collection will and will not accept. The two
 * branches are kept in step by hand: the media store takes a `RELATIVE_PATH` with no leading
 * slash, the file API takes the same path with the primary directory stripped, and both start
 * from the one constant.
 *
 * **What protects this from the mistake it already made once** is `MediaSaveTest`: a plain JVM
 * test asserting that the constant's first segment is one the platform allows. Robolectric cannot
 * catch it — its `MediaStore` shadow does not enforce the primary-directory rule — so a test that
 * reads the constant is the only thing standing between a careful edit and a release whose text
 * export silently never lands.
 */
suspend fun writeTextToPublicStorage(
    context: Context,
    relativePath: String,
    name: String,
    text: String
): String? = withContext(Dispatchers.IO) {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeTextThroughMediaStore(context, relativePath.trimEnd('/') + "/", name, text)
        } else {
            writeTextThroughLegacyFile(context, relativePath, name, text)
        }
    }.getOrElse { it.message ?: "the file did not get written" }
}

private fun writeTextThroughMediaStore(
    context: Context,
    relativePath: String,
    name: String,
    text: String
): String? {
    val resolver = context.contentResolver
    val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val target = resolver.insert(collection, values)
        ?: return "the export folder would not accept a new file"

    try {
        val stream = resolver.openOutputStream(target)
            ?: return "the export folder could not be written to"
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    } catch (error: Exception) {
        // The pending row is the only trace a failed write leaves, and it is ours to clean up.
        runCatching { resolver.delete(target, null, null) }
        return error.message ?: "the file did not get written"
    }

    resolver.update(
        target,
        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
        null,
        null
    )
    return null
}

private fun writeTextThroughLegacyFile(
    context: Context,
    relativePath: String,
    name: String,
    text: String
): String? {
    @Suppress("DEPRECATION")
    val directory = File(
        Environment.getExternalStoragePublicDirectory(ExportPrimaryDirectory),
        relativePath.removePrefix("$ExportPrimaryDirectory/")
    )
    if (!directory.exists() && !directory.mkdirs()) return "the export folder could not be created"

    val target = File(directory, name)
    return try {
        target.writeText(text, Charsets.UTF_8)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
        null
    } catch (error: Exception) {
        runCatching { target.delete() }
        error.message ?: "the file did not get written"
    }
}
