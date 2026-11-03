package at.mikag.alarmspike

import android.content.Context
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

fun Context.dp(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

/** Keeps content out from under the status and navigation bars (edge-to-edge on Android 15+). */
fun View.padForSystemBars() {
    if (Build.VERSION.SDK_INT >= 30) {
        setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }
}

fun LinearLayout.heading(text: String) {
    addView(TextView(context).apply {
        this.text = text
        textSize = 18f
        setPadding(0, context.dp(24), 0, context.dp(6))
    })
}

fun LinearLayout.note(text: String): TextView {
    val view = TextView(context).apply {
        this.text = text
        textSize = 14f
        alpha = 0.8f
    }
    addView(view)
    return view
}

fun LinearLayout.button(text: String, onClick: () -> Unit): Button {
    val view = Button(context).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }
    addView(
        view,
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    )
    return view
}
