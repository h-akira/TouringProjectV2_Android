package com.touringproject.v2.ask

// Asking by text from Home (slice 5 spec §5, US-A-08).

/** The sheet takes up to 500 characters, counted as code points like the other texts the Backend takes. */
const val TEXT_ASK_MAX = 500

private fun String.characters() = codePointCount(0, length)

/** What the sheet's field keeps of a typed or pasted text: its first 500 characters, never cutting an emoji in half. */
fun cutTextAsk(input: String): String =
  if (input.characters() <= TEXT_ASK_MAX) input else input.substring(0, input.offsetByCodePoints(0, TEXT_ASK_MAX))

/** How many more characters the field takes. */
fun textAskLeft(input: String): Int = TEXT_ASK_MAX - input.characters()

/** The question sent: trimmed, or null when it is only blanks (「質問する」 cannot be pressed then). */
fun textQuestion(input: String): String? = input.trim().takeIf { it.isNotEmpty() && it.characters() <= TEXT_ASK_MAX }

/**
 * What became of a text ask, for the sheet. SENT: the Backend took it, so the sheet closes and SC-03 waits for the
 * answer. OFFLINE: it could not reach the Backend; the sheet stays with the text and says so (no unreached ask is
 * kept). NOT_TAKEN: refused or let go before it was taken (SC-03 has said why); the sheet stays with the text.
 */
enum class TextAskOutcome { SENT, OFFLINE, NOT_TAKEN }

const val TEXT_ASK_OFFLINE_MESSAGE = "通信できません"

/**
 * Home's text sheet (slice 5 spec §5.2): whether it is open, what is typed, what it says, and whether a send is under
 * way. AppGraph holds it for the process, so the text stays while SC-03 covers the screen and the sheet comes back with
 * it, and an outcome after the activity was recreated still reaches it.
 */
data class TextSheet(val open: Boolean = false, val text: String = "", val message: String? = null, val sending: Boolean = false)

/** What the sheet becomes once the ask loop has told what became of the text (TextAskOutcome). */
fun TextSheet.after(outcome: TextAskOutcome): TextSheet = when (outcome) {
  TextAskOutcome.SENT -> TextSheet()
  TextAskOutcome.OFFLINE -> copy(sending = false, message = TEXT_ASK_OFFLINE_MESSAGE)
  TextAskOutcome.NOT_TAKEN -> copy(sending = false, message = null)
}
