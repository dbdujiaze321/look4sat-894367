/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.presentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/**
 * Stores one user supplied radar icon.
 *
 * The image is copied into internal storage and re-encoded as a small PNG instead of keeping the
 * picked URI: a URI grant can be revoked, the file can be moved, and decoding a 12 MP photo on
 * every radar frame would be worse than useless at 26 dp.
 *
 * Decoding is bounded twice — by byte size before parsing and by pixel size after the bounds pass —
 * so a zip bomb or a huge panorama cannot take the radar screen down with it.
 */
object CustomRadarIcon {

    /** Longest side of the stored icon, in pixels. Enough for a 4x density 32 dp marker. */
    const val MAX_DIMENSION = 256

    /** Anything larger than this is rejected before decoding. */
    const val MAX_SOURCE_BYTES = 4 * 1024 * 1024

    /** Sanity bound on the decoded source, checked from the header only. */
    const val MAX_SOURCE_DIMENSION = 4096

    private const val DIR = "radar-icon"
    private const val NAME = "icon.png"

    fun file(context: Context): File = File(File(context.filesDir, DIR), NAME)

    fun exists(context: Context): Boolean = file(context).isFile

    fun clear(context: Context) {
        file(context).delete()
    }

    /** Copies, downscales and stores [uri]. Returns false when the image is unusable. */
    fun save(context: Context, uri: Uri): Boolean {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } ?: return false
        if (bytes.size > MAX_SOURCE_BYTES) return false

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        if (bounds.outWidth > MAX_SOURCE_DIMENSION || bounds.outHeight > MAX_SOURCE_DIMENSION) return false

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val decoded = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (_: OutOfMemoryError) {
            null
        } ?: return false

        val scaled = scaleToFit(decoded)
        return try {
            val target = file(context)
            target.parentFile?.mkdirs()
            target.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        } catch (_: Exception) {
            false
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    fun load(context: Context): ImageBitmap? = try {
        val source = file(context)
        if (source.isFile) BitmapFactory.decodeFile(source.absolutePath)?.asImageBitmap() else null
    } catch (_: Exception) {
        null
    }

    /** Power-of-two sample factor so the decoded bitmap never exceeds twice [MAX_DIMENSION]. */
    internal fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= MAX_DIMENSION && height / (sample * 2) >= MAX_DIMENSION) {
            sample *= 2
        }
        return sample
    }

    private fun scaleToFit(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= MAX_DIMENSION) return source
        val scale = MAX_DIMENSION.toFloat() / longest
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }
}
