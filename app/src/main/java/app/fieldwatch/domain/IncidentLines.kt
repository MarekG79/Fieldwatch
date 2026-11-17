package app.fieldwatch.domain

/** English lines for saved events and surroundings. */
internal object IncidentLines {
    val all: List<EnglishLine> = listOf(
        en("incident.event", "Event", LanguageSlot.CONTROL),
        en("incident.surroundings", "Surroundings", LanguageSlot.CONTROL),
        en("incident.save", "Save event", LanguageSlot.CONTROL),
        en("incident.saving", "Saving…", LanguageSlot.CONTROL),
        en("incident.saved", "Saved: {name}", LanguageSlot.BODY),
        en("incident.saved.detail", "Saved: {name} · {n} radios · Downloads/{folder}", LanguageSlot.BODY),
        en("incident.save.failed", "Could not save.", LanguageSlot.BODY),
        en("incident.rename", "Rename", LanguageSlot.CONTROL),
        en("incident.surroundings.save", "Save surroundings…", LanguageSlot.CONTROL),
        en("incident.surroundings.note", "Saves the radios in this filter heard in the last 10 minutes, as PDF and JSON.", LanguageSlot.BODY),
        en("incident.window", "Last {n} minutes · {count} radios", LanguageSlot.BODY),
        en("incident.phone", "Phone position: {pos}", LanguageSlot.BODY),
        en("incident.alarm", "Alarm", LanguageSlot.CONTROL),
        en("incident.tone", "Propeller tone about {hz} Hz", LanguageSlot.BODY),
        en("incident.audio", "Sound clip: Downloads/Antidron/{name}", LanguageSlot.BODY),
        en("incident.radios", "Radios", LanguageSlot.CONTROL),
        en("incident.drone.at", "Drone: {pos}", LanguageSlot.BODY),
        en("incident.pilot.at", "Pilot or takeoff: {pos}", LanguageSlot.BODY),
    )
}
