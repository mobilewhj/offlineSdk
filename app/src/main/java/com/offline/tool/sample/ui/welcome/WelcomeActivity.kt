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
import kotlinx.coroutines.launch

class WelcomeActivity : ComponentActivity() {
    private val viewModel: WelcomeViewModel by viewModels {
        viewModelFactory { initializer {
            WelcomeViewModel((application as DemoApplication).manager)
        } }
    }
    private lateinit var binding: ActWelcomeBinding
    private var navigating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        binding.btnWelcomeRetry.setOnClickListener { viewModel.retry() }
        binding.btnWelcomeReturn.setOnClickListener { finish() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.prepare()
    }

    private fun render(state: WelcomeUiState) = with(binding) {
        btnWelcomeRetry.isVisible = state is WelcomeUiState.Failed || state == WelcomeUiState.Interrupted
        btnWelcomeReturn.isVisible = state == WelcomeUiState.Interrupted
        pbWelcomeOffline.isVisible = state is WelcomeUiState.Preparing
        val presentation = (state as? WelcomeUiState.Preparing)?.let(::presentProgress)
        val percent = presentation?.percent
        tvWelcomeOfflinePercent.isVisible = percent != null
        when (state) {
            WelcomeUiState.Idle -> tvWelcomeOfflineStatus.setText(R.string.starting)
            WelcomeUiState.Interrupted -> tvWelcomeOfflineStatus.setText(R.string.preparation_interrupted)
            is WelcomeUiState.Preparing -> {
                tvWelcomeOfflineStatus.setText(checkNotNull(presentation).statusResource)
                pbWelcomeOffline.isIndeterminate = percent == null
                if (percent != null) {
                    pbWelcomeOffline.progress = percent
                    tvWelcomeOfflinePercent.text = getString(R.string.percent, percent)
                }
            }

            is WelcomeUiState.Failed -> tvWelcomeOfflineStatus.setText(when (state.reason) {
                WelcomeFailure.PRIVACY_REQUIRED -> R.string.privacy_required
                WelcomeFailure.LOCAL_PREPARATION -> R.string.local_preparation_failed
            })
            WelcomeUiState.Ready -> if (!navigating) {
                navigating = true
                startActivity(Intent(this@WelcomeActivity, MainActivity::class.java))
                finish()
            }
        }
    }
}
