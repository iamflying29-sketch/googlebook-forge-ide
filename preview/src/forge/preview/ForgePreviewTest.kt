package forge.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import sergio.sastre.composable.preview.scanner.android.AndroidComposablePreviewScanner
import sergio.sastre.composable.preview.scanner.android.AndroidPreviewInfo
import sergio.sastre.composable.preview.scanner.core.preview.ComposablePreview

/**
 * Forge preview: renders every @Preview composable of the module on the screen shapes Forge asks for, with
 * Robolectric (Android's framework running on the JVM) and Roborazzi, so no device is needed. Robolectric runs the
 * Android version the app targets (or the newest it has). Forge adds this test only for the preview run, through a
 * Gradle init script; the project's own files are not changed.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForgePreviewTest(private val preview: ComposablePreview<AndroidPreviewInfo>, private val shape: String) {

    companion object {
        /** Robolectric resource qualifiers for each screen shape (size in dp, round or not, UI mode, density). */
        private val SHAPES = mapOf(
            "phone" to "w411dp-h914dp-normal-long-notround-port-420dpi",
            "foldable" to "w841dp-h701dp-normal-notlong-notround-land-420dpi",
            "tablet" to "w1280dp-h800dp-xlarge-long-notround-land-mdpi",
            "watch-round" to "w227dp-h227dp-small-notlong-round-watch-xhdpi",
            "watch-square" to "w180dp-h180dp-small-notlong-notround-watch-xhdpi",
            "tv" to "w960dp-h540dp-xlarge-long-notround-land-television-xhdpi",
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{1} {0}")
        fun previews(): List<Array<Any>> {
            val packages = System.getProperty("forge.preview.packages").orEmpty().split(",").filter { it.isNotBlank() }
            val shapes = System.getProperty("forge.preview.shapes").orEmpty().split(",").filter { it in SHAPES }
            val found = AndroidComposablePreviewScanner().scanPackageTrees(*packages.toTypedArray()).getPreviews()
            return found.flatMap { p -> shapes.map { s -> arrayOf<Any>(p, s) } }
        }
    }

    @Test
    fun render() {
        RuntimeEnvironment.setQualifiers(SHAPES.getValue(shape))
        val info = preview.previewInfo
        // One picture per preview: a multipreview such as @WearPreviewDevices gives several previews of one function.
        val label = listOf(info.group, info.name, preview.previewIndex?.toString().orEmpty())
            .filter { it.isNotBlank() }.joinToString("-")
        val name = (preview.declaringClass.substringAfterLast('.') + "." + preview.methodName +
            (if (label.isEmpty()) "" else "-$label")).replace(Regex("[^A-Za-z0-9._]+"), "-").trim('-')
        // The preview's own background, as Android Studio shows it; a watch screen is black otherwise.
        val background = when {
            info.showBackground -> if (info.backgroundColor != 0L) Color(info.backgroundColor) else Color.White
            shape.startsWith("watch") -> Color.Black
            else -> Color.Transparent
        }
        captureRoboImage(filePath = "${System.getProperty("forge.preview.out")}/$shape/$name.png") {
            Box(Modifier.fillMaxSize().background(Color.Black)) {   // the bezel around a round watch screen
                val screen = if (shape == "watch-round") Modifier.fillMaxSize().clip(CircleShape) else Modifier.fillMaxSize()
                Box(screen.background(background)) { preview() }
            }
        }
    }
}
