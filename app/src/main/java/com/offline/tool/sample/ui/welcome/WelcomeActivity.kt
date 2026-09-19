package com.offline.tool.sample.ui.welcome

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.offline.tool.sample.DemoApplication
import com.offline.tool.sample.MainActivity
import com.offline.tool.sample.R
import com.offline.tool.sample.applySystemBarInsets
import com.offline.tool.sample.databinding.ActWelcomeBinding
import com.offline.tool.sample.offline.LocalPreparationProgress.Stage
import kotlinx.coroutines.launch

class WelcomeActivity : ComponentActivity() {
    private val viewModel: WelcomeViewModel by viewModels {
        viewModelFactory { initializer { (application as DemoApplication).graph.welcomeViewModel() } }
    }
    private lateinit var binding: ActWelcomeBinding
    private var navigating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        binding.btnWelcomeRetry.setOnClickListener { viewModel.prepare() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    private fun render(state: WelcomeUiState) = with(binding) {
        btnWelcomeRetry.isVisible = state is WelcomeUiState.Failed
        pbWelcomeOffline.isVisible = state is WelcomeUiState.Preparing
        tvWelcomeOfflinePercent.isVisible = state is WelcomeUiState.Preparing && state.progress.percent != null
        when (state) {
            WelcomeUiState.Idle -> Unit
            is WelcomeUiState.Preparing -> {
                tvWelcomeOfflineStatus.setText(
                    when (state.progress.stage) {
                        Stage.CHECKING -> R.string.checking
                        Stage.DOWNLOADING -> R.string.downloading
                        Stage.EXTRACTING -> R.string.extracting
                        Stage.SAVING -> R.string.saving
                    }
                )
                pbWelcomeOffline.isIndeterminate = state.progress.percent == null
                pbWelcomeOffline.progress = state.progress.percent ?: 0
                tvWelcomeOfflinePercent.text = getString(R.string.percent, state.progress.percent ?: 0)
            }

            is WelcomeUiState.Failed -> tvWelcomeOfflineStatus.text = getString(R.string.failed, state.reason)
            WelcomeUiState.Ready -> if (!navigating) {
                navigating = true
                startActivity(Intent(this@WelcomeActivity, MainActivity::class.java))
                finish()
            }
        }
    }
}
