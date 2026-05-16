package com.improvehub.healthconnectexporter

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 36)
        }

        val title = TextView(this).apply {
            text = "ImproveHUB Health Exporter"
            textSize = 24f
        }

        val body = TextView(this).apply {
            text =
                "This app reads health data from Health Connect to create a local export " +
                "for your ImproveHUB analysis workflow. It reads only the data you grant " +
                "access to, shows the export on-device, and shares it only when you " +
                "explicitly tap the share button."
            textSize = 16f
            setPadding(0, 24, 0, 0)
        }

        root.addView(title)
        root.addView(body)

        val scrollView = ScrollView(this).apply { addView(root) }
        setContentView(scrollView)
    }
}
