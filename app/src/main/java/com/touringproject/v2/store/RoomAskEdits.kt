package com.touringproject.v2.store

import com.touringproject.v2.records.AskEdits
import com.touringproject.v2.records.memoFits
import kotlinx.coroutines.CancellationException

/**
 * Changes kept asks in Room. A change that was written is something new to send (changed). A failed write is logged
 * and reported as false, so the screen can say so and keep what was typed (slice 3 spec §8).
 */
class RoomAskEdits(
  private val dao: AskDao,
  private val now: () -> Long,
  private val changed: () -> Unit,
  private val log: (String, Throwable) -> Unit,
) : AskEdits {
  override suspend fun memo(askId: String, memo: String?): Boolean =
    memoFits(memo) && attempt("ask_memo_failed askId=$askId") { dao.writeMemo(askId, memo, now()) }

  override suspend fun delete(askId: String): Boolean = attempt("ask_delete_failed askId=$askId") { dao.delete(askId, now()) }

  private suspend fun attempt(failure: String, write: suspend () -> Boolean): Boolean {
    val written = try {
      write()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log(failure, e)
      false
    }
    if (written) changed()
    return written
  }
}
