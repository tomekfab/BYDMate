package com.bydmate.app.voice.openclaw

import com.bydmate.app.agent.ActionRejection

/**
 * Polish lines the driver hears after an assistant action. Kept in code rather than Android resources,
 * like [PolishAssistantText]. Built only from local text (registry labels, the dispatcher's own gate
 * reason); assistant prose never appears here.
 */
object PolishActionText {
    const val FAILED = "Nie udało się wykonać polecenia."
    const val DECLINED = "Anulowano."
    const val TIMED_OUT = "Nie potwierdzono, więc anulowałem polecenie."
    const val NO_OVERLAY = "Potrzebuję zgody na wyświetlanie nakładki, żeby to potwierdzić."
    const val REJECTED = "Nie mogę tego wykonać."
    const val RECOVERED = "Połączenie zostało przerwane, nie wykonałem polecenia. Powtórz."
    const val EXPIRED = "Odpowiedź asystenta przyszła za późno, nie wykonałem polecenia."
    const val ALIAS_UNAVAILABLE = "Zapamiętywanie fraz nie jest jeszcze dostępne."
    const val UNCERTAIN = "Nie wiem, czy polecenie zostało wykonane."

    fun done(summary: String): String = "Wykonano: $summary"

    /** The dispatcher's trusted localized reason, with control characters removed. */
    fun blocked(text: String): String = text.filterNot(Char::isISOControl)

    fun rejection(reason: ActionRejection): String = when (reason) {
        ActionRejection.RECOVERED_NOT_EXECUTED -> RECOVERED
        ActionRejection.EXPIRED -> EXPIRED
        ActionRejection.ALIAS_UNAVAILABLE -> ALIAS_UNAVAILABLE
        else -> REJECTED
    }
}
