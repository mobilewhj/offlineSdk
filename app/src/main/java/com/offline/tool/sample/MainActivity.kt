package com.offline.tool.sample

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.offline.tool.OfflineInterceptor
import com.offline.tool.sample.databinding.ActMainBinding
import com.offline.tool.sample.ui.welcome.WelcomeActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
                val page = (application as DemoApplication).graph.pageAdapter
                    .acquireForPage(DemoGraph.BASE_URL, DemoGraph.BASE_URL)
                if (page == null) {
                    showUnavailable()
                    return@launch
                }
                // 该 WebView 仅绑定一次，后台升级不替换此目录。
                binding.wvMain.webViewClient = OfflineInterceptor(
                    page.directory, page.baseUrl, allowHttpAndHttps = true,
                    onResourceFailure = page.onResourceFailure,
                )
                binding.wvMain.loadUrl(DemoGraph.BASE_URL)
                binding.tvMainStatus.text = getString(R.string.ready, page.directory.name)
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
