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
        "But here, no need for a complex sextant, an up-to-date timepiece, a visible horizon, or doing a whole bunch of calculations, just take three photos of the stars and presto!",
        "Mais ici, pas besoin de sextant complexe, de garde-temps à jour, d'un horizon visible ou encore d'effectuer tout un tas de calculs, prenez simplement trois photos des étoiles et hop !",
        "Pero aquí, no hay necesidad de un sextante complejo, de un cronómetro actualizado, de un horizonte visible o de realizar un montón de cálculos, ¡simplemente toma tres fotos de las estrellas y listo!"
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
    val stepCalHorizon: String @Composable get() = s(
        "CALIBRATION — HORIZON", "CALIBRATION — HORIZON", "CALIBRACIÓN — HORIZONTE"
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
        "Here, you choose the solver mode. By default, it is on 'Iterative' to automatically triangulate your position, without the need for a prior estimated position. If you select 'LOP', the resolution will be done via lines of position (Marcq Saint-Hilaire method) and these will be displayed on the final map. You must enter an estimated position in this case.",
        "Ici, vous choisissez le mode de résolution. Par défaut, il est sur « Itératif » pour trianguler automatiquement votre position, sans besoin de position estimée au préalable. " +
                "Si vous sélectionnez « LOP », la résolution sera effectué via des droites de hauteurs (technique de Marcq Saint-Hilaire) et ces dernières seront affichées sur la carte finale. Vous devez entrer une position estimée dans ce cas.",
        "Aquí, eliges el modo de resolución. Por defecto, está en 'Iterativo' para triangular automáticamente tu posición, sin necesidad de una posición estimada previa. " +
                "Si seleccionas 'LOP', la resolución se realizará a través de líneas de posición (técnica de Marcq Saint-Hilaire) y estas se mostrarán en el mapa final. Debes ingresar una posición estimada en este caso."
    )
    val narrationCalHorizon: String @Composable get() = s(
        "Let's move on to the calibration window! Let's start with the simplest: the horizon calibration. This reduces the alignment error between the camera and the phone's accelerometer. Simply enter the height at which your phone is located (relative to sea level), align the horizon with the red line, then click on 'Set Horizon'.",
        "Passons à la fenêtre de calibration ! Commençons par la plus simple : la calibration de l’horizon. " +
                "Celle-ci réduit l’erreur d’alignement entre la caméra et l’accéléromètre du téléphone. " +
                "Entrez simplement la hauteur à laquelle votre téléphone est situé (par rapport au niveau de la mer), alignez l’horizon avec la ligne rouge, puis cliquez sur « Définir l’horizon ».",
        "¡Pasemos a la ventana de calibración! Comencemos por la más simple: la calibración del horizonte. " +
                "Esta reduce el error de alineación entre la cámara y el acelerómetro del teléfono. " +
                "Simplemente ingresa la altura a la que se encuentra tu teléfono (con respecto al nivel del mar), alinea el horizonte con la línea roja y luego haz clic en 'Definir horizonte'."
    )
    val narrationCalSensors: String @Composable get() = s(
        "The second calibration is specific to the phone's sensors — it is the elliptical regression. Click on 'Calibrate Sensors', then follow the instructions. Place your phone on each of the faces presented and hold it stable. It will vibrate when a measurement is recorded to indicate you should change faces.",
        "La seconde calibration est propre aux capteurs du téléphone — il s’agit de la régression elliptique. " +
                "Cliquez sur « Calibrer les capteurs », puis suivez les instructions. Placez votre téléphone sur chacune des faces présentées " +
                "et maintenez-le stable. Il vibrera lorsqu’une mesure sera enregistrée pour vous indiquer de changer de face.",
        "La segunda calibración es propia de los sensores del teléfono — se trata de la regresión elíptica. " +
                "Haz clic en 'Calibrar los sensores', luego sigue las instrucciones. Coloca tu teléfono en cada una de las caras presentadas " +
                "y mantenlo estable. Vibrará cuando se registre una medición para indicarte que cambies de cara."
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
        "Once the photo is taken, a thumbnail will appear in the panel visible here and you will be informed of the progress of the analysis. Processing can take up to 15 seconds. A position will only be available after three validated images.",
        "Une fois la photo prise, une miniature apparaîtra dans le panneau visible ici et vous serez mis au courant du progrès de l'analyse. Le traitement peut prendre jusqu’à 15 secondes. " +
                "Une position ne sera disponible qu’après trois images validées.",
        "Una vez tomada la foto, aparecerá una miniatura en el panel visible aquí y se te informará del progreso del análisis. El procesamiento puede tardar hasta 15 segundos. " +
                "Una posición solo estará disponible después de tres imágenes validadas."
    )
    val narrationMapIterative: String @Composable get() = s(
        "After the capture and resolution of 3 images, you will be automatically redirected to the Map page! If you chose the iterative process, it works by starting from the 0,0 coordinates and adding an offset at each iteration until it matches your measurements. And there you go! Your position is determined.",
        "Après la capture et la résolution de 3 images, vous serez redirigé automatiquement vers la page Carte ! " +
                "Si vous avez choisi le processus itératif, Il fonctionne en partant des coordonées 0,0  " +
                "et en ajoutant un décalage a chaque itération jusqu'à correspondre à vos mesures. Et voilà ! Votre position est déterminée.",
        "¡Después de la captura y resolución de 3 imágenes, serás redirigido automáticamente a la página del Mapa! " +
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
    val sensorCalibration: String @Composable get() = s("Sensor Calibration", "Calibration des capteurs", "Calibración de los sensores")
    val calibrateSensors: String @Composable get() = s("Calibrate Sensors", "Calibrer les capteurs", "Calibrar los sensores")
    val viewPositionHistory: String @Composable get() = s("View Position History", "Historique des positions", "Historial de posiciones")
    val replayTutorial: String @Composable get() = s("Replay Tutorial", "Rejouer le didacticiel", "Repetir el tutorial")
    val changeLanguage: String @Composable get() = s("Change Language", "Changer de langue", "Cambiar de idioma")
    val systemParameters: String @Composable get() = s("System Parameters", "Paramètres système", "Parámetros del sistema")
    val redTintMode: String @Composable get() = s("Red Tint Mode (Night Vision)", "Mode Teinte Rouge (Vision Nocturne)", "Modo Tinte Rojo (Visión Nocturna)")
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
    val estimatedPosition: String @Composable get() = s("Estimated Position", "Position estimée", "Posición estimada")
    val latitudeLabel: String @Composable get() = s("Latitude (°N)", "Latitude (°N)", "Latitud (°N)")
    val latitudeRange: String @Composable get() = s("Latitude must be between -90 and 90", "La latitude doit être comprise entre -90 et 90", "La latitud debe estar entre -90 y 90")
    val longitudeLabel: String @Composable get() = s("Longitude (°E)", "Longitude (°E)", "Longitud (°E)")
    val longitudeRange: String @Composable get() = s("Longitude must be between -180 and 180", "La longitude doit être comprise entre -180 et 180", "La longitud debe estar entre -180 y 180")
    @Composable fun solverModeName(mode: SolverMode): String = when (mode) {
        SolverMode.ITERATIVE -> s("Iterative", "Itératif", "Iterativo")
        SolverMode.LOP -> "LOP"
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
    val sphereSteps: List<String> @Composable get() = listOf(screenUp, screenDown, topEdgeUp, topEdgeDown, rightEdgeUp, leftEdgeUp)
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
}