package com.vaila.launcher

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Renders a page of the local site off-screen and returns a JPEG (phone-width layout). */
class SiteShot(private val act: Activity) {

    fun capture(path: String): ByteArray? {
        val latch = CountDownLatch(1)
        var result: ByteArray? = null
        val d = act.resources.displayMetrics.density
        val w = (412 * d).toInt()
        val h0 = (900 * d).toInt()
        act.runOnUiThread {
            val wv = WebView(act)
            var done = false
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.setBackgroundColor(Color.WHITE)
            wv.measure(spec(w), spec(h0))
            wv.layout(0, 0, w, h0)
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (done) return
                    done = true
                    view.postDelayed({
                        try {
                            val h = (view.contentHeight * d).toInt().coerceIn(h0, w * 5)
                            view.measure(spec(w), spec(h))
                            view.layout(0, 0, w, h)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            val c = Canvas(bmp)
                            c.drawColor(Color.WHITE)
                            view.draw(c)
                            val outW = 600
                            val scaled = Bitmap.createScaledBitmap(bmp, outW, (h.toLong() * outW / w).toInt(), true)
                            val out = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                            result = out.toByteArray()
                        } catch (_: Throwable) {
                        } finally {
                            view.destroy()
                            latch.countDown()
                        }
                    }, 1500)
                }
            }
            wv.loadUrl("http://127.0.0.1:${Stack.HTTP_PORT}/" + path.trimStart('/'))
        }
        latch.await(40, TimeUnit.SECONDS)
        return result
    }

    private fun spec(v: Int) = View.MeasureSpec.makeMeasureSpec(v, View.MeasureSpec.EXACTLY)
}
