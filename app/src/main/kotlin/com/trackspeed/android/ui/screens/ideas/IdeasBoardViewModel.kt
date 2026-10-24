package com.trackspeed.android.ui.screens.ideas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.cloud.ideas.SupabaseIdeasBoardService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class IdeasBoardViewModel @Inject constructor(service: SupabaseIdeasBoardService) : ViewModel() {
    internal val store = IdeasBoardStore(viewModelScope, service.api)
}
