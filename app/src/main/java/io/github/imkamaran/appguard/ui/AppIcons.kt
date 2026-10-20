package io.github.imkamaran.appguard.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import io.github.imkamaran.appguard.policy.Category

/**
 * Material icons that are not in material-icons-core. Defined here instead of depending on
 * material-icons-extended, which adds several MB to the unminified APK.
 */
object AppIcons {
    val Wifi = icon(
        "Wifi",
        "M1,9l2,2c4.97,-4.97 13.03,-4.97 18,0l2,-2C16.93,2.93 7.08,2.93 1,9zM9,17l3,3 3,-3c-1.65,-1.66 -4.34,-1.66 -6,0zM5,13l2,2c2.76,-2.76 7.24,-2.76 10,0l2,-2C15.14,9.14 8.87,9.14 5,13z",
    )
    val ContentPaste = icon(
        "ContentPaste",
        "M19,2h-4.18C14.4,0.84 13.3,0 12,0c-1.3,0 -2.4,0.84 -2.82,2L5,2c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2L21,4c0,-1.1 -0.9,-2 -2,-2zM12,2c0.55,0 1,0.45 1,1s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1zM19,20L5,20L5,4h2v3h10L17,4h2v16z",
    )
    val Sensors = icon(
        "Sensors",
        "M7.76,16.24C6.67,15.16 6,13.66 6,12s0.67,-3.16 1.76,-4.24l1.42,1.42C8.45,9.9 8,10.9 8,12c0,1.1 0.45,2.1 1.17,2.83l-1.41,1.41zM16.24,16.24C17.33,15.16 18,13.66 18,12s-0.67,-3.16 -1.76,-4.24l-1.42,1.42C15.55,9.9 16,10.9 16,12c0,1.1 -0.45,2.1 -1.17,2.83l1.41,1.41zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM20,12c0,2.21 -0.9,4.21 -2.35,5.65l1.42,1.42C20.88,17.26 22,14.76 22,12s-1.12,-5.26 -2.93,-7.07l-1.42,1.42C19.1,7.79 20,9.79 20,12zM6.35,6.35L4.93,4.93C3.12,6.74 2,9.24 2,12s1.12,5.26 2.93,7.07l1.42,-1.42C4.9,16.21 4,14.21 4,12s0.9,-4.21 2.35,-5.65z",
    )
    val SimCard = icon(
        "SimCard",
        "M19.99,4c0,-1.1 -0.89,-2 -1.99,-2h-8L4,8v12c0,1.1 0.9,2 2,2h12.01c1.1,0 1.99,-0.9 1.99,-2l-0.01,-16zM9,19L7,19v-2h2v2zM17,19h-2v-2h2v2zM9,15L7,15v-4h2v4zM13,19h-2v-4h2v4zM13,13h-2v-2h2v2zM17,15h-2v-4h2v4z",
    )
    val VerifiedUser = icon(
        "VerifiedUser",
        "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12L21,5l-9,-4zM10,17l-4,-4 1.41,-1.41L10,14.17l6.59,-6.59L18,9l-8,8z",
    )
    val Shield = icon("Shield", "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12L21,5l-9,-4z")
    val Pause = icon("Pause", "M6,19h4L10,5L6,5v14zM14,5v14h4L18,5h-4z")
    val Code = icon(
        "Code",
        "M9.4,16.6L4.8,12l4.6,-4.6L8,6l-6,6 6,6 1.4,-1.4zM14.6,16.6l4.6,-4.6 -4.6,-4.6L16,6l6,6 -6,6 -1.4,-1.4z",
    )
    val OpenInNew = icon(
        "OpenInNew",
        "M19,19L5,19L5,5h7L12,3L5,3c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7zM14,3v2h3.59l-9.83,9.83 1.41,1.41L19,6.41L19,10h2L21,3h-7z",
    )

    fun forCategory(c: Category): ImageVector = when (c) {
        Category.LOCATION -> Icons.Filled.LocationOn
        Category.NETWORK -> Wifi
        Category.TELEPHONY -> SimCard
        Category.IDENTIFIERS -> Icons.Filled.Face
        Category.CONTACTS -> Icons.Filled.Person
        Category.CLIPBOARD -> ContentPaste
        Category.SENSORS -> Sensors
        Category.SYSTEM -> Icons.Filled.Settings
    }

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
