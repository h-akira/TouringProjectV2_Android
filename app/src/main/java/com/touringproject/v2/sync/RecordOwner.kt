package com.touringproject.v2.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whose records the device keeps (adr/0015). */
interface OwnerStore {
  /** The user name the records are kept under; null before any was taken. */
  suspend fun owner(): String?

  /** Takes userName as the owner. */
  suspend fun adopt(userName: String)
}

/** MATCH is the only state in which records are sent. UNKNOWN is before it could be told. */
enum class OwnerState { UNKNOWN, MATCH, MISMATCH, UNCONFIRMED }

/**
 * Tells whether the records may be sent under the user name of the settings: only when that name is the owner's
 * (adr/0015). A pass of the sync asks first, and what it finds is what SC-14 and SC-18 tell. A device with no owner yet
 * takes the name of the settings, unless that name is made up (the settings could not be read).
 */
class RecordOwner(
  private val store: OwnerStore,
  /** The user name of the settings, or null before they are loaded. */
  private val name: () -> String?,
  /** False while the name is made up because the settings could not be read. */
  private val confirmed: () -> Boolean,
  private val log: (String, Throwable) -> Unit,
) {
  private val now = MutableStateFlow(OwnerState.UNKNOWN)
  val state: StateFlow<OwnerState> = now.asStateFlow()

  private val ownerNow = MutableStateFlow<String?>(null)

  /** The owner's user name as the last check found it, for the rider to choose again in SC-18; null when unknown or none. */
  val ownerName: StateFlow<String?> = ownerNow.asStateFlow()

  suspend fun check(): Boolean {
    val name = name()
    var found: String? = null
    val result = when {
      name == null -> OwnerState.UNKNOWN
      !confirmed() -> OwnerState.UNCONFIRMED
      else -> try {
        val owner = store.owner()
        when {
          owner == null -> { store.adopt(name); found = name; OwnerState.MATCH }
          owner == name -> { found = owner; OwnerState.MATCH }
          else -> { found = owner; OwnerState.MISMATCH }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log("owner check failed", e)
        OwnerState.UNKNOWN
      }
    }
    ownerNow.value = found
    now.value = result
    return result == OwnerState.MATCH
  }
}
