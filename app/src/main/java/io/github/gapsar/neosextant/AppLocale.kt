package io.github.gapsar.neosextant

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.gapsar.neosextant.model.SolverMode

enum class AppLocale(val displayName: String, val flag: String) {
    EN("English", "🇬🇧"),
    FR("Français", "🇫🇷"),
    ES("Español", "🇪🇸")
}

val LocalAppLocale = staticCompositionLocalOf { AppLocale.EN }

object LocaleManager {
    private const val PREF_NAME = "neosextant_locale"
    private const val KEY_LOCALE = "app_locale"

    fun getLocale(context: Context): AppLocale {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return try {
            AppLocale.valueOf(prefs.getString(KEY_LOCALE, AppLocale.EN.name) ?: AppLocale.EN.name)
        } catch (_: Exception) { AppLocale.EN }
    }

    fun setLocale(context: Context, locale: AppLocale) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LOCALE, locale.name).apply()
    }

    fun hasChosenLanguage(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).contains(KEY_LOCALE)
    }
}

/** Centralized string registry. Every user-facing string in the app. */
@Suppress("PropertyName")
object S {

    @Composable
    private fun s(en: String, fr: String, es: String): String = when (LocalAppLocale.current) {
        AppLocale.EN -> en; AppLocale.FR -> fr; AppLocale.ES -> es
    }

    /** Non-composable variant for Workers / notifications. */
    fun get(locale: AppLocale, en: String, fr: String, es: String): String = when (locale) {
        AppLocale.EN -> en; AppLocale.FR -> fr; AppLocale.ES -> es
    }

    // ─── General ────────────────────────────────────────────────────────────────
    val back: String @Composable get() = s("Back", "Retour", "Atrás")
    val next: String @Composable get() = s("Next", "Suivant", "Siguiente")
    val dismiss: String @Composable get() = s("Dismiss", "Fermer", "Cerrar")
    val empty: String @Composable get() = s("Empty", "Vide", "Vacío")
    val close: String @Composable get() = s("Close", "Fermer", "Cerrar")

    // ─── Language Selection ─────────────────────────────────────────────────────
    val chooseLanguage: String @Composable get() = s(
        "Choose your language", "Choisissez votre langue", "Elige tu idioma"
    )

    // ─── Tutorial — Star Wars Crawl ─────────────────────────────────────────────
    val crawlWelcome: String @Composable get() = s(
        "Welcome to NeoSextant",
        "Bienvenue sur NeoSextant",
        "Bienvenido a NeoSextant"
    )
    val crawlIntro: String @Composable get() = s(
        "The purpose of this app is to allow you to determine your position on the globe thanks to a centuries-old technique:",
        "Cette application a pour but de vous permettre de déterminer votre position sur le globe grâce à une technique vieille de plusieurs siècles :",
        "El propósito de esta aplicación es permitirte determinar tu posición en el globo gracias a una técnica con varios siglos de antigüedad:"
    )
    val crawlAstronav: String @Composable get() = s(
        "Astronavigation", "L’Astronavigation", "La Astronavegación"
    )
    val crawlNoSextant: String @Composable get() = s(
        "But here, no need for a complex sextant, an up-to-date timepiece, a visible horizon, or doing a whole bunch of calculations, just take a few photos of the stars and presto!",
        "Mais ici, pas besoin de sextant complexe, de garde-temps à jour, d'un horizon visible ou encore d'effectuer tout un tas de calculs, prenez simplement quelques photos des étoiles et hop !",
        "Pero aquí, no hay necesidad de un sextante complejo, de un cronómetro actualizado, de un horizonte visible o de realizar un montón de cálculos, ¡simplemente toma algunas fotos de las estrellas y listo!"
    )
    val crawlPosition: String @Composable get() = s(
        "You get your position.",
        "Vous obtenez votre position.",
        "Obtienes tu posición."
    )
    val tapToSkip: String @Composable get() = s(
        "Tap to skip", "Appuyez pour passer", "Toca para saltar"
    )
    val tutorialTransition: String @Composable get() = s(
        "After this brief presentation, let me give you a quick tour of the app and explain how to use it.",
        "Après cette brève présentation, laissez-moi vous faire un petit tour de l’application et vous expliquer comment l’utiliser.",
        "Después de esta breve presentación, déjame darte un pequeño recorrido por la aplicación y explicarte cómo usarla."
    )
    val letsGo: String @Composable get() = s("Let's go!", "C’est parti !", "¡Vamos!")
    val skipTutorial: String @Composable get() = s(
        "Skip tutorial", "Passer le didacticiel", "Saltar el tutorial"
    )

    // ─── Tutorial Overlay — Step Labels ─────────────────────────────────────────
    val stepSettings: String @Composable get() = s(
        "SETTINGS PAGE", "PAGE PARAMÈTRES", "PÁGINA DE AJUSTES"
    )
    val stepSolver: String @Composable get() = s(
        "SOLVER MODE", "MODE DE RÉSOLUTION", "MODO DE RESOLUCIÓN"
    )
    val stepCalOverview: String @Composable get() = s(
        "CALIBRATION — OVERVIEW", "CALIBRATION — APERÇU", "CALIBRACIÓN — RESUMEN"
    )
    val stepCalHorizon: String @Composable get() = s(
        "CALIBRATION — HORIZON", "CALIBRATION — HORIZON", "CALIBRACIÓN — HORIZONTE"
    )
    val stepCalStar: String @Composable get() = s(
        "CALIBRATION — 1-SHOT (STAR)", "CALIBRATION — 1-SHOT (ÉTOILE)", "CALIBRACIÓN — 1-SHOT (ESTRELLA)"
    )
    val stepCalSensors: String @Composable get() = s(
        "CALIBRATION — SENSORS", "CALIBRATION — CAPTEURS", "CALIBRACIÓN — SENSORES"
    )
    val stepPhotos: String @Composable get() = s(
        "TAKING PHOTOS", "PRISE DE PHOTOS", "TOMA DE FOTOS"
    )
    val stepResults: String @Composable get() = s(
        "PHOTO RESULTS", "RÉSULTATS PHOTO", "RESULTADOS DE LAS FOTOS"
    )
    val stepMapIterative: String @Composable get() = s(
        "MAP PAGE (ITERATIVE)", "PAGE CARTE (ITÉRATIF)", "PÁGINA DE MAPA (ITERATIVO)"
    )
    val stepMapLop: String @Composable get() = s(
        "MAP PAGE (LOP)", "PAGE CARTE (LOP)", "PÁGINA DE MAPA (LOP)"
    )

    // ─── Tutorial Overlay — Narration Texts ─────────────────────────────────────
    val narrationSettings: String @Composable get() = s(
        "Welcome to the app! You are currently on the Settings page, this is where you will be able to enter the information necessary for your positioning. For the vessel information, it only needs to be filled in if you are moving, of course. However, for the weather conditions, they must be filled in wherever you are!",
        "Bienvenue dans l’application ! Vous voici actuellement dans la page Paramètres, c'est ici que vous allez pouvoir entrer les informatiosn nécessaires a votre positionnement. Pour les informations navires, elles ne sont a remplir que si vous êtes en mouvement bien sur. " +
                "Par contre pour les Conditions météo, elles sont à remplir ou que vous soyez !",
        "¡Bienvenido a la aplicación! Actualmente te encuentras en la página de Ajustes, aquí es donde podrás ingresar la información necesaria para tu posicionamiento. La información del barco, por supuesto, solo debe completarse si estás en movimiento. " +
                "Sin embargo, para las Condiciones meteorológicas, ¡deben completarse dondequiera que estés!"
    )
    val narrationSolver: String @Composable get() = s(
        "Here, you choose the solver mode. 'Iterative' triangulates your position from multiple images. 'LOP' draws classical lines of position (requires an estimated position). The new '1-Shot' mode calculates an instant fix from a single image using the 3D gravity vector.",
        "Ici, vous choisissez le mode de résolution. « Itératif » triangule votre position à partir de plusieurs images. « LOP » trace des droites de hauteur classiques (nécessite une position estimée). Le nouveau mode « 1-Shot » calcule une position instantanée depuis une seule image grâce au vecteur gravité.",
        "Aquí, eliges el modo de resolución. 'Iterativo' triangula tu posición desde varias imágenes. 'LOP' traza líneas de posición clásicas (requiere posición estimada). El nuevo modo '1-Shot' calcula una posición instantánea desde una sola imagen usando el vector gravedad."
    )
    val narrationCalOverview: String @Composable get() = s(
        "Calibration is essential for accurate positioning. Your phone's accelerometer has manufacturing biases, and there is always a small misalignment between the camera lens and the sensor chip. Without calibration, your altitude measurements can be off by several degrees — which translates to errors of hundreds of nautical miles!\n\nThere are 3 types of calibration, each correcting a different source of error. Let's go through them.",
        "La calibration est essentielle pour un positionnement précis. L'accéléromètre de votre téléphone présente des biais de fabrication, et il y a toujours un léger décalage entre l'objectif de la caméra et le capteur. Sans calibration, vos mesures d'altitude peuvent dévier de plusieurs degrés — soit des erreurs de centaines de milles nautiques !\n\nIl existe 3 types de calibration, chacun corrigeant une source d'erreur différente. Passons-les en revue.",
        "La calibración es esencial para un posicionamiento preciso. El acelerómetro de tu teléfono tiene sesgos de fabricación, y siempre hay una pequeña desalineación entre el lente de la cámara y el chip sensor. Sin calibración, tus mediciones de altitud pueden desviarse varios grados — ¡lo que se traduce en errores de cientos de millas náuticas!\n\nHay 3 tipos de calibración, cada uno corrigiendo una fuente de error diferente. Repasémoslos."
    )
    val narrationCalHorizon: String @Composable get() = s(
        "Horizon Calibration (Iterative & LOP modes)\n\nThis corrects the angular offset between your camera and the accelerometer. Point your phone at the sea horizon, enter your height of eye (meters above sea level), and press 'Set Horizon'. The app applies a dip correction (≈1.76' × √height) and records the difference as your pitch offset.\n\nYou will need to repeat this 3 times for a multi-sample average, improving reliability. The final averaged offset is saved and applied to all future measurements.",
        "Calibration de l'Horizon (modes Itératif & LOP)\n\nCeci corrige le décalage angulaire entre votre caméra et l'accéléromètre. Pointez votre téléphone vers l'horizon marin, entrez votre hauteur de l'œil (mètres au-dessus du niveau de la mer), puis appuyez sur « Définir l'horizon ». L'application applique une correction de dépression (≈1.76' × √hauteur) et enregistre la différence comme décalage de tangage.\n\nVous devrez répéter cette opération 3 fois pour obtenir une moyenne multi-échantillons, améliorant la fiabilité. Le décalage moyen final est sauvegardé et appliqué à toutes les mesures futures.",
        "Calibración del Horizonte (modos Iterativo & LOP)\n\nEsto corrige el desfase angular entre tu cámara y el acelerómetro. Apunta tu teléfono al horizonte marino, ingresa tu altura del ojo (metros sobre el nivel del mar) y presiona 'Definir horizonte'. La app aplica una corrección de depresión (≈1.76' × √altura) y registra la diferencia como desfase de inclinación.\n\nDeberás repetir esto 3 veces para un promedio multi-muestra, mejorando la fiabilidad. El desfase promedio final se guarda y aplica a todas las mediciones futuras."
    )
    val narrationCalStar: String @Composable get() = s(
        "Star Calibration (1-Shot mode)\n\nIn 1-Shot mode, the calibration screen changes completely. Instead of using the horizon, you calibrate using the stars themselves from a known position.\n\nYou must know your exact coordinates (from a nautical chart, a landmark, or a previous GPS fix). Enter them, point your phone at the night sky on a tripod, and press 'Calibrate'. The app plate-solves the star field and compares the camera orientation with the gravity vector to compute precise pitch and roll offsets.\n\nThis is the most accurate calibration method but requires a known position.",
        "Calibration par Étoile (mode 1-Shot)\n\nEn mode 1-Shot, l'écran de calibration change complètement. Au lieu d'utiliser l'horizon, vous calibrez à l'aide des étoiles elles-mêmes depuis une position connue.\n\nVous devez connaître vos coordonnées exactes (à partir d'une carte marine, d'un repère ou d'un relevé GPS précédent). Entrez-les, pointez votre téléphone vers le ciel étoilé sur un trépied, puis appuyez sur « Calibrer ». L'app résout le champ stellaire et compare l'orientation de la caméra avec le vecteur gravité pour calculer les décalages précis de tangage et de roulis.\n\nC'est la méthode de calibration la plus précise, mais elle nécessite une position connue.",
        "Calibración por Estrellas (modo 1-Shot)\n\nEn modo 1-Shot, la pantalla de calibración cambia completamente. En lugar de usar el horizonte, calibras usando las propias estrellas desde una posición conocida.\n\nDebes conocer tus coordenadas exactas (de una carta náutica, un punto de referencia o una posición GPS anterior). Ingrésalas, apunta tu teléfono al cielo nocturno en un trípode y presiona 'Calibrar'. La app resuelve el campo estelar y compara la orientación de la cámara con el vector de gravedad para calcular desfases precisos de inclinación y balanceo.\n\nEste es el método de calibración más preciso, pero requiere una posición conocida."
    )
    val narrationCalSensors: String @Composable get() = s(
        "Sensor Calibration (All modes)\n\nThis is the sphere-fit (elliptical regression) calibration. Every accelerometer has axis-specific biases — readings form an ellipsoid instead of a perfect sphere. This procedure measures gravity in 8 different orientations of your phone to fit an ellipsoid model and compute correction factors.\n\nClick 'Calibrate Sensors', then place your phone in each orientation as instructed. Hold it perfectly still — it will vibrate when a measurement is captured, signaling you to move to the next position. This calibration is important for ALL modes and should be done periodically (every ~10 days).",
        "Calibration des Capteurs (Tous les modes)\n\nC'est la calibration par ajustement sphérique (régression elliptique). Chaque accéléromètre a des biais spécifiques aux axes — les lectures forment un ellipsoïde au lieu d'une sphère parfaite. Cette procédure mesure la gravité sur 8 orientations de votre téléphone pour ajuster un modèle ellipsoïdal et calculer des facteurs de correction.\n\nCliquez sur « Calibrer les capteurs », puis placez votre téléphone dans chaque orientation comme indiqué. Maintenez-le parfaitement immobile — il vibrera lorsqu'une mesure sera capturée, vous indiquant de passer à la position suivante. Cette calibration est importante pour TOUS les modes et doit être faite périodiquement (environ tous les 10 jours).",
        "Calibración de Sensores (Todos los modos)\n\nEsta es la calibración por ajuste esférico (regresión elíptica). Cada acelerómetro tiene sesgos específicos por eje — las lecturas forman un elipsoide en lugar de una esfera perfecta. Este procedimiento mide la gravedad en 8 orientaciones de tu teléfono para ajustar un modelo elipsoidal y calcular factores de corrección.\n\nHaz clic en 'Calibrar los sensores', luego coloca tu teléfono en cada orientación según las instrucciones. Mantenlo perfectamente quieto — vibrará cuando se capture una medición, indicándote que cambies a la siguiente posición. Esta calibración es importante para TODOS los modos y debe hacerse periódicamente (aproximadamente cada 10 días)."
    )
    val narrationPhotos: String @Composable get() = s(
        "Welcome to the photo-taking section. Its use is simple, just point your phone towards the stars. Take a photo with the bottom button. Note that your phone MUST be as still as possible (a small animation will confirm that the photo is being taken).",
        "Bienvenue dans la partie prise de photo. L'utilisation est simple, pointez simplement votre téléphone vers les étoiles. " +
                "Prenez une photo avec le bouton du bas. Notez que votre téléphone DOIT être aussi immobile que possible " +
                "(une petite animation vous confirmera que la photo est en train d’être prise).",
        "Bienvenido a la sección de toma de fotos. El uso es simple, simplemente apunta tu teléfono hacia las estrellas. " +
                "Toma una foto con el botón inferior. Ten en cuenta que tu teléfono DEBE estar lo más quieto posible " +
                "(una pequeña animación te confirmará que la foto se está tomando)."
    )
    val narrationResults: String @Composable get() = s(
        "Once the photo is taken, a thumbnail will appear in the panel visible here and you will be informed of the progress of the analysis. Processing can take up to 15 seconds. A position will be available after one or three validated images, depending on the chosen mode.",
        "Une fois la photo prise, une miniature apparaîtra dans le panneau visible ici et vous serez mis au courant du progrès de l'analyse. Le traitement peut prendre jusqu’à 15 secondes. " +
                "Une position sera disponible après une ou trois images validées, selon le mode choisi.",
        "Una vez tomada la foto, aparecerá una miniatura en el panel visible aquí y se te informará del progreso del análisis. El procesamiento puede tardar hasta 15 segundos. " +
                "Una posición estará disponible después de una o tres imágenes validadas, según el modo elegido."
    )
    val narrationMapIterative: String @Composable get() = s(
        "After the capture and resolution of your images, you will be automatically redirected to the Map page! If you chose the iterative process, it works by starting from the 0,0 coordinates and adding an offset at each iteration until it matches your measurements. And there you go! Your position is determined.",
        "Après la capture et la résolution de vos images, vous serez redirigé automatiquement vers la page Carte ! " +
                "Si vous avez choisi le processus itératif, Il fonctionne en partant des coordonées 0,0  " +
                "et en ajoutant un décalage a chaque itération jusqu'à correspondre à vos mesures. Et voilà ! Votre position est déterminée.",
        "¡Después de la captura y resolución de tus imágenes, serás redirigido automáticamente a la página del Mapa! " +
                "Si elegiste el proceso iterativo, funciona partiendo de las coordenadas 0,0 " +
                "y agregando un desfase en cada iteración hasta coincidir con tus mediciones. ¡Y listo! Tu posición está determinada."
    )
    val narrationMapLop: String @Composable get() = s(
        "If you switch to LOP (Line of Position) mode in the Settings, the Map displays the characteristic lines of position of Marcq Saint-Hilaire. You will see 3 distinct colored lines forming a triangle around your estimated position, with detailed intercept calculations available!",
        "Si vous passez en mode LOP (Droite de Hauteurs) dans les Paramètres, la Carte affiche " +
                "les droites de hauteur caractéristiques de Marcq Saint-Hilaire. Vous verrez 3 lignes de couleurs distinctes " +
                "formant un triangle autour de votre position estimée, avec les calculs d’intercept détaillés disponibles !",
        "Si cambias al modo LOP (Línea de Posición) en los Ajustes, el Mapa muestra " +
                "las líneas de posición características de Marcq Saint-Hilaire. ¡Verás 3 líneas de colores distintos " +
                "formando un triángulo alrededor de tu posición estimada, con los cálculos de intercepto detallados disponibles!"
    )
    val endTour: String @Composable get() = s("End Tour", "Fin de la visite", "Fin del recorrido")

    // ─── Settings Screen ────────────────────────────────────────────────────────
    val settings: String @Composable get() = s("Settings", "Paramètres", "Ajustes")
    val timeSyncNever: String @Composable get() = s("Time sync: Never", "Synchro heure : Jamais", "Sincro hora: Nunca")
    @Composable fun timeSyncStatus(hours: Long, minutes: Long, reliable: Boolean): String {
        val age = if (hours > 0) s("${hours}h ${minutes}m ago", "il y a ${hours}h ${minutes}m", "hace ${hours}h ${minutes}m")
                 else s("${minutes}m ago", "il y a ${minutes}m", "hace ${minutes}m")
        val rel = if (reliable) "" else s(" (rebooted)", " (redémarré)", " (reiniciado)")
        return s("Time sync: ", "Synchro heure : ", "Sincro hora: ") + age + rel
    }
    val sensorCalibration: String @Composable get() = s("Sensor Calibration", "Calibration des capteurs", "Calibración de los sensores")
    val calibrateSensors: String @Composable get() = s("Calibrate Sensors", "Calibrer les capteurs", "Calibrar los sensores")
    val viewPositionHistory: String @Composable get() = s("View Position History", "Historique des positions", "Historial de posiciones")
    val replayTutorial: String @Composable get() = s("Replay Tutorial", "Rejouer le didacticiel", "Repetir el tutorial")
    val changeLanguage: String @Composable get() = s("Change Language", "Changer de langue", "Cambiar de idioma")
    val systemParameters: String @Composable get() = s("System Parameters", "Paramètres système", "Parámetros del sistema")
    val resetSensorCal: String @Composable get() = s("Reset Sensor Cal", "Réinitialiser capteurs", "Restablecer sensores")
    val resetHorizon: String @Composable get() = s("Reset Horizon", "Réinitialiser horizon", "Restablecer horizonte")
    val resetConfirmTitle: String @Composable get() = s("Reset to Defaults?", "Réinitialiser ?", "¿Restablecer?")
    val resetConfirmText: String @Composable get() = s("Are you sure you want to clear this calibration data?", "Voulez-vous vraiment effacer ces données ?", "¿Seguro que quieres borrar estos datos?")
    val confirm: String @Composable get() = s("Confirm", "Confirmer", "Confirmar")
    val cancel: String @Composable get() = s("Cancel", "Annuler", "Cancelar")
    val redTintMode: String @Composable get() = s("Red Tint Mode (Night Vision)", "Mode Teinte Rouge (Vision Nocturne)", "Modo Tinte Rojo (Visión Nocturna)")

    // ─── Help ─────────────────────────────────────────────────────────────
    val helpTitle: String @Composable get() = s("Observation Guide", "Guide d'observation", "Guía de observación")
    val guideAzimuthTitle: String @Composable get() = s("Spread them out", "Répartissez-les", "Distribúyelas")
    val guideAzimuthText: String @Composable get() = s("For the best position fix, capture stars in different directions (e.g., North, South-East, West) to reduce geometric bias.", "Pour de meilleurs résultats, capturez des étoiles dans différentes directions (Nord, Sud-Est, Ouest) pour réduire le biais géométrique.", "Para mejores resultados, captura estrellas en distintas direcciones para reducir el sesgo.")
    val guideHorizonTitle: String @Composable get() = s("Horizon Calibration", "Calibration de l'horizon", "Calibración del horizonte")
    val guideHorizonText: String @Composable get() = s("Your camera lens and internal sensors might not align perfectly. Use the Calibration screen to set a baseline offset using the true sea horizon.", "L'objectif de votre caméra et les capteurs internes peuvent ne pas s'aligner parfaitement. Utilisez l'écran de calibration pour corriger l'horizon.", "El lente de tu cámara y los sensores internos pueden no estar alineados. Usa la pantalla de calibración para corregir el horizonte.")
    val guideSteadyTitle: String @Composable get() = s("Keep it steady", "Restez stable", "Mantén la estabilidad")
    val guideSteadyText: String @Composable get() = s("Motion blur is the enemy of star detection. Keep the phone as steady as possible during the exposure.", "Le flou de mouvement empêche la détection des étoiles. Gardez le téléphone aussi stable que possible.", "El desenfoque de movimiento impide la detección. Mantén el teléfono lo más estable posible.")
    val helpSolverModeTitle: String @Composable get() = s("Solver Mode", "Mode de Résolution", "Modo de Resolución")
    val helpSolverModeText: String @Composable get() = s("• LOP Mode: Traditional Lines of Position. Strict 3-star limit. Best for verifying the classical method.\n• Iterative Mode: Advanced statistical solver. Allows 3+ stars. Provides internal precision estimate.\n• 1-Shot Mode: Instant position from a single image using the precise gravity 3D vector.", "• Mode LOP : Droites de hauteur traditionnelles. Limite stricte de 3 étoiles.\n• Mode Itératif : Solveur statistique avancé, 3 étoiles et plus.\n• Mode 1-Shot : Position instantanée depuis une seule image grâce au vecteur gravité 3D.", "• LOP: Líneas de posición clásicas.\n• Iterativo: Solucionador avanzado estadístico.\n• 1-Shot: Posición instantánea desde una sola imagen utilizando el vector gravedad 3D.")

    val vesselInfo: String @Composable get() = s("Vessel Information", "Informations du navire", "Información del barco")
    val shipSpeed: String @Composable get() = s("Ship's Speed (knots)", "Vitesse du navire (nœuds)", "Velocidad del barco (nudos)")
    val speedNegative: String @Composable get() = s("Speed cannot be negative", "La vitesse ne peut pas être négative", "La velocidad no puede ser negativa")
    val shipHeading: String @Composable get() = s("Ship's Heading (degrees true)", "Cap du navire (degrés vrais)", "Rumbo del barco (grados verdaderos)")
    val headingRange: String @Composable get() = s("Heading must be 0-360", "Le cap doit être compris entre 0 et 360", "El rumbo debe estar entre 0 y 360")
    val heightOfEye: String @Composable get() = s("Height of Eye (m)", "Hauteur de l’œil (m)", "Altura del ojo (m)")
    val heightMin: String @Composable get() = s("Height must be at least -500m", "La hauteur doit être d’au moins -500m", "La altura debe ser de al menos -500 m")
    val weatherConditions: String @Composable get() = s("Weather Conditions", "Conditions météorologiques", "Condiciones meteorológicas")
    val temperatureLabel: String @Composable get() = s("Temperature (°C)", "Température (°C)", "Temperatura (°C)")
    val tempAbsZero: String @Composable get() = s("Temperature cannot be below absolute zero", "La température ne peut pas être inférieure au zéro absolu", "La temperatura no puede ser inferior al cero absoluto")
    val pressureLabel: String @Composable get() = s("Pressure (hPa)", "Pression (hPa)", "Presión (hPa)")
    val pressurePositive: String @Composable get() = s("Pressure must be positive", "La pression doit être positive", "La presión debe ser positiva")
    val solverMode: String @Composable get() = s("Solver Mode", "Mode de résolution", "Modo de resolución")
    val iterativeDesc: String @Composable get() = s(
        "Iterative: Automatically calculates the position without an estimated position (0°, 0°)",
        "Itératif : Calcule automatiquement la position sans position estimée (0°, 0°)",
        "Iterativo: Calcula automáticamente la posición sin posición estimada (0°, 0°)"
    )
    val lopDesc: String @Composable get() = s(
        "LOP: Displays lines of position on the map near the estimated position",
        "LOP : Affiche les lignes de position sur la carte près de la position estimée",
        "LOP: Muestra las líneas de posición en el mapa cerca de la posición estimada"
    )
    val oneShotDesc: String @Composable get() = s(
        "1-Shot: Instantly fixes your position using a single image, analyzing its precise camera orientation vs earth's gravity.",
        "1-Shot : Fixe instantanément votre position à l'aide d'une seule image, en analysant son orientation vs la gravité de la Terre.",
        "1-Shot: Fija instantáneamente tu posición usando una sola imagen, analizando la orientación respecto a la gravedad."
    )
    val estimatedPosition: String @Composable get() = s("Estimated Position", "Position estimée", "Posición estimada")
    val latitudeLabel: String @Composable get() = s("Latitude (°N)", "Latitude (°N)", "Latitud (°N)")
    val latitudeRange: String @Composable get() = s("Latitude must be between -90 and 90", "La latitude doit être comprise entre -90 et 90", "La latitud debe estar entre -90 y 90")
    val longitudeLabel: String @Composable get() = s("Longitude (°E)", "Longitude (°E)", "Longitud (°E)")
    val longitudeRange: String @Composable get() = s("Longitude must be between -180 and 180", "La longitude doit être comprise entre -180 et 180", "La longitud debe estar entre -180 y 180")
    @Composable fun solverModeName(mode: SolverMode): String = when (mode) {
        SolverMode.ITERATIVE -> s("Iterative", "Itératif", "Iterativo")
        SolverMode.LOP -> "LOP"
        SolverMode.ONE_SHOT -> "1-Shot"
    }

    // ─── Camera View ────────────────────────────────────────────────────────────
    val capturingHoldStill: String @Composable get() = s("Capturing — hold still", "Capture — restez immobile", "Captura — quédate quieto")
    val selectImage: String @Composable get() = s("Select an image to see details", "Sélectionnez une image pour voir les détails", "Selecciona una imagen para ver los detalles")
    val navigationFailed: String @Composable get() = s("Navigation Failed", "Navigation échouée", "Navegación fallida")
    val takePicture: String @Composable get() = s("Take picture", "Prendre une photo", "Tomar una foto")
    val goToMap: String @Composable get() = s("Go to Map", "Aller à la carte", "Ir al mapa")
    val zoomOutToWorld: String @Composable get() = s("Zoom out to World", "Dézoomer sur le Monde", "Alejar al mundo")

    // ─── Map Screen ─────────────────────────────────────────────────────────────
    val mapResult: String @Composable get() = s("Position Fix", "Position fixée", "Posición fijada")
    val positionDetails: String @Composable get() = s("Position Details", "Détails de la position", "Detalles de la posición")
    val computedPosition: String @Composable get() = s("Computed Position:", "Position calculée :", "Posición calculada:")
    val latLonOffset: String @Composable get() = s("Lat/Lon Offset:", "Décalage Lat/Long :", "Desfase Lat/Lon:")
    val distanceOffset: String @Composable get() = s("Distance Offset:", "Décalage de distance :", "Desfase de distancia:")
    val viewDetailedCalc: String @Composable get() = s("View Detailed Calculations", "Voir les calculs détaillés", "Ver los cálculos detallados")
    val lopDetailedCalc: String @Composable get() = s("LOP Detailed Calculations", "Calculs détaillés LOP", "Cálculos detallados LOP")
    @Composable fun observation(n: Int): String = s("Observation $n", "Observation $n", "Observación $n")
    val rightAscension: String @Composable get() = s("Right Ascension (RA):", "Ascension droite (RA) :", "Ascensión recta (AR):")
    val declinationLabel: String @Composable get() = s("Declination (Dec):", "Déclinaison (Dec) :", "Declinación (Dec):")
    val computedAlt: String @Composable get() = s("Computed Alt (Hc):", "Hauteur calculée (Hc) :", "Altura calculada (Hc):")
    val observedAlt: String @Composable get() = s("Observed Alt (Ho):", "Hauteur observée (Ho) :", "Altura observada (Ho):")
    val intercept: String @Composable get() = s("Intercept:", "Intercept :", "Intercepto:")
    val azimuthLabel: String @Composable get() = s("Azimuth (Zn):", "Azimut (Zn) :", "Azimut (Zn):")
    val estimatedPositionMarker: String @Composable get() = s("Estimated Position", "Position estimée", "Posición estimada")
    val computedPositionMarker: String @Composable get() = s("Computed Position", "Position calculée", "Posición calculada")

    // ─── Calibration Screen ─────────────────────────────────────────────────────
    val horizonCalibration: String @Composable get() = s("Horizon Calibration", "Calibration de l’horizon", "Calibración del horizonte")
    val alignHorizon: String @Composable get() = s("Align the Red Line with the Horizon", "Alignez la ligne rouge avec l’horizon", "Alinea la línea roja con el horizonte")
    val sensorPitchFmt: String @Composable get() = s("Inclination: %.2f°", "Inclinaison : %.2f°", "Inclinación: %.2f°")
    val currentOffsetFmt: String @Composable get() = s("Current Offset: %.2f°", "Décalage actuel : %.2f°", "Desfase actual: %.2f°")
    val setHorizon: String @Composable get() = s("SET HORIZON", "DÉFINIR L’HORIZON", "DEFINIR HORIZONTE")
    val calibrateSensorsUpper: String @Composable get() = s("CALIBRATE SENSORS", "CALIBRER LES CAPTEURS", "CALIBRAR LOS SENSORES")
    val sensorCalibrationTitle: String @Composable get() = s("Sensor Calibration", "Calibration des capteurs", "Calibración de los sensores")
    val stepProgressFmt: String @Composable get() = s("Step %d / %d", "Étape %d / %d", "Paso %d / %d")
    val screenUp: String @Composable get() = s("Screen UP (Z+)", "Écran vers le HAUT (Z+)", "Pantalla hacia ARRIBA (Z+)")
    val screenDown: String @Composable get() = s("Screen DOWN (Z-)", "Écran vers le BAS (Z-)", "Pantalla hacia ABAJO (Z-)")
    val topEdgeUp: String @Composable get() = s("Top Edge UP (Y+)", "Bord supérieur vers le HAUT (Y+)", "Borde superior hacia ARRIBA (Y+)")
    val topEdgeDown: String @Composable get() = s("Top Edge DOWN (Y-)", "Bord supérieur vers le BAS (Y-)", "Borde superior hacia ABAJO (Y-)")
    val rightEdgeUp: String @Composable get() = s("Right Edge UP (X+)", "Bord droit vers le HAUT (X+)", "Borde derecho hacia ARRIBA (X+)")
    val leftEdgeUp: String @Composable get() = s("Left Edge UP (X-)", "Bord gauche vers le HAUT (X-)", "Borde izquierdo hacia ARRIBA (X-)")
    val tiltedForward45: String @Composable get() = s("Tilted 45° Forward", "Incliné 45° vers l'avant", "Inclinado 45° hacia adelante")
    val tiltedRight45: String @Composable get() = s("Tilted 45° Right", "Incliné 45° vers la droite", "Inclinado 45° hacia la derecha")
    val sphereSteps: List<String> @Composable get() = listOf(screenUp, screenDown, topEdgeUp, topEdgeDown, rightEdgeUp, leftEdgeUp, tiltedForward45, tiltedRight45)
    val changePositionAlert: String @Composable get() = s("Change Position!", "Changez de position !", "¡Cambia de posición!")
    val holdStillRecording: String @Composable get() = s("Hold Still... Recording...", "Restez immobile… Enregistrement en cours…", "Quédate quieto… Grabando…")
    val keepDeviceSteady: String @Composable get() = s("Keep device steady", "Maintenez l’appareil stable", "Mantén el dispositivo estable")
    val calibrationComplete: String @Composable get() = s("Calibration Complete!", "Calibration terminée !", "¡Calibración terminada!")
    val saveConfiguration: String @Composable get() = s("SAVE", "ENREGISTRER", "GUARDAR")
    val done: String @Composable get() = s("Done", "Terminé", "Terminado")

    // ─── History Screen ─────────────────────────────────────────────────────────
    val positionHistory: String @Composable get() = s("Position History", "Historique des positions", "Historial de posiciones")
    val clearAll: String @Composable get() = s("Clear All", "Tout effacer", "Borrar todo")
    val noRecordedPositions: String @Composable get() = s("No recorded positions yet.", "Aucune position enregistrée pour l’instant.", "Ninguna posición registrada por ahora.")
    val deleteEntry: String @Composable get() = s("Delete", "Supprimer", "Eliminar")

    // ─── Image Viewer ───────────────────────────────────────────────────────────
    val showStars: String @Composable get() = s("Show Stars", "Afficher les étoiles", "Mostrar las estrellas")
    val fullScreenImage: String @Composable get() = s("Full Screen Image", "Image plein écran", "Imagen a pantalla completa")

    // ─── Image Metadata Card ────────────────────────────────────────────────────
    val imageDetails: String @Composable get() = s("Image Details", "Détails de l’image", "Detalles de la imagen")
    val fileName: String @Composable get() = s("File Name:", "Nom du fichier :", "Nombre del archivo:")
    val timestamp: String @Composable get() = s("Timestamp:", "Horodatage :", "Marca de tiempo:")
    val measuredHeight: String @Composable get() = s("Measured Height:", "Hauteur mesurée :", "Altura medida:")
    val analysis: String @Composable get() = s("Analysis", "Analyse", "Análisis")
    val statusLabel: String @Composable get() = s("Status:", "Statut :", "Estado:")
    val processing: String @Composable get() = s("Processing...", "Traitement en cours…", "Procesamiento en curso…")
    @Composable fun statusFailed(err: String?): String = s("Failed: $err", "Échec : $err", "Fallo: $err")
    val solved: String @Composable get() = s("Solved", "Résolu", "Resuelto")
    val notSolved: String @Composable get() = s("Not Solved", "Non résolu", "No resuelto")
    val lopDataLabel: String @Composable get() = s("LOP Data", "Données LOP", "Datos LOP")
    val errorLabel: String @Composable get() = s("Error:", "Erreur :", "Error:")
    val reasonLabel: String @Composable get() = s("Reason:", "Raison :", "Razón:")
    val removeImage: String @Composable get() = s("Remove Image", "Supprimer l’image", "Eliminar la imagen")
    val removeAllImages: String @Composable get() = s("Remove All Images", "Supprimer toutes les images", "Eliminar todas las imágenes")
    val raLabel: String @Composable get() = s("RA:", "AD :", "AR:")
    val decLabel: String @Composable get() = s("Dec:", "Déc :", "Dec:")
    val interceptNm: String @Composable get() = s("Intercept:", "Intercept :", "Intercepto:")
    val azimuthShort: String @Composable get() = s("Azimuth:", "Azimut :", "Azimut:")

    // ─── Image Slot ─────────────────────────────────────────────────────────────
    val capturedImage: String @Composable get() = s("Captured image", "Image capturée", "Imagen capturada")

    // ─── Notifications (non-composable) ─────────────────────────────────────────
    fun notifTitle(locale: AppLocale) = get(locale,
        "Sensor Calibration Needed",
        "Calibration des capteurs nécessaire",
        "Calibración de los sensores necesaria"
    )
    fun notifText(locale: AppLocale) = get(locale,
        "Recalibrate your sensors for better accuracy.",
        "Recalibrez vos capteurs pour une meilleure précision.",
        "Recalibra tus sensores para una mejor precisión."
    )
    fun notifLongText(locale: AppLocale) = get(locale,
        "It has been more than 10 days since your last calibration. Recalibrate for better accuracy.",
        "Cela fait plus de 10 jours depuis votre dernière calibration. Recalibrez pour une meilleure précision.",
        "Han pasado más de 10 días desde tu última calibración. Recalibra para una mejor precisión."
    )
    fun notifChannelName(locale: AppLocale) = get(locale,
        "Calibration Reminders",
        "Rappels de calibration",
        "Recordatorios de calibración"
    )
    fun notifChannelDesc(locale: AppLocale) = get(locale,
        "Reminds you when calibration is necessary",
        "Vous rappelle quand la calibration est nécessaire",
        "Te recuerda cuándo es necesaria la calibración"
    )

    // ─── Star Calibration ───
    val starCalibrationTitle: String @Composable get() = s("Star Calibration", "Calibration par Étoile", "Calibración por Estrellas")
    val starCalibrationDesc: String @Composable get() = s(
        "Place your phone stable on a tripod, point it at the night sky, enter your exact coordinates, and click Calibrate 3 times. The offsets will be averaged for better precision.",
        "Placez votre téléphone stable sur un trépied, pointez-le vers le ciel étoilé, entrez vos coordonnées exactes et cliquez sur Calibrer 3 fois. Les décalages seront moyennés pour une meilleure précision.",
        "Coloca tu teléfono estable en un trípode, apunta al cielo nocturno, ingresa tus coordenadas exactas y haz clic en Calibrar 3 veces. Los desfases se promediarán para una mayor precisión."
    )
    val starCalibration: String @Composable get() = s("Star Calibration", "Calibration par Étoile", "Calibración por Estrellas")
    val latitudeLabelCal: String @Composable get() = s("Latitude", "Latitude", "Latitud")
    val longitudeLabelCal: String @Composable get() = s("Longitude", "Longitude", "Longitud")
    val degLabel: String @Composable get() = s("Deg", "Deg", "Grados")
    val minLabel: String @Composable get() = s("Min", "Min", "Minutos")
    val calibrate: String @Composable get() = s("CALIBRATE", "CALIBRER", "CALIBRAR")
    val calibrationSuccess: String @Composable get() = s("Calibration successful!", "Calibration réussie !", "¡Calibración exitosa!")
    val calibrationFailed: String @Composable get() = s("Calibration failed", "Échec de la calibration", "Fallo de calibración")
    val calibratingProgress: String @Composable get() = s("Capturing & solving...", "Capture et résolution...", "Capturando y resolviendo...")
    val resetOneshot: String @Composable get() = s("Reset 1-Shot Cal", "Réinitialiser 1-Shot", "Restablecer 1-Shot")
}