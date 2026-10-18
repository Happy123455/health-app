package com.happypipaliya.mealtracker

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToInt

private const val TAG = "MealImporter"

/** Finds new photos in the meals album and copies them into app storage, ready for analysis. */
object Importer {
    private const val MAX_SIDE = 1280
    private const val THUMB_SIDE = 384

    fun hasPhotoAccess(ctx: Context) = granted(ctx, Manifest.permission.READ_MEDIA_IMAGES)

    private fun granted(ctx: Context, permission: String) =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    /** Imports photos from the album that haven't been seen yet. Returns how many were added. */
    fun discoverAlbum(ctx: Context): Int {
        if (!hasPhotoAccess(ctx)) return 0
        val known = Db.get(ctx).knownMediaIds()
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
        )
        val fresh = mutableListOf<Pair<Long, Long>>()
        ctx.contentResolver.query(
            collection, projection,
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ? COLLATE NOCASE",
            arrayOf(Prefs(ctx).albumName),
            "${MediaStore.Images.Media.DATE_ADDED} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (id in known || c.getLong(3) <= 0L) continue // size 0 = camera still writing it
                val taken = c.getLong(1).takeIf { it > 0 } ?: (c.getLong(2) * 1000)
                fresh += id to taken
            }
        }
        return fresh.count { (id, taken) -> import(ctx, ContentUris.withAppendedId(collection, id), id, taken) != null }
    }

    /**
     * Saves a resized copy (and thumbnail) of the photo, keeps its GPS location if it has one,
     * and adds a meal waiting for analysis. Returns the meal id, or null if skipped or failed.
     */
    fun import(ctx: Context, uri: Uri, mediaId: Long?, takenAtHint: Long?): Long? {
        var photo: File? = null
        var thumb: File? = null
        return try {
            val exif = readExif(ctx, uri, mediaId != null)
            val takenAt = takenAtHint ?: queryDateTaken(ctx, uri) ?: exif.time ?: System.currentTimeMillis()
            val bitmap = decode(ctx, uri)
            val dir = File(ctx.filesDir, "photos").apply { mkdirs() }
            val name = UUID.randomUUID().toString()
            photo = File(dir, "$name.jpg").also { f -> f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
            thumb = File(dir, "${name}_t.jpg").also { f -> f.outputStream().use { scaled(bitmap, THUMB_SIDE).compress(Bitmap.CompressFormat.JPEG, 80, it) } }
            val id = Db.get(ctx).insertMeal(mediaId, takenAt, Dates.dateOf(takenAt), exif.lat, exif.lon, photo.path, thumb.path)
            if (id > 0) id else {
                photo.delete(); thumb.delete() // already imported
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not import $uri", e)
            photo?.delete(); thumb?.delete()
            null
        }
    }

    private data class Exif(val lat: Double?, val lon: Double?, val time: Long?)

    private fun readExif(ctx: Context, uri: Uri, isMediaStore: Boolean): Exif = runCatching {
        // Android hides photo locations unless we ask for the original with ACCESS_MEDIA_LOCATION.
        val source = if (isMediaStore && granted(ctx, Manifest.permission.ACCESS_MEDIA_LOCATION)) {
            runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
        } else uri
        open(ctx, source).use { stream ->
            val exif = ExifInterface(stream)
            val latLon = exif.latLong
            val time = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let {
                runCatching {
                    LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.getOrNull()
            }
            Exif(latLon?.getOrNull(0), latLon?.getOrNull(1), time)
        }
    }.getOrElse { Exif(null, null, null) }

    private fun queryDateTaken(ctx: Context, uri: Uri): Long? = if (uri.scheme != "content") null else runCatching {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it > 0 } else null
        }
    }.getOrNull()

    private fun open(ctx: Context, uri: Uri): InputStream =
        if (uri.scheme == "file") File(uri.path!!).inputStream()
        else ctx.contentResolver.openInputStream(uri) ?: error("Cannot open $uri")

    /** Decodes upright (EXIF rotation applied) and no larger than [MAX_SIDE]. */
    private fun decode(ctx: Context, uri: Uri): Bitmap {
        val source = if (uri.scheme == "file") ImageDecoder.createSource(File(uri.path!!))
        else ImageDecoder.createSource(ctx.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(w, h))
            if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    private fun scaled(b: Bitmap, side: Int): Bitmap {
        val s = side.toFloat() / maxOf(b.width, b.height)
        return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).roundToInt(), (b.height * s).roundToInt(), true)
    }
}

/** "Log meal": the camera saves straight into the meals album, then the photo is analysed. */
object MealCapture {
    fun newPhotoUri(ctx: Context): Uri? = runCatching {
        val name = "meal_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/${Prefs(ctx).albumName}")
        }
        ctx.contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
    }.getOrNull()

    fun complete(ctx: Context, uri: Uri) = background {
        Importer.import(ctx, uri, ContentUris.parseId(uri), System.currentTimeMillis())
        Work.scanNow(ctx)
    }

    /** Camera was cancelled: remove the empty entry this app created a moment ago. */
    fun discard(ctx: Context, uri: Uri) = background { runCatching { ctx.contentResolver.delete(uri, null, null) } }

    fun importPicked(ctx: Context, uri: Uri) = background {
        Importer.import(ctx, uri, null, null)
        Work.scanNow(ctx)
    }

    /** Removes the meal from the app (the original photo stays in the Gallery). */
    fun delete(ctx: Context, meal: Meal) = background {
        Db.get(ctx).markDeleted(meal.id)
        meal.photoPath?.let { File(it).delete() }
        meal.thumbPath?.let { File(it).delete() }
        HealthSync.deleteMeal(ctx, meal.id)
    }

    /**
     * Logs a meal again without a new photo: same dish and nutrients (scaled by [portion]), a copy of the
     * original photo, and it's sent to Samsung Health like any meal. Returns the new meal's id.
     */
    suspend fun repeat(ctx: Context, meal: Meal, portion: Double = 1.0, at: Long = System.currentTimeMillis()): Long =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val json = org.json.JSONObject(meal.analysisJson ?: return@withContext -1L)
            if (portion != 1.0) {
                json.optJSONObject("nutrients")?.let { n -> n.keys().asSequence().toList().forEach { k -> n.put(k, n.optDouble(k, 0.0) * portion) } }
                json.optJSONArray("items").objects().forEach { item ->
                    listOf("grams", "calories_kcal", "protein_g", "carbs_g", "fat_g").forEach { k -> item.put(k, item.optDouble(k, 0.0) * portion) }
                }
                val label = when (portion) { 0.5 -> "half portion"; 1.5 -> "bigger portion"; else -> "${portion}x portion" }
                json.put("assumptions", "Repeated from ${Dates.label(meal.date)} ($label). " + json.optString("assumptions"))
            } else {
                json.put("assumptions", "Repeated from ${Dates.label(meal.date)}. " + json.optString("assumptions"))
            }
            fun copy(path: String?): String? = path?.let { File(it) }?.takeIf { it.exists() }?.let { src ->
                File(src.parentFile, UUID.randomUUID().toString() + "_r.jpg").also { src.copyTo(it) }.path
            }
            val id = Db.get(ctx).insertQuickMeal(at, Analysis.parse(json), json.toString(), "repeat", copy(meal.photoPath), copy(meal.thumbPath))
            if (id > 0) { HealthSync.writeMealIfEnabled(ctx, id); SleepLink.publishEnergy(ctx) }
            id
        }

    private fun background(block: suspend () -> Unit) {
        appScope.launch(Dispatchers.IO) { block() }
    }
}
