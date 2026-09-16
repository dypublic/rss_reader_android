package com.codex.rssreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.codex.rssreader.data.ReaderRepository
import com.codex.rssreader.ui.ArticleListScreen
import com.codex.rssreader.ui.ReaderScreen
import com.codex.rssreader.ui.ReaderTheme
import com.codex.rssreader.ui.SettingsScreen
import com.codex.rssreader.ui.SubscriptionScreen

class MainActivity : ComponentActivity() {
    private val repository by lazy { ReaderRepository(applicationContext) }
    private val appViewModel by viewModels<AppViewModel> { AppViewModel.Factory(repository) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContent {
            ReaderTheme {
                var sourceId by rememberSaveable { mutableStateOf<Long?>(null) }
                var articleKey by rememberSaveable { mutableStateOf<String?>(null) }
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                val holder = rememberSaveableStateHolder()

                BackHandler(articleKey != null || sourceId != null || settingsOpen) {
                    when {
                        articleKey != null -> articleKey = null
                        settingsOpen -> settingsOpen = false
                        else -> sourceId = null
                    }
                }

                val rootModifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                when {
                    articleKey != null -> ReaderScreen(
                        articleKey = articleKey!!,
                        repository = repository,
                        onBack = { articleKey = null },
                        modifier = rootModifier,
                    )
                    settingsOpen -> SettingsScreen(repository, onBack = { settingsOpen = false }, modifier = rootModifier)
                    sourceId != null -> holder.SaveableStateProvider("articles:$sourceId") {
                        ArticleListScreen(
                            sourceId = sourceId!!,
                            repository = repository,
                            viewModel = appViewModel,
                            onBack = { sourceId = null },
                            onOpen = { key -> appViewModel.markRead(listOf(key)); articleKey = key },
                            modifier = rootModifier,
                        )
                    }
                    else -> SubscriptionScreen(
                        repository = repository,
                        viewModel = appViewModel,
                        onOpenSource = { id ->
                            holder.removeState("articles:$id")
                            appViewModel.enterSource()
                            sourceId = id
                        },
                        onSettings = { settingsOpen = true },
                        modifier = rootModifier,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        appViewModel.onAppStart()
    }
}
