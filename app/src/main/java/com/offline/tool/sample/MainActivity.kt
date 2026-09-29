package com.offline.tool.sample

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.offline.tool.ManagedPageCallbacks
import com.offline.tool.OfflineInterceptor
import com.offline.tool.PageDecision
import com.offline.tool.sample.databinding.ActMainBinding
import com.offline.tool.sample.offline.logOffline
import com.offline.tool.sample.ui.welcome.WelcomeActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var binding: ActMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        binding.btnMainRetry.setOnClickListener {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
        }
        lifecycleScope.launch {
            try {
                val manager = (application as DemoApplication).manager
                val decision = manager.loadPage(
                    url = DemoApplication.BASE_URL,
                    baseUrl = DemoApplication.BASE_URL,
                    callbacks = object : ManagedPageCallbacks {
                        override fun clearResourceCache(): Boolean = try {
                            // 资源缓存包含磁盘；不触碰 Cookie、localStorage 或业务数据。
                            binding.wvMain.clearCache(true)
                            true
                        } catch (_: RuntimeException) {
                            false
                        }

                        override fun loadOffline(directory: File, interceptor: OfflineInterceptor, url: String) {
                            binding.wvMain.webViewClient = interceptor
                            binding.wvMain.loadUrl(url)
                        }

                        override fun loadOnline(url: String) {
                            binding.wvMain.webViewClient = WebViewClient()
                            binding.wvMain.loadUrl(url)
                        }
                    },
                    allowHttpAndHttps = true,
                    onResourceFailure = { reason, path -> logOffline("resource=$reason path=$path") },
                )
                binding.tvMainStatus.text = when (decision) {
                    is PageDecision.Offline -> getString(R.string.ready, decision.record.version.toString())
                    PageDecision.Online -> getString(R.string.online)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showUnavailable()
            }
        }
    }

    private fun showUnavailable() {
        binding.tvMainStatus.setText(R.string.page_unavailable)
        binding.btnMainRetry.isVisible = true
    }

    override fun onResume() {
        super.onResume()
        binding.wvMain.onResume()
    }

    override fun onPause() {
        binding.wvMain.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        binding.wvMain.apply {
            stopLoading()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        super.onDestroy()
    }
}
