/*
 * Custom fork addition: built-in PDF viewer based on the framework
 * PdfRenderer (no external app needed). Pages are paged horizontally
 * with a SubsamplingScaleImageView per page, so pinch zoom and
 * double-tap zoom work like the image viewer.
 */

package me.zhanghai.android.files.home

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import java.io.File
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.util.layoutInflater

class PdfViewerActivity : AppActivity() {

    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var pageCount: Int = 0
    private val renderLock = Any()

    private lateinit var pageText: TextView
    private var pager: ViewPager2? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.install()

        val pathString = intent.getStringExtra(EXTRA_PATH)
        if (pathString.isNullOrEmpty()) {
            finish()
            return
        }
        setContentView(R.layout.pdf_viewer_activity)
        findViewById<View>(R.id.top_bar).applySystemBarInsetsPadding()
        findViewById<View>(R.id.back_button).setOnClickListener { finish() }
        findViewById<TextView>(R.id.title_text).text = File(pathString).name
        pageText = findViewById(R.id.page_text)
        val viewPager = findViewById<ViewPager2>(R.id.pager)
        pager = viewPager

        lifecycleScope.launch {
            val opened = withContext(Dispatchers.IO) { openRenderer(pathString) }
            val count = if (opened) {
                synchronized(renderLock) { renderer?.pageCount ?: 0 }
            } else {
                0
            }
            if (!opened || count <= 0) {
                Toast.makeText(
                    this@PdfViewerActivity, R.string.pdf_viewer_error_open,
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return@launch
            }
            pageCount = count
            viewPager.adapter = PageAdapter()
            viewPager.registerOnPageChangeCallback(
                object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        updatePageText(position)
                    }
                }
            )
            updatePageText(0)
        }
    }

    override fun onDestroy() {
        synchronized(renderLock) {
            try {
                renderer?.close()
            } catch (e: Exception) {
                // Already closed.
            }
            renderer = null
            try {
                descriptor?.close()
            } catch (e: Exception) {
                // Already closed.
            }
            descriptor = null
        }
        super.onDestroy()
    }

    private fun openRenderer(pathString: String): Boolean =
        try {
            synchronized(renderLock) {
                val file = File(pathString)
                descriptor = ParcelFileDescriptor.open(
                    file, ParcelFileDescriptor.MODE_READ_ONLY
                )
                renderer = PdfRenderer(descriptor!!)
            }
            true
        } catch (e: Exception) {
            false
        }

    private fun renderPage(index: Int, targetWidth: Int): Bitmap? {
        synchronized(renderLock) {
            val pdfRenderer = renderer ?: return null
            return try {
                pdfRenderer.openPage(index).use { page ->
                    val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                    val width = targetWidth
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(
                        width, height, Bitmap.Config.ARGB_8888
                    )
                    bitmap.eraseColor(Color.WHITE)
                    page.render(
                        bitmap, null, null,
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                    )
                    bitmap
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun updatePageText(position: Int) {
        pageText.text = getString(
            R.string.media_category_selected_format, position + 1, pageCount
        )
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = layoutInflater.inflate(
                R.layout.pdf_viewer_page, parent, false
            )
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.boundPage = position
            holder.image.recycle()
            val targetWidth = (
                resources.displayMetrics.widthPixels * RENDER_SCALE
                ).toInt().coerceAtMost(MAX_RENDER_WIDTH)
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    renderPage(position, targetWidth)
                }
                if (bitmap == null) {
                    return@launch
                }
                if (holder.boundPage == position && !isFinishing && !isDestroyed) {
                    holder.image.setImage(ImageSource.bitmap(bitmap))
                } else {
                    // The page view was recycled while rendering; the
                    // bitmap was never handed to the image view.
                    bitmap.recycle()
                }
            }
        }

        override fun onViewRecycled(holder: Holder) {
            holder.boundPage = -1
            holder.image.recycle()
        }

        override fun getItemCount(): Int = pageCount

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val image: SubsamplingScaleImageView =
                itemView.findViewById(R.id.page_image)
            var boundPage: Int = -1
        }
    }

    companion object {
        private val EXTRA_PATH = "${PdfViewerActivity::class.java.name}.extra.PATH"

        // Render pages at twice the screen width for readable pinch
        // zoom, capped to bound memory use.
        private const val RENDER_SCALE = 2f
        private const val MAX_RENDER_WIDTH = 2400

        fun createIntent(context: Context, path: Path): Intent =
            Intent(context, PdfViewerActivity::class.java).apply {
                putExtra(EXTRA_PATH, path.toString())
            }
    }
}
