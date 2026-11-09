package com.nexus.music.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.BitmapShader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.random.Random

/** Procedural paper tile, generated once off-main-thread and cached as paper_v1.png. */
object PaperTexture {
    private const val FILE_NAME = "paper_v1.png"
    private const val TILE_SIZE = 256
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nexus-paper-texture").apply { isDaemon = true }
    }

    @Volatile private var cached: Bitmap? = null

    fun prepare(context: Context, onReady: (Drawable?) -> Unit = {}) {
        cached?.let { bitmap ->
            onReady(drawable(context, bitmap))
            return
        }
        executor.execute {
            val file = File(context.cacheDir, FILE_NAME)
            val bitmap = runCatching {
                if (file.exists()) {
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                } else {
                    createTile().also { tile ->
                        runCatching {
                            FileOutputStream(file).use { output ->
                                tile.compress(Bitmap.CompressFormat.PNG, 100, output)
                            }
                        }
                    }
                }
            }.getOrNull()
            if (bitmap != null) cached = bitmap
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onReady(bitmap?.let { drawable(context, it) })
            }
        }
    }

    fun drawable(context: Context, bitmap: Bitmap): Drawable =
        BitmapDrawable(context.resources, bitmap).apply {
            tileModeX = Shader.TileMode.REPEAT
            tileModeY = Shader.TileMode.REPEAT
            alpha = 255
        }

    private fun createTile(): Bitmap {
        val bitmap = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val random = Random(0x4E455855)
        val gridSize = 17
        val values = Array(gridSize) { IntArray(gridSize) { random.nextInt(0, 21) } }
        val pixels = IntArray(TILE_SIZE * TILE_SIZE)
        for (y in 0 until TILE_SIZE) {
            val gy = y * (gridSize - 1) / TILE_SIZE.toFloat()
            val y0 = gy.toInt().coerceIn(0, gridSize - 2)
            val fy = gy - y0
            for (x in 0 until TILE_SIZE) {
                val gx = x * (gridSize - 1) / TILE_SIZE.toFloat()
                val x0 = gx.toInt().coerceIn(0, gridSize - 2)
                val fx = gx - x0
                val top = values[y0][x0] * (1f - fx) + values[y0][x0 + 1] * fx
                val bottom = values[y0 + 1][x0] * (1f - fx) + values[y0 + 1][x0 + 1] * fx
                val alpha = (top * (1f - fy) + bottom * fy).toInt().coerceIn(0, 20)
                pixels[y * TILE_SIZE + x] =
                    android.graphics.Color.argb(alpha, 112, 91, 67)
            }
        }
        bitmap.setPixels(pixels, 0, TILE_SIZE, 0, 0, TILE_SIZE, TILE_SIZE)
        val fibre = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        repeat(140) {
            val x = random.nextFloat() * TILE_SIZE
            val y = random.nextFloat() * TILE_SIZE
            val length = random.nextFloat() * 16f + 6f
            val bend = random.nextFloat() * 3f - 1.5f
            fibre.strokeWidth = random.nextFloat() * .65f + .25f
            fibre.color = android.graphics.Color.argb(random.nextInt(5, 10), 111, 87, 59)
            val path = Path().apply {
                moveTo(x, y)
                quadTo(x + length / 2f, y + bend, x + length, y + bend * .4f)
            }
            canvas.drawPath(path, fibre)
        }
        return bitmap
    }
}
