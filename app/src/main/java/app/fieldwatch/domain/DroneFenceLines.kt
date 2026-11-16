package app.fieldwatch.domain

/** English lines for the drone geofence. Slovak is in assets/lang/sk.json. */
internal object DroneFenceLines {
    val all: List<EnglishLine> = listOf(
        en("settings.topic.dronefence", "Drone geofence", LanguageSlot.CONTROL),
        en("dronefence.on", "Alarm when a drone comes near", LanguageSlot.CONTROL),
        en(
            "dronefence.note",
            "Uses the position a drone sends in Remote ID. Drones without Remote ID, and most FPV drones, are not seen. This needs GPS location on.",
            LanguageSlot.BODY,
        ),
        en("dronefence.radius", "Radius", LanguageSlot.CONTROL),
        en("dronefence.pilot", "Also alarm for the pilot", LanguageSlot.CONTROL),
        en(
            "dronefence.pilot.note",
            "The pilot or takeoff position the drone sends. A pilot near you can matter as much as the drone.",
            LanguageSlot.BODY,
        ),
        en("dronefence.beep", "Beep", LanguageSlot.CONTROL),
        en("dronefence.voice", "Speak", LanguageSlot.CONTROL),
        en("dronefence.shade", "Notification", LanguageSlot.CONTROL),
        en("dronefence.needs.gps", "Turn on GPS location to use the geofence.", LanguageSlot.BODY),
        en("dronefence.alert.aircraft", "Drone within {m}", LanguageSlot.CONTROL),
        en("dronefence.alert.pilot", "Drone pilot within {m}", LanguageSlot.CONTROL),
        en("dronefence.alert.body", "{id} is {d} from you. Radius {m}.", LanguageSlot.BODY),
        en("dronefence.say.aircraft", "Drone within {m}", LanguageSlot.CONTROL),
        en("dronefence.say.pilot", "Drone pilot within {m}", LanguageSlot.CONTROL),
    )
}
