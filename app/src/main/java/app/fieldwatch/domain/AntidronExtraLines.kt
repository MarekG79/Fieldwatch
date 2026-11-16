package app.fieldwatch.domain

/** English lines for Wi-Fi NAN, screen-off listening and the full-screen alarm. */
internal object AntidronExtraLines {
    val all: List<EnglishLine> = listOf(
        en("acoustic.background", "Listen with the screen off", LanguageSlot.CONTROL),
        en("acoustic.background.note", "Keeps listening with the screen off while scanning runs. Android shows that the microphone is in use. Swiping the app away stops it.", LanguageSlot.BODY),
        en("acoustic.background.on", "Listening with the screen off.", LanguageSlot.BODY),
        en("acoustic.background.needs.scan", "Screen-off listening needs scanning on. Start it on this screen.", LanguageSlot.BODY),
        en("alarm.dismiss", "I see it", LanguageSlot.CONTROL),
        en("alarm.open", "Open the app", LanguageSlot.CONTROL),
        en("dronefence.fullscreen", "Full-screen alarm", LanguageSlot.CONTROL),
        en("dronefence.fullscreen.note", "A red alarm that fills the screen, also over the lock screen.", LanguageSlot.BODY),
        en("dronefence.fullscreen.denied", "Android does not yet allow full-screen alarms for this app.", LanguageSlot.BODY),
        en("dronefence.fullscreen.allow", "Allow", LanguageSlot.CONTROL),
        en("nan.title", "Remote ID over Wi-Fi NAN", LanguageSlot.CONTROL),
        en("nan.note", "Some drones send Remote ID over Wi-Fi Aware (NAN). Only phones with Wi-Fi Aware can hear it.", LanguageSlot.BODY),
        en("nan.status", "Wi-Fi NAN: {state}", LanguageSlot.CONTROL),
        en("nan.state.running", "listening", LanguageSlot.CONTROL),
        en("nan.state.unsupported", "this phone has no Wi-Fi Aware", LanguageSlot.CONTROL),
        en("nan.state.unavailable", "paused (Wi-Fi off, hotspot or Wi-Fi Direct)", LanguageSlot.CONTROL),
        en("nan.state.failed", "could not start", LanguageSlot.CONTROL),
        en("nan.state.starting", "starting", LanguageSlot.CONTROL),
        en("nan.state.off", "switched off", LanguageSlot.CONTROL),
        en("nan.state.waiting", "starts with scanning", LanguageSlot.CONTROL),
    )
}
