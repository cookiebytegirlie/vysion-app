package com.vysion.xr.voice

import com.vysion.xr.core.Distances
import com.vysion.xr.core.HorizontalBearing
import com.vysion.xr.core.Mode
import com.vysion.xr.core.NavigationCue
import com.vysion.xr.core.OnboardingItem
import com.vysion.xr.core.OnboardingPage
import com.vysion.xr.core.OnboardingStep
import com.vysion.xr.core.PhoneOnboardingPage
import com.vysion.xr.core.PhoneOnboardingStep
import com.vysion.xr.core.Speaker
import com.vysion.xr.core.ModePhrasebook
import com.vysion.xr.core.Surroundings
import com.vysion.xr.core.WhereAmI
import java.util.Locale
import kotlin.math.roundToInt

/**
 * EVERY spoken string of the product, in en-US and es-MX, keyed (SRS §8 R4 "every cue string in `Cues.kt`,
 * nothing hardcoded elsewhere"; INV-4; `scripts/check_cues.sh` enforces it). Owner R4, due Sat 16:00
 * (phrasing guide) with the P0 strings needed at Sat 15:00 (welcome FR-G1, mode announcements FR-G3).
 * Provided to R1 (`ModeController`, `Permissions`, panels) and R3 (never builds strings itself; its
 * [NavigationCue]s are turned into words by [phrase]). Pure Kotlin: no Android classes, unit-tested.
 *
 * Phrasing rules (SRS §3, verbatim in `docs/modules/r4-voice-accessibility.md`):
 * - bearings are clock-face hours ("2 o'clock" / "a las 2"), never degrees ([ClockFace] computes the hour);
 * - distances are meters rounded to 0.5 ([Distances.roundToHalfMeter]) and spoken as "3 meters",
 *   "2 and a half meters", "half a meter", never "2.5";
 * - hazards are spoken before descriptions: the caller passes `interrupt = true` for [NavigationCue.Proximity]
 *   (SRS §3 "hazards are spoken before descriptions");
 * - no hedging: "probably", "maybe", "I think" and their Spanish equivalents are banned
 *   ([violatesPhrasingRules] checks a string; the unit test checks every value here);
 * - short: one clause per cue, so "stop" has little to interrupt (FR-G4).
 *
 * Only the text comes from here; `Speaker.say` is the only thing that speaks (FR-G4). Depends on A-8 only
 * in that the es-MX strings need an offline Spanish voice; with none, the en-US text is spoken.
 */
object Cues {

    enum class Key {
        /** FR-G1: lists the three commands. */
        WELCOME,

        /** SRS §3 product sentence, verbatim; `R.string.product_sentence` (strings.xml) is its visual twin. */
        PRODUCT_SENTENCE,

        /** FR-G3 mode announcements. */
        MODE_TEXT_READER,
        MODE_AUDIO_DESCRIPTION,
        MODE_NAVIGATION,

        /** Eyes-free paging (hand gesture NEXT_PAGE): page name + how to run it. */
        PAGE_TEXT_READER,
        PAGE_AUDIO_DESCRIPTION,
        PAGE_NAVIGATION,

        LISTENING,
        DONE,

        /** plan.md busy policy: a non-Stop command while CAPTURING/INFERRING. */
        BUSY,

        /** NFR reliability: any failed inference speaks an error within 2 s. */
        INFERENCE_FAILED,
        CAMERA_LOST,
        MODEL_NOT_FOUND,
        READING_WHOLE_VIEW,

        /** FR-T5. */
        HOLD_STILL,

        /** FR-N1. */
        OBSTACLE_AHEAD,

        /** RK-6, verbatim. */
        DOOR_NOT_FOUND,
        DOOR_SEEN_LEFT,
        DOOR_SEEN_CENTER,
        DOOR_SEEN_RIGHT,

        /** [NavigationCue.TargetLost]. */
        DOOR_LOST,

        /** FR-N5. */
        WALL_AHEAD,
        FLOOR_ENDS,

        /** FR-N6 / [NavigationCue.Arrived]. */
        ARRIVED,

        /** ADR-0006: NoOp perception on the S25 Ultra. */
        NAVIGATION_UNAVAILABLE_ON_THIS_DEVICE,

        /** A-7 / RK-4: no on-device recognition; pinch and panel buttons remain. */
        VOICE_COMMANDS_UNAVAILABLE,

        /** A-8 fallback: es-MX, then es-ES, then en-US with this notice. */
        SPANISH_VOICE_UNAVAILABLE,

        /** FR-T3 with nothing said yet. */
        NOTHING_TO_REPEAT,

        /** FR-G1 "permissions requested with spoken explanations": said by R1 before the system dialogs. */
        PERMISSION_EXPLANATION,

        /** FR-G1: camera or microphone refused; nothing can run. */
        PERMISSION_DENIED,

        /** ADR-0010: "cloud on"; says that photos leave the headset and who gets them (consent, spoken). */
        CLOUD_DESCRIBE_ON,

        /** ADR-0010: "cloud off", or the headset was taken off. */
        CLOUD_DESCRIBE_OFF,

        /** ADR-0010: "cloud on" in a build with no cloud key. */
        CLOUD_DESCRIBE_UNAVAILABLE,

        /** FR-N9: said before the location dialog, the first time "where am I" is asked; names what leaves the headset. */
        WHERE_AM_I_PERMISSION,

        /** FR-N9: precise location refused (or only approximate allowed); the way out is the app's Settings page. */
        WHERE_AM_I_PERMISSION_DENIED,

        /** FR-N9: Location is off in Settings. One sentence with the way out. */
        LOCATION_OFF,

        /** FR-N9: no fix in time (no Wi-Fi nearby, or no network for the lookup). */
        LOCATION_NO_FIX,

        /** FR-N9: offline, and the map around the wearer is not in memory. */
        MAP_UNAVAILABLE,

        /** FR-N9: the map around the wearer names no street and no place. */
        NOTHING_NAMED_NEARBY,
    }

    /** Languages this object speaks; anything else falls back to en-US (SRS scope: English and Spanish). */
    private const val ES = "es"

    private val EN_US: Map<Key, String> = mapOf(
        Key.WELCOME to "Welcome to Vysion XR. To talk, touch your thumb to your middle finger and hold, " +
            "then say read this, describe, or find the door. To stop me, make the hang loose sign, thumb and little finger out, or say stop.",
        // Verbatim copy of R.string.product_sentence (res/values/strings.xml); the test keeps them equal.
        Key.PRODUCT_SENTENCE to "Vysion XR helps you read, understand, and move through a space. " +
            "It is not validated for unassisted travel by blind users.",
        Key.MODE_TEXT_READER to "Reading",
        Key.MODE_AUDIO_DESCRIPTION to "Describing",
        Key.MODE_NAVIGATION to "Navigating",
        Key.PAGE_TEXT_READER to "Read. Tap your thumb to your middle finger to read.",
        Key.PAGE_AUDIO_DESCRIPTION to "Describe. Tap your thumb to your middle finger to describe.",
        Key.PAGE_NAVIGATION to "Navigation. Tap your thumb to your middle finger to find the door.",
        Key.LISTENING to "Listening",
        Key.DONE to "Done",
        Key.BUSY to "One moment, still working",
        Key.INFERENCE_FAILED to "That did not work. Say it again.",
        Key.CAMERA_LOST to "I lost the camera. Voice commands still work.",
        Key.MODEL_NOT_FOUND to "Model files not found. Run the fetch models script.",
        Key.READING_WHOLE_VIEW to "No text at the crosshair. Reading everything in view.",
        Key.HOLD_STILL to "Hold still",
        Key.OBSTACLE_AHEAD to "obstacle ahead",
        Key.DOOR_NOT_FOUND to "I can't find a door, turn slowly",
        Key.DOOR_SEEN_LEFT to "Door on your left.",
        Key.DOOR_SEEN_CENTER to "Door straight ahead.",
        Key.DOOR_SEEN_RIGHT to "Door on your right.",
        Key.DOOR_LOST to "I lost the door, turn slowly",
        Key.WALL_AHEAD to "wall ahead",
        Key.FLOOR_ENDS to "floor ends",
        Key.ARRIVED to "you have arrived",
        Key.NAVIGATION_UNAVAILABLE_ON_THIS_DEVICE to
            "Navigation is not available on this device. Read and describe still work.",
        Key.VOICE_COMMANDS_UNAVAILABLE to
            "Voice commands are not working right now. Tap your thumb to your middle finger to run the page, or use the buttons.",
        Key.SPANISH_VOICE_UNAVAILABLE to "The Spanish voice is not installed. Continuing in English.",
        Key.NOTHING_TO_REPEAT to "Nothing to repeat yet",
        Key.PERMISSION_EXPLANATION to "Vysion needs the camera to see, the microphone to hear you, " +
            "and scene understanding to measure distances.",
        Key.PERMISSION_DENIED to "Without the camera and the microphone, Vysion cannot read, describe or guide. " +
            "Allow them in Settings and come back.",
        Key.CLOUD_DESCRIBE_ON to "Cloud describe on. When you say describe, the photo is sent to Google. " +
            "Say cloud off to keep everything on this headset.",
        Key.CLOUD_DESCRIBE_OFF to "Cloud describe off. Everything stays on this headset.",
        Key.CLOUD_DESCRIBE_UNAVAILABLE to "Cloud describe is not set up on this headset. Describe works without it.",
        Key.WHERE_AM_I_PERMISSION to "To say where you are, Vysion needs your precise location. It downloads the map " +
            "around you from OpenFreeMap, and Google words the answer when you are online. Nothing is saved.",
        Key.WHERE_AM_I_PERMISSION_DENIED to "Without precise location, Vysion cannot name the street. Allow it in " +
            "Settings, Apps, Vysion XR, Permissions, Location.",
        Key.LOCATION_OFF to "Location is off, so turn it on in Settings, under Location, and ask again.",
        Key.LOCATION_NO_FIX to "I can't get your location right now, so check that Wi-Fi is on and ask again.",
        Key.MAP_UNAVAILABLE to "I need an internet connection to load the map around you, so connect and ask again.",
        Key.NOTHING_NAMED_NEARBY to "I found no named street or place near you.",
    )

    private val ES_MX: Map<Key, String> = mapOf(
        Key.WELCOME to "Bienvenido a Vysion XR. Para hablar, junta el pulgar con el dedo medio y mantén, " +
            "y di lee esto, describe, o busca la puerta. Para callarme, extiende el pulgar y el meñique, o di alto.",
        Key.PRODUCT_SENTENCE to "Vysion XR te ayuda a leer, entender y moverte por un espacio. " +
            "No está validado para que personas ciegas viajen sin asistencia.",
        Key.MODE_TEXT_READER to "Leyendo",
        Key.MODE_AUDIO_DESCRIPTION to "Describiendo",
        Key.MODE_NAVIGATION to "Navegando",
        Key.PAGE_TEXT_READER to "Leer. Toca el pulgar con el dedo medio para leer.",
        Key.PAGE_AUDIO_DESCRIPTION to "Describir. Toca el pulgar con el dedo medio para describir.",
        Key.PAGE_NAVIGATION to "Navegación. Toca el pulgar con el dedo medio para buscar la puerta.",
        Key.LISTENING to "Escuchando",
        Key.DONE to "Listo",
        Key.BUSY to "Un momento, sigo trabajando",
        Key.INFERENCE_FAILED to "Eso no funcionó. Dilo otra vez.",
        Key.CAMERA_LOST to "Perdí la cámara. Los comandos de voz siguen funcionando.",
        Key.MODEL_NOT_FOUND to "No encontré los archivos del modelo. Ejecuta el script fetch models.",
        Key.READING_WHOLE_VIEW to "No hay texto en la mira. Leo todo lo que se ve.",
        Key.HOLD_STILL to "Quédate quieto",
        Key.OBSTACLE_AHEAD to "obstáculo adelante",
        Key.DOOR_NOT_FOUND to "No encuentro una puerta, gira despacio",
        Key.DOOR_SEEN_LEFT to "Puerta a tu izquierda.",
        Key.DOOR_SEEN_CENTER to "Puerta al frente.",
        Key.DOOR_SEEN_RIGHT to "Puerta a tu derecha.",
        Key.DOOR_LOST to "Perdí la puerta, gira despacio",
        Key.WALL_AHEAD to "pared adelante",
        Key.FLOOR_ENDS to "el piso termina",
        Key.ARRIVED to "has llegado",
        Key.NAVIGATION_UNAVAILABLE_ON_THIS_DEVICE to
            "La navegación no está disponible en este dispositivo. Leer y describir sí funcionan.",
        Key.VOICE_COMMANDS_UNAVAILABLE to
            "Los comandos de voz no funcionan ahora. Toca el pulgar con el dedo medio para usar la página, o usa los botones.",
        Key.SPANISH_VOICE_UNAVAILABLE to "La voz en español no está instalada. Continúo en inglés.",
        Key.NOTHING_TO_REPEAT to "Nada que repetir todavía",
        Key.PERMISSION_EXPLANATION to "Vysion necesita la cámara para ver, el micrófono para escucharte " +
            "y entender la escena para medir distancias.",
        Key.PERMISSION_DENIED to "Sin la cámara y el micrófono, Vysion no puede leer, describir ni guiar. " +
            "Permítelos en Ajustes y vuelve.",
        Key.CLOUD_DESCRIBE_ON to "Nube activada. Cuando dices describe, la foto se envía a Google. " +
            "Di desactiva la nube para que todo se quede en este visor.",
        Key.CLOUD_DESCRIBE_OFF to "Nube desactivada. Todo se queda en este visor.",
        Key.CLOUD_DESCRIBE_UNAVAILABLE to "La nube no está configurada en este visor. Describir funciona sin ella.",
        Key.WHERE_AM_I_PERMISSION to "Para decir dónde estás, Vysion necesita tu ubicación precisa. Descarga el mapa " +
            "de tu zona de OpenFreeMap, y Google redacta la respuesta cuando hay conexión. No se guarda nada.",
        Key.WHERE_AM_I_PERMISSION_DENIED to "Sin la ubicación precisa, Vysion no puede nombrar la calle. Permítela en " +
            "Ajustes, Aplicaciones, Vysion XR, Permisos, Ubicación.",
        Key.LOCATION_OFF to "La ubicación está desactivada, así que actívala en Ajustes, en Ubicación, y vuelve a preguntar.",
        Key.LOCATION_NO_FIX to "No puedo obtener tu ubicación ahora, así que revisa que el Wi-Fi esté activado y vuelve a preguntar.",
        Key.MAP_UNAVAILABLE to "Necesito conexión a internet para cargar el mapa de tu zona, así que conéctate y vuelve a preguntar.",
        Key.NOTHING_NAMED_NEARBY to "No encontré calles ni lugares con nombre cerca de ti.",
    )

    /** The one lookup; every named accessor below goes through it. Unknown languages speak en-US. */
    fun text(key: Key, locale: Locale): String =
        (if (locale.language == ES) ES_MX else EN_US).getValue(key)

    fun welcome(locale: Locale = Speaker.EN_US): String = text(Key.WELCOME, locale)
    fun productSentence(locale: Locale = Speaker.EN_US): String = text(Key.PRODUCT_SENTENCE, locale)
    fun listening(locale: Locale = Speaker.EN_US): String = text(Key.LISTENING, locale)
    fun done(locale: Locale = Speaker.EN_US): String = text(Key.DONE, locale)
    fun busy(locale: Locale = Speaker.EN_US): String = text(Key.BUSY, locale)
    fun inferenceFailed(locale: Locale = Speaker.EN_US): String = text(Key.INFERENCE_FAILED, locale)

    /** `docs/nfr.md` names this `Cues.error`; same string as [inferenceFailed]. */
    fun error(locale: Locale = Speaker.EN_US): String = inferenceFailed(locale)
    fun cameraLost(locale: Locale = Speaker.EN_US): String = text(Key.CAMERA_LOST, locale)
    fun modelNotFound(locale: Locale = Speaker.EN_US): String = text(Key.MODEL_NOT_FOUND, locale)

    /** Reading crosshair: nothing readable inside it, so the whole view is read next. */
    fun readingWholeView(locale: Locale = Speaker.EN_US): String = text(Key.READING_WHOLE_VIEW, locale)

    /** ADR-0010: the cloud Describe switch, confirmed aloud. */
    fun cloudDescribe(on: Boolean, locale: Locale = Speaker.EN_US): String =
        text(if (on) Key.CLOUD_DESCRIBE_ON else Key.CLOUD_DESCRIBE_OFF, locale)
    fun cloudDescribeUnavailable(locale: Locale = Speaker.EN_US): String = text(Key.CLOUD_DESCRIBE_UNAVAILABLE, locale)
    fun holdStill(locale: Locale = Speaker.EN_US): String = text(Key.HOLD_STILL, locale)
    fun obstacleAhead(locale: Locale = Speaker.EN_US): String = text(Key.OBSTACLE_AHEAD, locale)
    fun doorNotFound(locale: Locale = Speaker.EN_US): String = text(Key.DOOR_NOT_FOUND, locale)

    /** ADR-0006 (phone): the door's side from one glance, no distance (none is measured there). NONE says "not found". */
    fun doorSeen(bearing: HorizontalBearing, locale: Locale = Speaker.EN_US): String = when (bearing) {
        HorizontalBearing.LEFT -> text(Key.DOOR_SEEN_LEFT, locale)
        HorizontalBearing.CENTER -> text(Key.DOOR_SEEN_CENTER, locale)
        HorizontalBearing.RIGHT -> text(Key.DOOR_SEEN_RIGHT, locale)
        HorizontalBearing.NONE -> text(Key.DOOR_NOT_FOUND, locale)
    }
    fun navigationUnavailableOnThisDevice(locale: Locale = Speaker.EN_US): String =
        text(Key.NAVIGATION_UNAVAILABLE_ON_THIS_DEVICE, locale)
    fun voiceCommandsUnavailable(locale: Locale = Speaker.EN_US): String = text(Key.VOICE_COMMANDS_UNAVAILABLE, locale)
    fun spanishVoiceUnavailable(locale: Locale = Speaker.EN_US): String = text(Key.SPANISH_VOICE_UNAVAILABLE, locale)
    fun nothingToRepeat(locale: Locale = Speaker.EN_US): String = text(Key.NOTHING_TO_REPEAT, locale)
    fun permissionExplanation(locale: Locale = Speaker.EN_US): String = text(Key.PERMISSION_EXPLANATION, locale)
    fun permissionDenied(locale: Locale = Speaker.EN_US): String = text(Key.PERMISSION_DENIED, locale)

    /**
     * FR-N9: the words for every "where am I" outcome. [WhereAmI.Found] is the on-device template, spoken offline or
     * when the cloud wording fails: "You are near Larkin Street, between Fulton Street and Grove Street. Nearby:
     * Main Library, about 20 meters; …". No direction is ever said (the heading is unknown, no compass), and distances
     * are rough ([roughMeters]) because a Wi-Fi fix is good to tens of meters.
     */
    fun whereAmI(result: WhereAmI, locale: Locale = Speaker.EN_US): String = when (result) {
        is WhereAmI.Found -> surroundings(result.surroundings, locale)
        WhereAmI.PermissionNeeded -> text(Key.WHERE_AM_I_PERMISSION, locale)
        WhereAmI.PermissionDenied -> text(Key.WHERE_AM_I_PERMISSION_DENIED, locale)
        WhereAmI.LocationOff -> text(Key.LOCATION_OFF, locale)
        WhereAmI.NoFix -> text(Key.LOCATION_NO_FIX, locale)
        WhereAmI.NoMap -> text(Key.MAP_UNAVAILABLE, locale)
    }

    /** FR-N9 template: the street (with its cross streets), then up to three places, then a vague fix's accuracy. */
    fun surroundings(s: Surroundings, locale: Locale = Speaker.EN_US): String {
        val es = locale.language == ES
        val sentences = mutableListOf<String>()
        s.street?.let { street ->
            val opening = when {
                street.distanceM <= NEAR_STREET_M -> if (es) "Estás cerca de ${street.name}" else "You are near ${street.name}"
                es -> "La calle más cercana es ${street.name}, a ${roughMeters(street.distanceM, locale)}"
                else -> "The nearest street is ${street.name}, ${roughMeters(street.distanceM, locale)} away"
            }
            val cross = s.crossStreets
            sentences += when {
                cross.size >= 2 -> if (es) "$opening, entre ${cross[0].name} y ${cross[1].name}." else
                    "$opening, between ${cross[0].name} and ${cross[1].name}."
                cross.size == 1 -> if (es) "$opening, a ${roughMeters(cross[0].distanceM, locale)} de ${cross[0].name}." else
                    "$opening, ${roughMeters(cross[0].distanceM, locale)} from ${cross[0].name}."
                else -> "$opening."
            }
        }
        if (s.places.isNotEmpty()) {
            val items = s.places.map { place ->
                if (es) "${place.name}, a ${roughMeters(place.distanceM, locale)}" else "${place.name}, ${roughMeters(place.distanceM, locale)}"
            }
            val list = if (items.size == 1) items[0] else items.dropLast(1).joinToString("; ") + (if (es) "; y " else "; and ") + items.last()
            sentences += (if (es) "Cerca: " else "Nearby: ") + list + "."
        }
        if (sentences.isEmpty()) return text(Key.NOTHING_NAMED_NEARBY, locale)
        if (!s.accuracyM.isNaN() && s.accuracyM > VAGUE_FIX_M) {
            sentences += if (es) "Tu ubicación es precisa solo a ${roughMeters(s.accuracyM, locale)}." else
                "Your location is accurate only to ${roughMeters(s.accuracyM, locale)}."
        }
        return sentences.joinToString(" ")
    }

    /**
     * FR-N9 distances from a Wi-Fi fix, rounded so they do not claim precision they lack: to 10 m below 200 m, to 50 m
     * below 1 km, else to 100 m, never below 10: "about 40 meters" / "unos 40 metros".
     */
    fun roughMeters(distanceM: Float, locale: Locale = Speaker.EN_US): String {
        val step = when {
            distanceM < 200f -> 10
            distanceM < 1_000f -> 50
            else -> 100
        }
        val rounded = maxOf(10, (distanceM / step).roundToInt() * step)
        return if (locale.language == ES) "unos $rounded metros" else "about $rounded meters"
    }

    /** FR-G3: spoken on every mode change; the panel shows the same mode. */
    /** Eyes-free paging: the page's name and how to run it with the middle-finger tap. */
    fun pageFocused(mode: Mode, locale: Locale = Speaker.EN_US): String = when (mode) {
        Mode.TEXT_READER -> text(Key.PAGE_TEXT_READER, locale)
        Mode.AUDIO_DESCRIPTION -> text(Key.PAGE_AUDIO_DESCRIPTION, locale)
        Mode.NAVIGATION -> text(Key.PAGE_NAVIGATION, locale)
    }

    fun modeAnnouncement(mode: Mode, locale: Locale = Speaker.EN_US): String = when (mode) {
        Mode.TEXT_READER -> text(Key.MODE_TEXT_READER, locale)
        Mode.AUDIO_DESCRIPTION -> text(Key.MODE_AUDIO_DESCRIPTION, locale)
        Mode.NAVIGATION -> text(Key.MODE_NAVIGATION, locale)
    }

    /**
     * Words for one [NavigationCue] (SRS §8 R3 "door bearing in clock-face form, distance in meters rounded
     * to 0.5"; R4 turns it into words). Examples: "door, 2 o'clock, 3 meters" / "puerta, a las 2, 3 metros",
     * "obstacle ahead", "wall ahead", "floor ends", "you have arrived".
     */
    fun phrase(cue: NavigationCue, locale: Locale = Speaker.EN_US): String = when (cue) {
        is NavigationCue.Proximity -> text(Key.OBSTACLE_AHEAD, locale) // FR-N1, verbatim; interrupt = true
        is NavigationCue.DoorBearing -> doorBearing(cue.clock, cue.distanceM, locale) // FR-N3 every 3 s
        is NavigationCue.WallAhead -> text(Key.WALL_AHEAD, locale) // FR-N5
        is NavigationCue.FloorEnds -> text(Key.FLOOR_ENDS, locale) // FR-N5
        NavigationCue.Arrived -> text(Key.ARRIVED, locale) // FR-N6
        NavigationCue.TargetLost -> text(Key.DOOR_LOST, locale)
    }

    /** "door, 2 o'clock, 3 meters" / "puerta, a las 2, 3 metros". `clock` is 1..12 from `ClockFace`. */
    fun doorBearing(clock: Int, distanceM: Float, locale: Locale = Speaker.EN_US): String {
        require(clock in 1..12) { "clock must be 1..12: $clock" }
        val es = locale.language == ES
        val noun = if (es) "puerta" else "door"
        return "$noun, ${clockFace(clock, locale)}, ${meters(distanceM, locale)}"
    }

    /** "2 o'clock" / "a las 2" ("a la 1" is singular). */
    fun clockFace(clock: Int, locale: Locale = Speaker.EN_US): String {
        require(clock in 1..12) { "clock must be 1..12: $clock" }
        return if (locale.language == ES) {
            if (clock == 1) "a la 1" else "a las $clock"
        } else {
            "$clock o'clock"
        }
    }

    /**
     * Meters rounded to 0.5 and spoken without a decimal point: "3 meters", "1 meter", "2 and a half meters",
     * "half a meter", "less than half a meter" / "3 metros", "1 metro", "2 metros y medio", "medio metro".
     */
    fun meters(distanceM: Float, locale: Locale = Speaker.EN_US): String {
        val rounded = Distances.roundToHalfMeter(distanceM)
        val whole = rounded.toInt()
        val half = rounded - whole >= 0.5f
        val es = locale.language == ES
        return when {
            whole == 0 && !half -> if (es) "menos de medio metro" else "less than half a meter"
            whole == 0 -> if (es) "medio metro" else "half a meter"
            es && whole == 1 -> if (half) "1 metro y medio" else "1 metro"
            es -> if (half) "$whole metros y medio" else "$whole metros"
            whole == 1 && !half -> "1 meter"
            half -> "$whole and a half meters"
            else -> "$whole meters"
        }
    }

    // ── phrasing rules ───────────────────────────────────────────────────────────────────────────────

    /** FR-N9: a street this close is the one the wearer is "near" (a Wi-Fi fix is good to tens of meters). */
    const val NEAR_STREET_M: Float = 40f

    /** FR-N9: a fix vaguer than this is said aloud after the answer. */
    const val VAGUE_FIX_M: Float = 75f

    /** The rules, as text, for the module doc and for anyone writing a new cue. */
    val PHRASING_RULES: List<String> = listOf(
        "Bearings are clock-face hours: '2 o'clock', never degrees.",
        "Distances are meters rounded to 0.5 and spoken as words: '2 and a half meters', never '2.5'.",
        "Hazards first: Proximity is said with interrupt = true, before any description.",
        "No hedging: nothing is spoken that was not measured or seen.",
        "One clause per cue; every cue is interruptible with 'stop'.",
    )

    /** Hedging words and phrases, accent-folded (SRS §3 "no 'probably a door'"). */
    val HEDGING_WORDS: List<String> = listOf( // cues-allow: this is the banned list itself
        "probably", "maybe", "perhaps", "possibly", "likely", // cues-allow: banned list
        "i think", "i guess", "i believe", // cues-allow: banned list
        "might", "could be", "seems", "appears", "looks like", "sort of", "kind of", // cues-allow: banned list
        "probablemente", "quiza", "quizas", "tal vez", "posiblemente", "creo que", "supongo", // cues-allow: banned list
        "parece", "a lo mejor", "puede que", "puede ser", "podria", // cues-allow: banned list
    )

    private val DECIMAL_NUMBER = Regex("\\d\\.\\d")
    private val DEGREES = Regex("\\b(degrees?|grados?)\\b")

    /**
     * Every rule the string breaks, as human-readable reasons; empty when it is clean. Used by the unit test
     * over every value in this file and available to R2 for VLM output if they want it.
     */
    fun violatesPhrasingRules(s: String): List<String> {
        val folded = " " + TextFold.fold(s) + " "
        val violations = mutableListOf<String>()
        for (word in HEDGING_WORDS) {
            if (folded.contains(" $word ")) violations += "hedging word '$word'"
        }
        if (DECIMAL_NUMBER.containsMatchIn(s)) violations += "decimal number: say 'and a half' instead"
        if (DEGREES.containsMatchIn(folded)) violations += "bearing in degrees: use clock-face hours"
        return violations
    }

    /**
     * First-launch onboarding (Michelle Bui's designs, pages 2-8). The English is the designs' copy verbatim;
     * each page is read aloud on entry ([OnboardingPage.spoken]) and shown with the same words, so the visual
     * and the audio twin cannot drift (SRS §3 rule 1).
     */
    fun onboarding(step: OnboardingStep, locale: Locale = Speaker.EN_US): OnboardingPage =
        (if (locale.language == ES) OnboardingCopy.ES_MX else OnboardingCopy.EN_US).page(step)

    /** Onboarding page 4: Vysion keeps talking until the user stops it, so stopping can be practiced. */
    fun stopPractice(locale: Locale = Speaker.EN_US): String =
        (if (locale.language == ES) OnboardingCopy.ES_MX else OnboardingCopy.EN_US).stopPractice

    /**
     * Phone onboarding (designs "Phone v6 · End-to-end", section 1): six pages, three of them lessons; each is read aloud on
     * entry and the screen shows the same words. `PhoneOnboardingCuesTest` checks both languages.
     */
    fun phoneOnboarding(step: PhoneOnboardingStep, locale: Locale = Speaker.EN_US): PhoneOnboardingPage =
        (if (locale.language == ES) PhoneOnboardingCopy.ES_MX else PhoneOnboardingCopy.EN_US).page(step)

    /** Phone lesson 3: Vysion keeps talking until the two-finger double-tap stops it, so stopping can be practiced. */
    fun phoneStopPractice(locale: Locale = Speaker.EN_US): String =
        (if (locale.language == ES) PhoneOnboardingCopy.ES_MX else PhoneOnboardingCopy.EN_US).stopPractice

    /** Every phone onboarding string in both languages, for the phrasing test. */
    internal fun phoneOnboardingValues(): List<String> = listOf(PhoneOnboardingCopy.EN_US, PhoneOnboardingCopy.ES_MX).flatMap { copy ->
        PhoneOnboardingStep.entries.flatMap { step ->
            val page = copy.page(step)
            listOfNotNull(page.title, page.body, page.hint, page.spoken) + page.items.flatMap { listOfNotNull(it.title, it.detail) }
        } + copy.stopPractice
    }

    /** Every onboarding string in both languages, for the phrasing test. */
    internal fun onboardingValues(): List<String> = listOf(OnboardingCopy.EN_US, OnboardingCopy.ES_MX).flatMap { copy ->
        OnboardingStep.entries.flatMap { step ->
            val page = copy.page(step)
            listOfNotNull(page.title, page.body, page.hint, page.footnote, page.spoken) +
                page.items.flatMap { listOfNotNull(it.title, it.detail) }
        } + copy.stopPractice
    }

    /** All values, for the completeness and phrasing tests. */
    internal fun allValues(): Map<Pair<Key, String>, String> =
        EN_US.mapKeys { it.key to "en-US" } + ES_MX.mapKeys { it.key to "es-MX" }
}

/**
 * [ModePhrasebook] (the seam in `core/Contracts.kt`) backed by [Cues]: the object `AppGraph`
 * passes as `ModeController(…, phrasebook = CuesPhrasebook)` after `CuesPhrasebook.bind(speaker)`, so every
 * sentence the controller says follows `Speaker.locale` (FR-G6). Owner R4.
 *
 * [navigationCue] returns `null` for [NavigationCue.Arrived] on purpose: R1 runs the ARRIVAL prompt and
 * speaks the VLM's handle-side answer instead (FR-N6); `Cues.phrase` still has "you have arrived" if the
 * blindfold test asks for a confirmation first.
 */
object CuesPhrasebook : ModePhrasebook {
    /** Where the current locale comes from; [bind] points it at the live `Speaker`. Defaults to en-US. */
    @Volatile var locale: () -> Locale = { Speaker.EN_US }

    fun bind(speaker: Speaker) {
        locale = { speaker.locale.value }
    }

    override val welcome: String get() = Cues.welcome(locale())
    override val busy: String get() = Cues.busy(locale())
    override val inferenceFailed: String get() = Cues.inferenceFailed(locale())
    override val doorNotFound: String get() = Cues.doorNotFound(locale())
    override fun doorSeen(bearing: HorizontalBearing): String = Cues.doorSeen(bearing, locale())
    override val navigationUnavailable: String get() = Cues.navigationUnavailableOnThisDevice(locale())
    override val holdStill: String get() = Cues.holdStill(locale())
    override val permissionExplanation: String get() = Cues.permissionExplanation(locale())
    override val permissionDenied: String get() = Cues.permissionDenied(locale())
    override val voiceCommandsUnavailable: String get() = Cues.voiceCommandsUnavailable(locale())
    override val cameraLost: String get() = Cues.cameraLost(locale())
    override val modelNotFound: String get() = Cues.modelNotFound(locale())
    override val readingWholeView: String get() = Cues.readingWholeView(locale())
    override fun cloudDescribe(on: Boolean): String = Cues.cloudDescribe(on, locale())
    override val cloudDescribeUnavailable: String get() = Cues.cloudDescribeUnavailable(locale())
    override fun whereAmI(result: WhereAmI): String = Cues.whereAmI(result, locale())
    override fun modeAnnounced(mode: Mode): String = Cues.modeAnnouncement(mode, locale())
    override fun pageFocused(mode: Mode): String = Cues.pageFocused(mode, locale())
    override fun navigationCue(cue: NavigationCue): String? =
        if (cue == NavigationCue.Arrived) null else Cues.phrase(cue, locale())
    override fun onboarding(step: OnboardingStep): OnboardingPage = Cues.onboarding(step, locale())
    override val stopPractice: String get() = Cues.stopPractice(locale())
    override fun phoneOnboarding(step: PhoneOnboardingStep): PhoneOnboardingPage = Cues.phoneOnboarding(step, locale())
    override val phoneStopPractice: String get() = Cues.phoneStopPractice(locale())
}

/**
 * The onboarding words for one language. Pages are assembled here once, and [OnboardingPage.spoken] is built
 * from the same fields the panel shows, so nothing is said that is not on screen and the reverse.
 */
private class OnboardingCopy(
    private val welcome: Pair<String, String>,
    private val privacyTitle: String,
    private val privacyRows: List<String>,
    private val privacyFootnote: String,
    private val talk: Pair<String, String>,
    private val talkHint: String,
    private val talkHintSpoken: String,
    private val stop: Pair<String, String>,
    private val stopHint: String,
    private val sounds: Pair<String, String>,
    private val soundRows: List<OnboardingItem>,
    private val safetyTitle: String,
    private val productSentence: String,
    private val caneReminder: String,
    private val allSet: Pair<String, String>,
    private val modeRows: List<OnboardingItem>,
    private val allSetSpokenTail: String,
    val stopPractice: String,
) {
    fun page(step: OnboardingStep): OnboardingPage = when (step) {
        OnboardingStep.WELCOME -> OnboardingPage(
            step = step, title = welcome.first, body = welcome.second,
            spoken = "${welcome.first} ${welcome.second}",
        )
        OnboardingStep.PRIVACY -> OnboardingPage(
            step = step, title = privacyTitle, items = privacyRows.map { OnboardingItem(it) },
            footnote = privacyFootnote,
            spoken = (listOf(privacyTitle) + privacyRows + privacyFootnote).joinToString(" "),
        )
        OnboardingStep.TALK -> OnboardingPage(
            step = step, title = talk.first, body = talk.second, hint = talkHint,
            spoken = "${talk.first} ${talk.second} $talkHintSpoken",
        )
        OnboardingStep.STOP -> OnboardingPage(
            step = step, title = stop.first, body = stop.second, hint = stopHint,
            spoken = "${stop.first} ${stop.second}",
        )
        OnboardingStep.SOUNDS -> OnboardingPage(
            step = step, title = sounds.first, body = sounds.second, items = soundRows,
            spoken = "${sounds.first} ${sounds.second}",
        )
        OnboardingStep.SAFETY -> OnboardingPage(
            step = step, title = safetyTitle, body = productSentence, hint = caneReminder,
            spoken = "$safetyTitle $productSentence $caneReminder",
        )
        OnboardingStep.ALL_SET -> OnboardingPage(
            step = step, title = allSet.first, body = allSet.second, items = modeRows,
            spoken = "${allSet.first} ${allSet.second} $allSetSpokenTail",
        )
    }

    companion object {
        val EN_US = OnboardingCopy(
            welcome = "Hi, I’m Vysion." to
                "I help you read, understand, and move through a space. Everything runs on this headset.",
            privacyTitle = "Everything stays on this headset.",
            privacyRows = listOf(
                "Nothing is uploaded unless you say “cloud on”.",
                "Camera images are never saved.",
                "You can stop me any time.",
            ),
            privacyFootnote = "Next, Android asks to use the camera and microphone.",
            talk = "Talk to me anytime." to "Touch your thumb to your middle finger and hold while you speak. Let go to send. " +
                    "Thumb to ring finger moves to the next page.",
            talkHint = "Try it now: say “read this”",
            talkHintSpoken = "Try it now: say read this.",
            stop = "Stop me anytime." to
                "Make the hang loose sign: thumb and little finger out, the other fingers folded. Hold it a moment and I go quiet.",
            stopHint = "Try it: I’ll start talking, you stop me",
            sounds = "Learn my sounds." to "Each sound means one thing. Look at a sound and pinch to hear it.",
            soundRows = listOf(
                OnboardingItem("Listening", "I’m hearing you"),
                OnboardingItem("Thinking", "Working on it"),
                OnboardingItem("Done", "Finished"),
                OnboardingItem("Ping", "Where the door is"),
                OnboardingItem("Warning", "Something is close"),
                OnboardingItem("Error", "Say it again"),
            ),
            safetyTitle = "I’m a helper, not a guide.",
            productSentence = "Vysion XR helps you read, understand, and move through a space. " +
                "It is not validated for unassisted travel by blind users.",
            caneReminder = "Keep using your cane or guide dog.",
            allSet = "You’re all set." to "Try one of these. You can switch any time.",
            modeRows = listOf(
                OnboardingItem("Read", "“Read this”"),
                OnboardingItem("Describe", "“What’s here?”"),
                OnboardingItem("Navigation", "“Find the door”"),
            ),
            allSetSpokenTail = "Say read this, what’s here, or find the door.",
            stopPractice = "This is a long sentence so you can practice stopping me. Make the hang loose sign, " +
                "and I go quiet right away. I keep talking until you do. I keep talking until you do.",
        )

        val ES_MX = OnboardingCopy(
            welcome = "Hola, soy Vysion." to
                "Te ayudo a leer, entender y moverte por un espacio. Todo funciona en este visor.",
            privacyTitle = "Todo se queda en este visor.",
            privacyRows = listOf(
                "No se sube nada a menos que digas “activa la nube”.",
                "Las imágenes de la cámara nunca se guardan.",
                "Puedes detenerme en cualquier momento.",
            ),
            privacyFootnote = "Después, Android te pide usar la cámara y el micrófono.",
            talk = "Háblame cuando quieras." to "Junta el pulgar con el dedo medio y mantén mientras hablas. Suelta para enviar. " +
                    "Pulgar con anular pasa a la siguiente página.",
            talkHint = "Pruébalo: di “lee esto”",
            talkHintSpoken = "Pruébalo: di lee esto.",
            stop = "Detenme cuando quieras." to
                "Extiende el pulgar y el meñique, con los otros dedos doblados. Mantenlo un momento y me callo.",
            stopHint = "Pruébalo: yo empiezo a hablar y tú me detienes",
            sounds = "Aprende mis sonidos." to "Cada sonido significa una cosa. Mira un sonido y pellizca para oírlo.",
            soundRows = listOf(
                OnboardingItem("Escuchando", "Te estoy oyendo"),
                OnboardingItem("Pensando", "Trabajando en ello"),
                OnboardingItem("Listo", "Terminado"),
                OnboardingItem("Ping", "Dónde está la puerta"),
                OnboardingItem("Advertencia", "Algo está cerca"),
                OnboardingItem("Error", "Dilo otra vez"),
            ),
            safetyTitle = "Soy una ayuda, no un guía.",
            productSentence = "Vysion XR te ayuda a leer, entender y moverte por un espacio. " +
                "No está validado para que personas ciegas viajen sin asistencia.",
            caneReminder = "Sigue usando tu bastón o tu perro guía.",
            allSet = "Todo listo." to "Prueba uno de estos. Puedes cambiar cuando quieras.",
            modeRows = listOf(
                OnboardingItem("Leer", "“Lee esto”"),
                OnboardingItem("Describir", "“¿Qué hay aquí?”"),
                OnboardingItem("Navegación", "“Busca la puerta”"),
            ),
            allSetSpokenTail = "Di lee esto, qué hay aquí, o busca la puerta.",
            stopPractice = "Esta es una frase larga para que practiques detenerme. Extiende el pulgar y el meñique, " +
                "y me callo de inmediato. Sigo hablando hasta que lo hagas. " +
                "Sigo hablando hasta que lo hagas.",
        )
    }
}

/**
 * The phone onboarding words for one language (designs "Phone v6 · End-to-end", section 1). As with [OnboardingCopy],
 * [PhoneOnboardingPage.spoken] is built from the same fields the screen shows, so nothing is said that is not on screen
 * and the reverse. The sound chips reuse the headset's six sound rows: one meaning per sound on every device.
 */
private class PhoneOnboardingCopy(
    private val welcome: Pair<String, String>,
    private val welcomeHint: String,
    private val permissions: Pair<String, String>,
    private val learnTap: Pair<String, String>,
    private val learnSwipe: Pair<String, String>,
    private val learnTalkStop: Pair<String, String>,
    private val allSet: Pair<String, String>,
    private val allSetSpokenTail: String,
    private val soundRows: List<OnboardingItem>,
    val stopPractice: String,
) {
    fun page(step: PhoneOnboardingStep): PhoneOnboardingPage = when (step) {
        PhoneOnboardingStep.WELCOME -> PhoneOnboardingPage(
            step = step, title = welcome.first, body = welcome.second, hint = welcomeHint,
            spoken = "${welcome.first} ${welcome.second} $welcomeHint.",
        )
        PhoneOnboardingStep.PERMISSIONS -> PhoneOnboardingPage(
            step = step, title = permissions.first, body = permissions.second,
            spoken = "${permissions.first} ${permissions.second}",
        )
        PhoneOnboardingStep.LEARN_TAP -> PhoneOnboardingPage(
            step = step, title = learnTap.first, body = learnTap.second,
            spoken = "${learnTap.first} ${learnTap.second}",
        )
        PhoneOnboardingStep.LEARN_SWIPE -> PhoneOnboardingPage(
            step = step, title = learnSwipe.first, body = learnSwipe.second,
            spoken = "${learnSwipe.first} ${learnSwipe.second}",
        )
        PhoneOnboardingStep.LEARN_TALK_STOP -> PhoneOnboardingPage(
            step = step, title = learnTalkStop.first, body = learnTalkStop.second,
            spoken = "${learnTalkStop.first} ${learnTalkStop.second}",
        )
        PhoneOnboardingStep.ALL_SET -> PhoneOnboardingPage(
            step = step, title = allSet.first, body = allSet.second, items = soundRows,
            spoken = "${allSet.first} ${allSet.second} $allSetSpokenTail",
        )
    }

    companion object {
        val EN_US = PhoneOnboardingCopy(
            welcome = "Hi, I’m Vysion." to "I read, describe and guide you, out loud. Everything runs on this phone.",
            welcomeHint = "Tap anywhere to start",
            permissions = "I need your camera and microphone." to
                "To see what’s in front of you and hear what you ask. Camera images are never saved. " +
                "Reading, describing and finding the door never leave this phone.",
            learnTap = "Tap anywhere to read." to "Try it now. Point at anything with words.",
            learnSwipe = "Swipe to switch." to "Read, Describe, Navigate. I say each one as you land on it.",
            learnTalkStop = "Hold to talk. Two fingers to stop." to
                "Hold anywhere and say what you need. Tap twice with two fingers to stop me talking.",
            allSet = "You’re set." to "Six sounds tell you what’s happening. Tap one to hear it.",
            allSetSpokenTail = "Point your phone and tap.",
            soundRows = listOf(
                OnboardingItem("Listening", "I’m hearing you"),
                OnboardingItem("Thinking", "Working on it"),
                OnboardingItem("Done", "Finished"),
                OnboardingItem("Ping", "Where the door is"),
                OnboardingItem("Warning", "Something is close"),
                OnboardingItem("Error", "Say it again"),
            ),
            stopPractice = "This is a long sentence so you can practice stopping me. Tap twice with two fingers, " +
                "and I go quiet right away. I keep talking until you do. I keep talking until you do.",
        )

        val ES_MX = PhoneOnboardingCopy(
            welcome = "Hola, soy Vysion." to "Leo, describo y te guío, en voz alta. Todo funciona en este teléfono.",
            welcomeHint = "Toca en cualquier lugar para empezar",
            permissions = "Necesito tu cámara y tu micrófono." to
                "Para ver lo que tienes delante y oír lo que preguntas. Las imágenes de la cámara nunca se guardan. " +
                "Leer, describir y buscar la puerta nunca salen de este teléfono.",
            learnTap = "Toca en cualquier lugar para leer." to "Pruébalo ahora. Apunta a algo con palabras.",
            learnSwipe = "Desliza para cambiar." to "Leer, Describir, Navegación. Digo cada uno cuando llegas a él.",
            learnTalkStop = "Mantén para hablar. Dos dedos para parar." to
                "Mantén en cualquier lugar y di lo que necesitas. Toca dos veces con dos dedos para que deje de hablar.",
            allSet = "Todo listo." to "Seis sonidos te dicen qué está pasando. Toca uno para oírlo.",
            allSetSpokenTail = "Apunta con tu teléfono y toca.",
            soundRows = listOf(
                OnboardingItem("Escuchando", "Te estoy oyendo"),
                OnboardingItem("Pensando", "Trabajando en ello"),
                OnboardingItem("Listo", "Terminado"),
                OnboardingItem("Ping", "Dónde está la puerta"),
                OnboardingItem("Advertencia", "Algo está cerca"),
                OnboardingItem("Error", "Dilo otra vez"),
            ),
            stopPractice = "Esta es una frase larga para que practiques detenerme. Toca dos veces con dos dedos, " +
                "y me callo de inmediato. Sigo hablando hasta que lo hagas. Sigo hablando hasta que lo hagas.",
        )
    }
}
