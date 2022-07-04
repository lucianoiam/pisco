/*
 * Pisco - Plug-In Scan and Control
 * Copyright (C) 2022 Luciano Iam <oss@lucianoiam.com>
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
package com.github.lucianoiam.pisco

import android.annotation.SuppressLint
import android.net.nsd.NsdManager
import android.net.nsd.NsdManager.DiscoveryListener
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.ProgressBar
import android.widget.RelativeLayout
import androidx.appcompat.app.AppCompatActivity

// TODO:
//  - Allow to configure dpf-plugin-name expected value (currently ignored)
//  - Deal with multiple services
//       - Present a list of available services
//       - Skip list if only a single service is available
//       - Option "Auto Connect" for each entry
//       - Allow to access the list at anytime

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "pisco"
        private const val MDNS_SERVICE_TYPE = "_http._tcp."
    }

    private var mServiceUrl: String? = null
    private lateinit var mNsdManager: NsdManager
    private lateinit var mNsdDiscoveryListener: DiscoveryListener
    private lateinit var mWebView: WebView
    private lateinit var mProgress: ProgressBar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mNsdManager = getSystemService(NSD_SERVICE) as NsdManager
        mNsdDiscoveryListener = NsdDiscoveryListenerImpl()

        this.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        this.window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        mWebView = WebView(this)
        mWebView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        mWebView.visibility = View.INVISIBLE
        mWebView.setBackgroundResource(R.color.background)
        mWebView.isVerticalScrollBarEnabled = false
        mWebView.isHorizontalScrollBarEnabled = false
        mWebView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        mWebView.settings.javaScriptEnabled = true
        mWebView.webViewClient = WebViewClientImpl()
        WebView.setWebContentsDebuggingEnabled(true)

        val content = RelativeLayout(this)
        content.layoutParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT
        )
        content.setBackgroundResource(R.color.background)
        content.addView(mWebView)

        mProgress = ProgressBar(this, null, android.R.attr.progressBarStyleLarge)
        val rlp = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT
        )
        rlp.addRule(RelativeLayout.CENTER_IN_PARENT)
        mProgress.layoutParams = rlp
        content.addView(mProgress)

        setContentView(content)
    }

    public override fun onStart() {
        super.onStart()
        if (mServiceUrl == null) {
            startDiscovery()
        }
    }

    public override fun onStop() {
        super.onStop()
        stopDiscovery()
    }

    private fun startDiscovery() {
        mNsdManager.discoverServices(
            MDNS_SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            mNsdDiscoveryListener
        )
    }

    private fun stopDiscovery() {
        try {
            mNsdManager.stopServiceDiscovery(mNsdDiscoveryListener)
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
        }
    }

    @Synchronized
    private fun onServiceUrlReady(serviceUrl: String) {
        if (mServiceUrl == null) {
            mServiceUrl = serviceUrl
            stopDiscovery()
            runOnUiThread { mWebView.loadUrl(mServiceUrl!!) }
        }
    }

    private inner class NsdDiscoveryListenerImpl : DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            Log.d(TAG, "onDiscoveryStarted()")
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            Log.d(TAG, "onServiceFound() $service")
            if (service.serviceType == MDNS_SERVICE_TYPE) {
                mNsdManager.resolveService(service, NsdResolveListenerImpl())
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            Log.e(TAG, "onServiceLost()")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.i(TAG, "onDiscoveryStopped()")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "onStartDiscoveryFailed() errorCode=$errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "onStopDiscoveryFailed() errorCode=$errorCode")
        }
    }

    private inner class NsdResolveListenerImpl : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "onResolveFailed() errorCode=$errorCode")
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            Log.e(TAG, "onServiceResolved() serviceInfo=$serviceInfo")
            serviceInfo.attributes["dpfuri"]?.let {
                val uri = String(it)
                System.out.println("Found plugin with URI = $uri")
                serviceInfo.attributes["instanceid"]?.let {
                    // TODO: compare instance ID against favorite value
                    val instanceId = String(it)
                    System.out.println("Plugin instance ID = $instanceId")
                }
                val serviceUrl = "http:/" + serviceInfo.host + ":" + serviceInfo.port
                onServiceUrlReady(serviceUrl)
            }
        }
    }

    private inner class WebViewClientImpl : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            Log.d(TAG, "onPageFinished()")
            mProgress.visibility = View.GONE
            mWebView.visibility = View.VISIBLE
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            Log.d(TAG, "onReceivedError() error=$error")
            view.stopLoading()
        }
    }

}
