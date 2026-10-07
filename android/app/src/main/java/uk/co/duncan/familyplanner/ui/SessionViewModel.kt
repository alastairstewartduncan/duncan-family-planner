package uk.co.duncan.familyplanner.ui

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uk.co.duncan.familyplanner.auth.GoogleSignIn
import uk.co.duncan.familyplanner.data.Family
import uk.co.duncan.familyplanner.data.Repository

sealed interface Session {
    data object Loading : Session
    data object SignedOut : Session
    data class NeedsJoin(val email: String) : Session
    data class Ready(val family: Family, val personId: String?) : Session {
        val familyId get() = family.id
        val me get() = family.person(personId)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModel : ViewModel() {
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    val session: StateFlow<Session> = Repository.authState().flatMapLatest { user ->
        if (user == null) {
            flowOf(Session.SignedOut)
        } else {
            Repository.membership(user.uid).flatMapLatest { m ->
                if (m == null) {
                    flowOf(Session.NeedsJoin(user.email.orEmpty()))
                } else {
                    Repository.familyFlow(m.familyId).map { f -> f?.let { Session.Ready(it, m.personId) } ?: Session.Loading }
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Session.Loading)

    init {
        // Register the push token whenever we become a ready member, and
        // try joining automatically right after sign-in.
        viewModelScope.launch {
            var registeredFor: String? = null
            var autoJoinTried = false
            session.collect { s ->
                when (s) {
                    is Session.Ready -> if (registeredFor != s.familyId) {
                        registeredFor = s.familyId
                        Repository.registerPushToken()
                    }
                    is Session.NeedsJoin -> if (!autoJoinTried) { autoJoinTried = true; join() }
                    else -> Unit
                }
            }
        }
    }

    fun signIn(activity: Activity) = launchBusy {
        GoogleSignIn.signIn(activity)
    }

    fun join() = launchBusy {
        try {
            Repository.joinFamily()
        } catch (e: FirebaseFunctionsException) {
            throw IllegalStateException(e.message ?: "Couldn't join the family")
        }
    }

    fun signOut() = viewModelScope.launch {
        Repository.removePushToken()
        Repository.signOut()
    }

    fun clearError() { _error.value = null }

    private fun launchBusy(block: suspend () -> Unit) = viewModelScope.launch {
        _busy.value = true
        _error.value = null
        try {
            block()
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                _error.value = e.message ?: e.javaClass.simpleName
            }
        } finally {
            _busy.value = false
        }
    }
}
