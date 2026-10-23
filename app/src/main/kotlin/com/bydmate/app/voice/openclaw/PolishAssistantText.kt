package com.bydmate.app.voice.openclaw

/**
 * Polish status and error lines for the assistant settings and the connection test. Kept in code
 * rather than Android resources on purpose: the Polish setup must stay readable when the app
 * itself is shown in another language. Pure; no secrets and no server text ever appear here.
 */
object PolishAssistantText {
    const val DISABLED = "Asystent online jest wyłączony. Włącz go w ustawieniach."
    const val ENDPOINT_MISSING = "Podaj adres bramy asystenta (wss://…) w ustawieniach."
    const val UNCERTAIN =
        "Nie wiadomo, czy asystent dostał pytanie. Nie wysłałem go ponownie. Zapytaj jeszcze raz, jeśli chcesz."
    const val RESEND_TOO_LARGE =
        "Ponowienie nie zostało wysłane: wiadomość jest za duża dla bramy asystenta. " +
            "Nadal nie wiadomo, czy asystent dostał pierwotne pytanie. Możesz spróbować ponownie albo zapytać od nowa."
    const val APPROVAL = "Urządzenie czeka na zatwierdzenie na komputerze z asystentem."
    const val REVOKED = "Asystent odrzucił to urządzenie. Sparuj je ponownie."

    fun failure(failure: OpenClawFailure): String = when (failure) {
        OpenClawFailure.NetworkUnavailable -> "Brak połączenia z bramą asystenta. Sprawdź sieć i Tailscale."
        OpenClawFailure.TlsRejected -> "Certyfikat bramy jest nieprawidłowy. Połączenie odrzucone."
        OpenClawFailure.HostRejected -> "Brama przekierowała na inny adres. Połączenie odrzucone."
        OpenClawFailure.ChallengeFailed -> "Brama nie przedstawiła się poprawnie. Połączenie przerwane."
        OpenClawFailure.ProtocolMismatch -> "Brama używa innej wersji protokołu niż aplikacja."
        OpenClawFailure.ScopeMismatch -> "Brama przyznała inne uprawnienia niż czat. Połączenie odrzucone."
        OpenClawFailure.AuthRejected -> "Brama nie przyjęła tego urządzenia. Sparuj je ponownie."
        OpenClawFailure.Forbidden -> "Brama odmówiła tej operacji."
        else -> requestFailure(failure)
    }

    /** A safe summary of the service-owned recovery queue; never includes transport diagnostics. */
    internal fun recovery(state: OpenClawRecoveryState): String = when (state) {
        OpenClawRecoveryState.Idle -> "Automatyczne odzyskiwanie nie jest aktywne."
        is OpenClawRecoveryState.Connecting -> "Trwa automatyczne łączenie z asystentem."
        is OpenClawRecoveryState.Retrying -> {
            val delaySeconds = state.nextDelayMs / 1_000
            val failureText = state.failure?.let(::failure)
            listOfNotNull(failureText, "Kolejna próba za $delaySeconds s.").joinToString(" ")
        }
        OpenClawRecoveryState.Ready -> "Automatyczne odzyskiwanie: połączono."
        is OpenClawRecoveryState.Stopped -> stoppedRecovery(state.reason)
    }

    private fun stoppedRecovery(reason: AutomaticReconnectSkipReason?): String = when (reason) {
        AutomaticReconnectSkipReason.Disabled -> "Automatyczne odzyskiwanie zatrzymane: asystent jest wyłączony."
        AutomaticReconnectSkipReason.InvalidEndpoint -> "Automatyczne odzyskiwanie zatrzymane: popraw adres bramy."
        AutomaticReconnectSkipReason.Unpaired -> "Automatyczne odzyskiwanie zatrzymane: urządzenie nie jest sparowane."
        AutomaticReconnectSkipReason.AwaitingApproval -> "Automatyczne odzyskiwanie zatrzymane: urządzenie czeka na zatwierdzenie."
        AutomaticReconnectSkipReason.Revoked -> "Automatyczne odzyskiwanie zatrzymane: urządzenie zostało odrzucone."
        AutomaticReconnectSkipReason.IdentityChanged -> "Automatyczne odzyskiwanie zatrzymane: dane parowania się zmieniły."
        null -> "Automatyczne odzyskiwanie zatrzymane."
    }

    private fun requestFailure(failure: OpenClawFailure): String = when (failure) {
        OpenClawFailure.ServerUnavailable -> "Asystent jest chwilowo niedostępny."
        OpenClawFailure.ServerError -> "Asystent zwrócił błąd."
        OpenClawFailure.Timeout -> "Asystent nie odpowiedział na czas."
        OpenClawFailure.Busy -> "Poprzednia wiadomość jest jeszcze w toku."
        OpenClawFailure.EmptyRequest -> "Wpisz wiadomość."
        OpenClawFailure.EmptyAnswer -> "Asystent nie zwrócił odpowiedzi."
        OpenClawFailure.NothingToResend -> "Nie ma nic do ponowienia."
        OpenClawFailure.RequestTooLarge -> "Wiadomość jest za duża dla bramy asystenta. Nie została wysłana."
        else -> error("not a request failure: $failure")
    }

    /** The one-line state of the pairing as the settings screen shows it. */
    fun link(status: OpenClawStatus): String = when {
        !status.enabled -> "Wyłączony"
        !status.endpointValid -> "Podaj adres bramy (wss://…)"
        else -> when (val link = status.link) {
            OpenClawLinkState.Unpaired -> "Nie sparowano"
            OpenClawLinkState.Disconnected -> "Sparowano, rozłączono"
            OpenClawLinkState.Connecting -> "Łączę…"
            is OpenClawLinkState.AwaitingApproval -> "Czeka na zatwierdzenie na komputerze z asystentem"
            OpenClawLinkState.Ready -> "Połączono"
            OpenClawLinkState.Revoked -> "Odrzucono przez asystenta. Sparuj ponownie"
            is OpenClawLinkState.Failed -> failure(link.failure)
        }
    }
}
