package com.codex.rssreader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.codex.rssreader.data.ArticleEntity
import com.codex.rssreader.data.ReaderRepository
import com.codex.rssreader.rules.ArticleRules
import com.codex.rssreader.sync.RefreshTrigger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppViewModel(val repository: ReaderRepository) : ViewModel() {
    var refreshing by mutableStateOf(false)
        private set
    var unreadOnly by mutableStateOf(true)
        private set
    var listKeys by mutableStateOf<List<String>?>(null)
        private set
    var sourceRefreshVersion by mutableIntStateOf(0)
        private set
    fun onAppStart() = viewModelScope.launch {
        repository.clearExpired()
        // Automatic refresh reads cached content immediately and never drives UI progress.
        repository.refreshAll(RefreshTrigger.APP_START)
    }

    fun enterSource() {
        unreadOnly = true
        listKeys = null
    }

    fun selectUnreadFilter(value: Boolean) {
        if (unreadOnly != value) {
            unreadOnly = value
            listKeys = null
        }
    }

    fun initializeList(rows: List<ArticleEntity>) {
        val selected = rows.filter { !unreadOnly || it.readAt == null }.map { it.articleKey }
        if (listKeys == null || (listKeys!!.isEmpty() && selected.isNotEmpty())) {
            listKeys = selected
        }
    }

    fun resetList() { listKeys = null }

    fun refreshSource(id: Long) {
        if (refreshing) return
        viewModelScope.launch {
            refreshing = true
            try { repository.refreshSource(id) } finally {
                try {
                    val rows = repository.articles(id).first()
                    val selected = rows.filter { !unreadOnly || it.readAt == null }.map { it.articleKey }
                    listKeys = ArticleRules.appendNewKeys(listKeys, selected)
                    sourceRefreshVersion += 1
                } finally { refreshing = false }
            }
        }
    }

    fun refreshAll() {
        if (refreshing) return
        viewModelScope.launch {
            refreshing = true
            try { repository.refreshAll() } finally { refreshing = false }
        }
    }

    fun markRead(keys: List<String>) { viewModelScope.launch { repository.markRead(keys) } }

    class Factory(private val repository: ReaderRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(repository) as T
    }
}
