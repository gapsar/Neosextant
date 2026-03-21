package com.example.basic_neosextant

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.example.basic_neosextant.model.SolverMode

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
        "In this app, the goal is to determine your position on the globe using a centuries old technique :",
        "Avec cette application, le but est de déterminer votre position sur le globe grâce à une technique vieille de plusieurs siècles :",
        "En esta aplicación, el objetivo es determinar tu posición en el globo usando una técnica milenaria :"
    )
    val crawlAstronav: String @Composable get() = s(
        "Astronavigation", "L’astronavigation", "Astronavegación"
    )
    val crawlNoSextant: String @Composable get() = s(
        "Here, no need for expensive Sextant, precise time keeping, horizon visibility and hard computations, just take three images of the stars in the sky and boom !",
        "Ici, pas besoin de sextant coûteux, de garde-temps précis, de voir l’horizon ou d’effectuer des calculs compliqués, prenez simplement trois photos des étoiles et hop !",
        "Aquí no necesitas Sextante caro, cronometraje preciso, de ver el horizonte o hacer cálculos complicados, simplemente toma tres fotos de las estrellas y ¡listo!"
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
        "After this quick and dirty presentation, let's show you a bit around and explain how to use it.",
        "Après cette brève présentation, laissez-nous vous faire un petit tour de l’application et vous expliquer comment l’utiliser.",
        "Después de esta breve presentación, déjanos mostrarte la aplicación y explicarte cómo usarla."
    )
    val letsGo: String @Composable get() = s("Let's go!", "C’est parti !", "¡Vamos!")
    val skipTutorial: String @Composable get() = s(
        "Skip tutorial", "Passer le didacticiel", "Saltar tutorial"
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
        "TAKING PHOTOS", "PRISE DE PHOTOS", "TOMAR FOTOS"
    )
    val stepResults: String @Composable get() = s(
        "PHOTO RESULTS", "RÉSULTATS PHOTO", "RESULTADOS DE FOTOS"
    )
    val stepMapIterative: String @Composable get() = s(
        "MAP PAGE (ITERATIVE)", "PAGE CARTE (ITÉRATIF)", "PÁGINA DEL MAPA (ITERATIVO)"
    )
    val stepMapLop: String @Composable get() = s(
        "MAP PAGE (LOP)", "PAGE CARTE (LOP)", "PÁGINA DEL MAPA (LOP)"
    )

    // ─── Tutorial Overlay — Narration Texts ─────────────────────────────────────
    val narrationSettings: String @Composable get() = s(
        "Welcome to the app! Here in the Settings page, you enter your vessel's Speed and Heading if you are moving. " +
                "And if you are fixed on land, just input Temperature and Pressure to compute atmospheric refraction.",
        "Bienvenue dans l’application ! Ici, dans la page Paramètres, vous entrez la vitesse et le cap de votre navire si vous êtes en mouvement. " +
                "Et si vous êtes à terre, indiquez simplement la température et la pression pour calculer la réfraction atmosphérique.",
        "¡Bienvenido en la aplicación! Aquí en la pagina de Ajustes, ingresa la Velocidad y el Rumbo de tu embarcación si estás en movimiento. " +
                "Si estás en tierra firme, simplemente ingresa la Temperatura y la Presión para calcular la refracción atmosférica."
    )
    val narrationSolver: String @Composable get() = s(
        "Down here is the Solver Mode toggle. By default, it's on 'Iterative' to automatically triangulate your location. " +
                "If you select 'LOP', you'll see the classic celestial navigation intersection lines on the map.",
        "Ici, vous choisissez le mode de résolution. Par défaut, il est sur « Itératif » pour trianguler automatiquement votre position, sans besoin de position estimée au préalable. " +
                "Si vous sélectionnez « LOP », les lignes de position (technique de Marcq Saint-Hilaire) seront affichées sur la carte finale. Vous devez entrer une position estimée dans ce cas.",
        "Aquí está el selector de Modo de resolución. Por defecto, está en 'Iterativo' para triangular automáticamente tu ubicación. " +
                "Si seleccionas 'LOP', verás las líneas de intersección clásicas de la navegación celeste en el mapa."
    )
    val narrationCalHorizon: String @Composable get() = s(
        "Now onto the calibration window! First the easy one: the horizon calibration. " +
                "This reduces alignment error between the camera and acceleration sensor. " +
                "Input your eye height, align the horizon with the red line, and click 'Set Horizon'.",
        "Passons à la fenêtre de calibration ! Commençons par la plus simple : la calibration de l’horizon. " +
                "Celle-ci réduit l’erreur d’alignement entre la caméra et l’accéléromètre du téléphone. " +
                "Entrez simplement la hauteur à laquelle votre téléphone est situé (par rapport au niveau de la mer), alignez l’horizon avec la ligne rouge, puis cliquez sur « Définir l’horizon ».",
        "¡Ahora pasemos a la ventana de calibración! Primero la más fácil: la calibración del horizonte. " +
                "Esto reduce el error de alineación entre la cámara y el sensor de aceleración. " +
                "Ingresa la altura de tus ojos, alinea el horizonte con la línea roja y haz clic en 'Definir horizonte'."
    )
    val narrationCalSensors: String @Composable get() = s(
        "The second calibration is inherent to phone IMUs — it is called Sphere Fitting. " +
                "Click 'Calibrate Sensors', then follow the instructions. Put your phone on each side " +
                "and hold it stable. It will vibrate when it reads a clean measurement.",
        "La seconde calibration est propre aux capteurs du téléphone — il s’agit de la régression elliptique. " +
                "Cliquez sur « Calibrer les capteurs », puis suivez les instructions. Placez votre téléphone sur chacune des faces présentées " +
                "et maintenez-le stable. Il vibrera lorsqu’une mesure sera enregistrée pour vous indiquer de changer de face.",
        "La segunda calibración es propia de los sensores IMU del teléfono — se llama Ajuste esférico. " +
                "Haz clic en 'Calibrar sensores', luego sigue las instrucciones. Coloca tu teléfono en cada cara " +
                "y mantenlo estable. Vibrará cuando registre una medición limpia."
    )
    val narrationPhotos: String @Composable get() = s(
        "Welcome to the sky view. To navigate, just point your phone at the stars. " +
                "Take a picture using the bottom button. Note that your phone MUST be as still as possible " +
                "(a small animation will confirm you that the picture is being taken).",
        "Bienvenue dans la partie prise de vue. L'utilisation est simple, pointez simplement votre téléphone vers les étoiles. " +
                "Prenez une photo avec le bouton du bas. Notez que votre téléphone DOIT être aussi immobile que possible " +
                "(une petite animation vous confirmera que la photo est en train d’être prise).",
        "Bienvenido a la vista del cielo. Para navegar, simplemente apunta tu teléfono hacia las estrellas. " +
                "Toma una foto con el botón de abajo. Ten en cuenta que tu teléfono DEBE estar lo más quieto posible " +
                "(una pequeña animación te confirmará que la foto se está tomando)."
    )
    val narrationResults: String @Composable get() = s(
        "Once taken, you'll see a thumbnail appear in the lifted panel. Processing takes a few seconds. " +
                "You need exactly three successfully solved images to triangulate your position.",
        "Une fois la photo prise, une miniature apparaîtra dans le panneau relevé. Le traitement peut prendre jusqu’à 15 secondes. " +
                "Une position ne sera disponible qu’après trois images validées.",
        "Una vez tomada, verás una miniatura en el panel inferior. El procesamiento toma unos segundos. " +
                "Necesitas exactamente tres imágenes resueltas con éxito para triangular tu posición."
    )
    val narrationMapIterative: String @Composable get() = s(
        "After 3 images are captured and solved, you'll be brought to the Map! " +
                "The Iterative Process mathematically converges your latitude and longitude, starting from your Estimated Position. " +
                "It uses least-squares to find the precise fix. Boom! Your location is pinpointed.",
        "Après la capture et la résolution de 3 images, vous serez redirigé vers la Carte ! " +
                "Le processus itératif converge mathématiquement vers votre latitude et longitude, en partant de votre position estimée. " +
                "Il ajuste un décalage à chaque itération jusqu’à trouver l’endroit qui correspond à vos mesures. Et voilà ! Votre position est déterminée.",
        "Después de capturar y resolver 3 imágenes, ¡serás llevado al Mapa! " +
                "El proceso Iterativo converge matemáticamente hacia tu latitud y longitud, partiendo de tu Posición estimada. " +
                "Utiliza mínimos cuadrados para encontrar la posición precisa. ¡Listo! Tu ubicación está determinada."
    )
    val narrationMapLop: String @Composable get() = s(
        "If you switch to LOP (Line of Position) mode in Settings, the Map shows " +
                "the classic celestial navigation intersection. You will see 3 distinct colored lines " +
                "crossing over your Estimated Position, with detailed intercept math available!",
        "Si vous passez en mode LOP (Ligne de Position) dans les Paramètres, la Carte affiche " +
                "les droites de hauteur caractéristiques de Marcq Saint-Hilaire. Vous verrez 3 lignes de couleurs distinctes " +
                "formant un triangle autour de votre position estimée, avec les calculs d’intercept détaillés disponibles !",
        "Si cambias al modo LOP (Línea de Posición) en Ajustes, el Mapa muestra " +
                "la intersección clásica de la navegación celeste. ¡Verás 3 líneas de colores distintos " +
                "cruzándose cerca de tu Posición estimada, con cálculos detallados de intercepto disponibles!"
    )
    val endTour: String @Composable get() = s("End Tour", "Fin de la visite", "Fin del tour")

    // ─── Settings Screen ────────────────────────────────────────────────────────
    val settings: String @Composable get() = s("Settings", "Paramètres", "Ajustes")
    val sensorCalibration: String @Composable get() = s("Sensor Calibration", "Calibration des capteurs", "Calibración de sensores")
    val calibrateSensors: String @Composable get() = s("Calibrate Sensors", "Calibrer les capteurs", "Calibrar sensores")
    val viewPositionHistory: String @Composable get() = s("View Position History", "Historique des positions", "Historial de posiciones")
    val replayTutorial: String @Composable get() = s("Replay Tutorial", "Rejouer le didacticiel", "Repetir tutorial")
    val changeLanguage: String @Composable get() = s("Change Language", "Changer de langue", "Cambiar idioma")
    val vesselInfo: String @Composable get() = s("Vessel Information", "Informations du navire", "Información del barco")
    val shipSpeed: String @Composable get() = s("Ship's Speed (knots)", "Vitesse du navire (nœuds)", "Velocidad del barco (nudos)")
    val speedNegative: String @Composable get() = s("Speed cannot be negative", "La vitesse ne peut pas être négative", "La velocidad no puede ser negativa")
    val shipHeading: String @Composable get() = s("Ship's Heading (degrees true)", "Cap du navire (degrés vrais)", "Rumbo del barco (grados verdaderos)")
    val headingRange: String @Composable get() = s("Heading must be 0-360", "Le cap doit être compris entre 0 et 360", "El rumbo debe estar entre 0 y 360")
    val heightOfEye: String @Composable get() = s("Height of Eye (m)", "Hauteur de l’œil (m)", "Altura del ojo (m)")
    val heightMin: String @Composable get() = s("Height must be at least -500m", "La hauteur doit être d’au moins -500 m", "La altura debe ser de al menos -500 m")
    val weatherConditions: String @Composable get() = s("Weather Conditions", "Conditions météorologiques", "Condiciones meteorológicas")
    val temperatureLabel: String @Composable get() = s("Temperature (°C)", "Température (°C)", "Temperatura (°C)")
    val tempAbsZero: String @Composable get() = s("Temperature cannot be below absolute zero", "La température ne peut pas être inférieure au zéro absolu", "La temperatura no puede estar por debajo del cero absoluto")
    val pressureLabel: String @Composable get() = s("Pressure (hPa)", "Pression (hPa)", "Presión (hPa)")
    val pressurePositive: String @Composable get() = s("Pressure must be positive", "La pression doit être positive", "La presión debe ser positiva")
    val solverMode: String @Composable get() = s("Solver Mode", "Mode de résolution", "Modo de resolución")
    val iterativeDesc: String @Composable get() = s(
        "Iterative: Auto-computes position without an Estimated Position (0°,0°)",
        "Itératif : Calcule automatiquement la position sans position estimée (0°, 0°)",
        "Iterativo: Calcula automáticamente la posición sin Posición (0°,0°)"
    )
    val lopDesc: String @Composable get() = s(
        "LOP: Displays Lines of Position on the map near Estimated Position",
        "LOP : Affiche les lignes de position sur la carte près de la position estimée",
        "LOP: Muestra Líneas de Posición en el mapa cerca de la Posición estimada"
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
    val capturingHoldStill: String @Composable get() = s("Capturing — hold still", "Capture — restez immobile", "Capturando — no te muevas")
    val selectImage: String @Composable get() = s("Select an image to see details", "Sélectionnez une image pour voir les détails", "Selecciona una imagen para ver detalles")
    val navigationFailed: String @Composable get() = s("Navigation Failed", "Navigation échouée", "Navegación fallida")
    val takePicture: String @Composable get() = s("Take picture", "Prendre une photo", "Tomar foto")
    val goToMap: String @Composable get() = s("Go to Map", "Aller à la carte", "Ir al mapa")

    // ─── Map Screen ─────────────────────────────────────────────────────────────
    val mapResult: String @Composable get() = s("Position Fix", "Position fixée", "Posición fijada")
    val positionDetails: String @Composable get() = s("Position Details", "Détails de la position", "Detalles de la posición")
    val computedPosition: String @Composable get() = s("Computed Position:", "Position calculée :", "Posición calculada:")
    val latLonOffset: String @Composable get() = s("Lat/Lon Offset:", "Décalage Lat/Long :", "Desfase Lat/Lon:")
    val distanceOffset: String @Composable get() = s("Distance Offset:", "Décalage de distance :", "Desfase de distancia:")
    val viewDetailedCalc: String @Composable get() = s("View Detailed Calculations", "Voir les calculs détaillés", "Ver cálculos detallados")
    val lopDetailedCalc: String @Composable get() = s("LOP Detailed Calculations", "Calculs détaillés LOP", "Cálculos detallados LOP")
    @Composable fun observation(n: Int): String = s("Observation $n", "Observation $n", "Observación $n")
    val rightAscension: String @Composable get() = s("Right Ascension (RA):", "Ascension droite (RA) :", "Ascensión recta (RA):")
    val declinationLabel: String @Composable get() = s("Declination (Dec):", "Déclinaison (Dec) :", "Declinación (Dec):")
    val computedAlt: String @Composable get() = s("Computed Alt (Hc):", "Hauteur calculée (Hc) :", "Altura calculada (Hc):")
    val observedAlt: String @Composable get() = s("Observed Alt (Ho):", "Hauteur observée (Ho) :", "Altura observada (Ho):")
    val intercept: String @Composable get() = s("Intercept:", "Intercept :", "Intercepto:")
    val azimuthLabel: String @Composable get() = s("Azimuth (Zn):", "Azimut (Zn) :", "Azimut (Zn):")
    val estimatedPositionMarker: String @Composable get() = s("Estimated Position", "Position estimée", "Posición estimada")
    val computedPositionMarker: String @Composable get() = s("Computed Position", "Position calculée", "Posición calculada")

    // ─── Calibration Screen ─────────────────────────────────────────────────────
    val horizonCalibration: String @Composable get() = s("Horizon Calibration", "Calibration de l’horizon", "Calibración del horizonte")
    val alignHorizon: String @Composable get() = s("Align the Red Line with the Horizon", "Alignez la ligne rouge avec l’horizon", "Alinea la línea roja con el horizonte")
    val sensorPitchFmt: String @Composable get() = s("Sensor Pitch: %.2f°", "Inclinaison : %.2f°", "Inclinación: %.2f°")
    val currentOffsetFmt: String @Composable get() = s("Current Offset: %.2f°", "Décalage actuel : %.2f°", "Desfase actual: %.2f°")
    val setHorizon: String @Composable get() = s("SET HORIZON", "DÉFINIR L’HORIZON", "DEFINIR HORIZONTE")
    val calibrateSensorsUpper: String @Composable get() = s("CALIBRATE SENSORS", "CALIBRER LES CAPTEURS", "CALIBRAR SENSORES")
    val sensorCalibrationTitle: String @Composable get() = s("Sensor Calibration", "Calibration des capteurs", "Calibración de sensores")
    val stepProgressFmt: String @Composable get() = s("Step %d / %d", "Étape %d / %d", "Paso %d / %d")
    val screenUp: String @Composable get() = s("Screen UP (Z+)", "Écran vers le HAUT (Z+)", "Pantalla ARRIBA (Z+)")
    val screenDown: String @Composable get() = s("Screen DOWN (Z-)", "Écran vers le BAS (Z-)", "Pantalla ABAJO (Z-)")
    val topEdgeUp: String @Composable get() = s("Top Edge UP (Y+)", "Bord supérieur vers le HAUT (Y+)", "Borde superior ARRIBA (Y+)")
    val topEdgeDown: String @Composable get() = s("Top Edge DOWN (Y-)", "Bord supérieur vers le BAS (Y-)", "Borde superior ABAJO (Y-)")
    val rightEdgeUp: String @Composable get() = s("Right Edge UP (X+)", "Bord droit vers le HAUT (X+)", "Borde derecho ARRIBA (X+)")
    val leftEdgeUp: String @Composable get() = s("Left Edge UP (X-)", "Bord gauche vers le HAUT (X-)", "Borde izquierdo ARRIBA (X-)")
    val sphereSteps: List<String> @Composable get() = listOf(screenUp, screenDown, topEdgeUp, topEdgeDown, rightEdgeUp, leftEdgeUp)
    val changePositionAlert: String @Composable get() = s("Change Position!", "Changez de position !", "¡Cambia de posición!")
    val holdStillRecording: String @Composable get() = s("Hold Still... Recording...", "Restez immobile… Enregistrement en cours…", "No te muevas... Grabando...")
    val keepDeviceSteady: String @Composable get() = s("Keep device steady", "Maintenez l’appareil stable", "Mantén el dispositivo estable")
    val calibrationComplete: String @Composable get() = s("Calibration Complete!", "Calibration terminée !", "¡Calibración completa!")
    val saveConfiguration: String @Composable get() = s("SAVE CONFIGURATION", "ENREGISTRER", "GUARDAR CONFIGURACIÓN")
    val done: String @Composable get() = s("Done", "Terminé", "Hecho")

    // ─── History Screen ─────────────────────────────────────────────────────────
    val positionHistory: String @Composable get() = s("Position History", "Historique des positions", "Historial de posiciones")
    val clearAll: String @Composable get() = s("Clear All", "Tout effacer", "Borrar todo")
    val noRecordedPositions: String @Composable get() = s("No recorded positions yet.", "Aucune position enregistrée pour l’instant.", "Aún no hay posiciones registradas.")
    val deleteEntry: String @Composable get() = s("Delete entry", "Supprimer", "Eliminar")

    // ─── Image Viewer ───────────────────────────────────────────────────────────
    val showStars: String @Composable get() = s("Show Stars", "Afficher les étoiles", "Mostrar estrellas")
    val fullScreenImage: String @Composable get() = s("Full Screen Image", "Image plein écran", "Imagen en pantalla completa")

    // ─── Image Metadata Card ────────────────────────────────────────────────────
    val imageDetails: String @Composable get() = s("Image Details", "Détails de l’image", "Detalles de la imagen")
    val fileName: String @Composable get() = s("File Name:", "Nom du fichier :", "Nombre del archivo:")
    val timestamp: String @Composable get() = s("Timestamp:", "Horodatage :", "Marca de tiempo:")
    val measuredHeight: String @Composable get() = s("Measured Height:", "Hauteur mesurée :", "Altura medida:")
    val analysis: String @Composable get() = s("Analysis", "Analyse", "Análisis")
    val statusLabel: String @Composable get() = s("Status:", "Statut :", "Estado:")
    val processing: String @Composable get() = s("Processing...", "Traitement en cours…", "Procesando...")
    @Composable fun statusFailed(err: String?): String = s("Failed: $err", "Échec : $err", "Fallido: $err")
    val solved: String @Composable get() = s("Solved", "Résolu", "Resuelto")
    val notSolved: String @Composable get() = s("Not Solved", "Non résolu", "No resuelto")
    val lopDataLabel: String @Composable get() = s("LOP Data", "Données LOP", "Datos LOP")
    val errorLabel: String @Composable get() = s("Error:", "Erreur :", "Error:")
    val reasonLabel: String @Composable get() = s("Reason:", "Raison :", "Razón:")
    val removeImage: String @Composable get() = s("Remove Image", "Supprimer l’image", "Eliminar imagen")
    val raLabel: String @Composable get() = s("RA:", "AD :", "AR:")
    val decLabel: String @Composable get() = s("Dec:", "Déc :", "Dec:")
    val interceptNm: String @Composable get() = s("Intercept:", "Intercept :", "Intercepto:")
    val azimuthShort: String @Composable get() = s("Azimuth:", "Azimut :", "Azimut:")

    // ─── Image Slot ─────────────────────────────────────────────────────────────
    val capturedImage: String @Composable get() = s("Captured image", "Image capturée", "Imagen capturada")

    // ─── Notifications (non-composable) ─────────────────────────────────────────
    fun notifTitle(locale: AppLocale) = get(locale,
        "Sensor Calibration Needed",
        "Calibration des capteurs nécessaire",
        "Calibración de sensores necesaria"
    )
    fun notifText(locale: AppLocale) = get(locale,
        "Recalibrate your sensors for best accuracy.",
        "Recalibrez vos capteurs pour une meilleure précision.",
        "Recalibra tus sensores para mejor precisión."
    )
    fun notifLongText(locale: AppLocale) = get(locale,
        "It's been over 10 days since your last calibration. Recalibrate for best accuracy.",
        "Cela fait plus de 10 jours depuis votre dernière calibration. Recalibrez pour une meilleure précision.",
        "Han pasado más de 10 días desde tu última calibración. Recalibra para mejor precisión."
    )
    fun notifChannelName(locale: AppLocale) = get(locale,
        "Calibration Reminders",
        "Rappels de calibration",
        "Recordatorios de calibración"
    )
    fun notifChannelDesc(locale: AppLocale) = get(locale,
        "Reminds you when sensor calibration is needed",
        "Vous rappelle quand la calibration est nécessaire",
        "Te recuerda cuando la calibración es necesaria"
    )
}
