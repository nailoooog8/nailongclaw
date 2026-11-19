// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import io.agents.nailongclaw.R
import io.agents.nailongclaw.base.BaseActivity
import io.agents.nailongclaw.utils.XLog
import io.agents.nailongclaw.widget.CommonToolbar

/**
 * Web screen - general-purpose browser
 *
 * ⚠️ **This activity has no callers** (`WebActivity.start` has zero call sites
 * in the repo). It enables JavaScript and loads any URL it is handed, so it is
 * kept alive by [WebUrlPolicy] rather than removed: if a future change wires
 * it up, that change must not silently activate a screen that can be pointed
 * at `intent:`/`javascript:`/`file:` URLs. Tracked as BACKLOG P3 · F10.
 */
class WebActivity : BaseActivity() {

    private lateinit var toolbar: CommonToolbar
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    companion object {
        private const val TAG = "WebActivity"
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_TITLE = "extra_title"

        /**
         * Open a web page
         * @param context context
         * @param url page URL
         * @param title default title (optional; if not provided, the page title is shown)
         */
        fun start(context: Context, url: String, title: String? = null) {
            val intent = Intent(context, WebActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
            }
            context.startActivity(intent)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web)

        val loadUrl = intent.getStringExtra(EXTRA_URL)
            ?.takeIf { WebUrlPolicy.isSupported(it) }
            ?.trim()
        if (loadUrl == null) {
            // Fail closed: a refused URL finishes the activity instead of
            // falling through to a WebView that would load it anyway.
            XLog.e(TAG, "Refusing to open unsupported URL")
            finish()
            return
        }

        val defaultTitle = intent.getStringExtra(EXTRA_TITLE)

        initToolbar(defaultTitle)
        initProgressBar()
        initWebView(loadUrl)
    }

    private fun initToolbar(defaultTitle: String?) {
        toolbar = findViewById(R.id.toolbar)
        toolbar.apply {
            // Set default title if provided
            if (!defaultTitle.isNullOrEmpty()) {
                setTitle(defaultTitle)
            }
            // Show back button
            showBackButton(true) { finish() }
        }
    }

    private fun initProgressBar() {
        progressBar = findViewById(R.id.progressBar)
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView(url: String) {
        webView = findViewById(R.id.webView)
        webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    // The same gate as onCreate, applied to in-page navigations:
                    // a loaded page can redirect to any scheme, and only
                    // http/https is let through. Refused schemes are swallowed
                    // (return true without loading) rather than handed to the
                    // system, so a page cannot bounce the user into another
                    // app's intent handler.
                    val target = url?.takeIf { WebUrlPolicy.isSupported(it) }
                    if (target == null) {
                        XLog.e(TAG, "Blocked navigation to unsupported URL")
                        return true
                    }
                    view?.loadUrl(target.trim())
                    return true
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    // Reflect the page title once the page has loaded
                    if (!title.isNullOrEmpty()) {
                        toolbar.setTitle(title)
                    }
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    progressBar.progress = newProgress
                    // Hide progress bar when loading completes
                    if (newProgress >= 100) {
                        progressBar.visibility = View.GONE
                    } else {
                        progressBar.visibility = View.VISIBLE
                    }
                }
            }

            loadUrl(url)
        }
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }
}
