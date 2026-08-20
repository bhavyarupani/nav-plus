package com.navplus.feature.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.navplus.core.group.ConvoyEngine
import com.navplus.core.group.GroupSyncService
import com.navplus.core.group.model.GroupSession
import com.navplus.core.group.model.StopOptionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class GroupViewModel @Inject constructor(
    private val syncService: GroupSyncService,
    private val convoyEngine: ConvoyEngine,
) : ViewModel() {

    val session = syncService.session.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun createSession(name: String, color: String): String = syncService.createSession(name, color)

    fun joinSession(code: String, name: String, color: String) = syncService.joinSession(code, name, color)

    fun proposeStop(options: List<StopOptionType>) = syncService.proposeStop(options)

    fun castVote(proposalId: String, choice: StopOptionType) = syncService.castVote(proposalId, choice)

    fun closeVoting() = syncService.closeVoting()

    fun leaveGroup() = syncService.disconnect()

    override fun onCleared() {
        super.onCleared()
        syncService.disconnect()
    }
}
