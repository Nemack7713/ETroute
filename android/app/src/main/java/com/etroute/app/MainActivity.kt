package com.etroute.app

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        status.text = if (uri == null) {
            "Document selection cancelled"
        } else {
            "Document selected through Android SAF\n$uri"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = "Device: Not connected\nETroute: v${BuildConfig.VERSION_NAME}\nETumax: Not connected"
            textSize = 18f
        }

        val pairButton = Button(this).apply {
            text = "Pair Device (v0.5)"
            isEnabled = false
        }

        val documentButton = Button(this).apply {
            text = "Open Document"
            setOnClickListener { openDocument.launch(arrayOf("*/*")) }
        }

        val updateButton = Button(this).apply {
            text = "Check Update (v0.2)"
            isEnabled = false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(TextView(this@MainActivity).apply {
                text = "ETroute"
                textSize = 30f
            })
            addView(TextView(this@MainActivity).apply {
                text = "Local Android Orchestrator — Foundation"
                textSize = 15f
            })
            addView(status)
            addView(pairButton)
            addView(documentButton)
            addView(updateButton)
        }

        setContentView(root)
    }
}
