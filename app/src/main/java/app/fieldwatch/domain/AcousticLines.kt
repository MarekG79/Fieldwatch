package app.fieldwatch.domain

/** English lines for the acoustic drone detector. Slovak and Czech are in assets/lang. */
internal object AcousticLines {
    val all: List<EnglishLine> = listOf(
        en("acoustic.title", "Propeller sound", LanguageSlot.CONTROL),
        en("acoustic.listen", "Listen with the microphone", LanguageSlot.CONTROL),
        en("acoustic.note", "Hears the hum of drone propellers, also drones without Remote ID. Range is usually 50 to 150 m in quiet places, less in wind or traffic. Mowers, engines and fans can sound alike.", LanguageSlot.BODY),
        en("acoustic.foreground", "Listens only while this screen is open. The screen stays on.", LanguageSlot.BODY),
        en("acoustic.listening", "Listening…", LanguageSlot.CONTROL),
        en("acoustic.quiet", "No propeller sound", LanguageSlot.CONTROL),
        en("acoustic.maybe", "Something hums at {hz} Hz", LanguageSlot.CONTROL),
        en("acoustic.heard", "Likely a drone · {hz} Hz", LanguageSlot.CONTROL),
        en("acoustic.level", "Sound level {db} dB", LanguageSlot.CONTROL),
        en("acoustic.save", "Save last 10 s", LanguageSlot.CONTROL),
        en("acoustic.saved", "Saved to Downloads/Antidron: {name}", LanguageSlot.BODY),
        en("acoustic.save.failed", "Could not save the recording.", LanguageSlot.BODY),
        en("acoustic.error.permission", "Allow the microphone to listen for drones.", LanguageSlot.BODY),
        en("acoustic.error.mic", "The microphone is busy or not available.", LanguageSlot.BODY),
        en("acoustic.alert", "Drone sound nearby", LanguageSlot.CONTROL),
        en("acoustic.alert.body", "Propeller hum at about {hz} Hz. The position is not known.", LanguageSlot.BODY),
        en("simple.heard", "Likely a drone (sound)", LanguageSlot.CONTROL),
        en("simple.heard.note", "Propeller hum at about {hz} Hz. The position is not known.", LanguageSlot.BODY),
    )
}
