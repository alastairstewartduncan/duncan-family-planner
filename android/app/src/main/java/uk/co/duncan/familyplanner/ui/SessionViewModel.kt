package uk.co.duncan.familyplanner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uk.co.duncan.familyplanner.data.Family
import uk.co.duncan.familyplanner.data.Person
import uk.co.duncan.familyplanner.data.Repository

sealed interface Session {
    data object Loading : Session
    data object SignedOut : Session
    data class Ready(val family: Family, val personId: String?, val serverUrl: String) : Session {
        val me get() = family.person(personId)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModel : ViewModel() {
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** Family members offered on the sign-in screen once the server answers. */
    private val _people = MutableStateFlow<List<Person>?>(null)
    val people: StateFlow<List<Person>?> = _people

    val session: StateFlow<Session> = Repository.credentials.flatMapLatest { c ->
        if (c == null) flowOf(Session.SignedOut)
        else Repository.familyFlow()
            .map<Family?, Session> { f -> f?.let { Session.Ready(it, c.personId, c.serverUrl) } ?: Session.Loading }
            .onStart { emit(Session.Loading) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Session.Loading)

    val lastServerUrl: String get() = Repository.lastServerUrl

    fun connect(serverUrl: String) = launchBusy {
        _people.value = null
        val people = Repository.people(serverUrl)
        if (people.isEmpty()) error("The server is running but the family isn't set up yet (run npm run setup on the PC).")
        _people.value = people
    }

    fun signIn(serverUrl: String, personId: String, pin: String) = launchBusy {
        Repository.signIn(serverUrl, personId, pin)
    }

    fun signOut() = viewModelScope.launch {
        Repository.signOut()
        _people.value = null
    }

    fun changeServer() { _people.value = null; _error.value = null }

    fun clearError() { _error.value = null }

    private fun launchBusy(block: suspend () -> Unit) = viewModelScope.launch {
        _busy.value = true
        _error.value = null
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.net.ConnectException) {
            _error.value = "Can't reach the server. Is the PC on, and is Tailscale connected on this phone?"
        } catch (e: java.net.SocketTimeoutException) {
            _error.value = "The server didn't answer in time. Is Tailscale connected?"
        } catch (e: java.net.UnknownHostException) {
            _error.value = "Can't find that server address. Check it, and that Tailscale is connected."
        } catch (e: Exception) {
            _error.value = e.message ?: e.javaClass.simpleName
        } finally {
            _busy.value = false
        }
    }
}
