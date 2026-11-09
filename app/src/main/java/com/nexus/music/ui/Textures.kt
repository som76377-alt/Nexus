package com.nexus.music.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Small, deliberately drawn material overlays. Each screen chooses its own stock:
 * paper fibres, woven linen, book paper, or matte record sleeve. No interpolated noise.
 */
object PaperTexture {
    enum class Material(val fileName: String, val seed: Int) {
        HOME_PAPER("home-paper", 0x4E585531),
        ROOM_LINEN("room-linen", 0x4E585532),
        LIBRARY_STOCK("library-stock", 0x4E585533),
        PLAYER_SLEEVE("player-sleeve", 0x4E585534)
    }

    private const val TILE_SIZE = 512
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nexus-material-textures").apply { isDaemon = true }
    }
    private val cached = ConcurrentHashMap<Material, Bitmap>()

    fun prepare(
        context: Context,
        material: Material = Material.HOME_PAPER,
        onReady: (Drawable?) -> Unit = {}
    ) {
        cached[material]?.let { bitmap ->
            onReady(drawable(context, bitmap))
            return
        }
        executor.execute {
            val file = File(context.cacheDir, "nexus_\${material.fileName}_v2.png")
            val bitmap = runCatching {
                (if (file.exists()) BitmapFactoryCompat.decode(file) else null)
                    ?: createTile(material).also { tile ->
                        runCatching {
                            FileOutputStream(file).use { output ->
                                tile.compress(Bitmap.CompressFormat.PNG, 100, output)
                            }
                        }
                    }
            }.getOrNull()
            if (bitmap != null) cached[material] = bitmap
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

    private fun createTile(material: Material): Bitmap {
        val bitmap = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val random = Random(material.seed)
        val fibre = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fleck = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        fun drawFibres(count: Int, minLength: Float, maxLength: Float, maxWidth: Float, maxAlpha: Int) {
            repeat(count) {
                val x = random.nextFloat() * TILE_SIZE
                val y = random.nextFloat() * TILE_SIZE
                val length = minLength + random.nextFloat() * (maxLength - minLength)
                val angle = (random.nextFloat() - .5f) * .7f
                val bend = (random.nextFloat() - .5f) * 2.5f
                fibre.strokeWidth = .25f + random.nextFloat() * maxWidth
                fibre.color = android.graphics.Color.argb(
                    4 + random.nextInt((maxAlpha - 3).coerceAtLeast(1)),
                    93 + random.nextInt(35),
                    70 + random.nextInt(30),
                    48 + random.nextInt(25)
                )
                val dx = kotlin.math.cos(angle) * length
                val dy = kotlin.math.sin(angle) * length
                val path = Path().apply {
                    moveTo(x, y)
                    quadTo(x + dx * .52f, y + dy * .52f + bend, x + dx, y + dy)
                }
                canvas.drawPath(path, fibre)
            }
        }

        when (material) {
            Material.HOME_PAPER -> {
                // Long, sparse cellulose strands and a few darker inclusions.
                drawFibres(330, 3f, 26f, .65f, 15)
                repeat(76) {
                    fleck.color = android.graphics.Color.argb(7 + random.nextInt(10), 105, 77, 51)
                    canvas.drawCircle(
                        random.nextFloat() * TILE_SIZE,
                        random.nextFloat() * TILE_SIZE,
                        .35f + random.nextFloat() * .75f,
                        fleck
                    )
                }
            }
            Material.ROOM_LINEN -> {
                // Broken warp-and-weft threads, visible only when the screen is examined closely.
                val thread = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = .55f
                    color = android.graphics.Color.argb(9, 91, 76, 59)
                }
                for (x in 0..TILE_SIZE step 9) {
                    var y = 0f
                    while (y < TILE_SIZE) {
                        val end = (y + 12f + random.nextFloat() * 8f).coerceAtMost(TILE_SIZE.toFloat())
                        canvas.drawLine(x + (random.nextFloat() - .5f) * 1.4f, y,
                            x + (random.nextFloat() - .5f) * 1.4f, end, thread)
                        y = end + 5f + random.nextFloat() * 7f
                    }
                }
                for (y in 0..TILE_SIZE step 9) {
                    var x = 0f
                    while (x < TILE_SIZE) {
                        val end = (x + 12f + random.nextFloat() * 8f).coerceAtMost(TILE_SIZE.toFloat())
                        canvas.drawLine(x, y + (random.nextFloat() - .5f) * 1.4f,
                            end, y + (random.nextFloat() - .5f) * 1.4f, thread)
                        x = end + 5f + random.nextFloat() * 7f
                    }
                }
                drawFibres(115, 3f, 15f, .4f, 10)
            }
            Material.LIBRARY_STOCK -> {
                // Soft offset-print stipple with occasional deckle-like short marks.
                repeat(220) {
                    val x = random.nextFloat() * TILE_SIZE
                    val y = random.nextFloat() * TILE_SIZE
                    fleck.color = android.graphics.Color.argb(5 + random.nextInt(12), 98, 79, 59)
                    canvas.drawCircle(x, y, .35f + random.nextFloat() * 1.1f, fleck)
                }
                drawFibres(210, 2f, 18f, .55f, 13)
                val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.argb(5, 105, 82, 59)
                    strokeWidth = .6f
                }
                for (y in 48..TILE_SIZE step 128) {
                    canvas.drawLine(0f, y.toFloat(), TILE_SIZE.toFloat(), y.toFloat(), rule)
                }
            }
            Material.PLAYER_SLEEVE -> {
                // Broad, quiet fibres like uncoated album-jacket stock.
                drawFibres(190, 8f, 42f, .85f, 10)
                val crease = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = .7f
                    color = android.graphics.Color.argb(6, 245, 233, 211)
                }
                for (i in 0..7) {
                    val y = 32f + i * 68f
                    canvas.drawLine(0f, y, TILE_SIZE.toFloat(), y + (i % 2) * 2f, crease)
                }
            }
        }
        return bitmap
    }

    private object BitmapFactoryCompat {
        fun decode(file: File): Bitmap? =
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
    }
}
