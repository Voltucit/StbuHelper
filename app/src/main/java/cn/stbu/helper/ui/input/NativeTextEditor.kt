package cn.stbu.helper.ui.input

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONObject

/** A local Android editor for devices whose IME cannot reliably edit WebView fields. */
class NativeTextEditor(private val activity: Activity, private val darkMode: () -> Boolean) {
    private var current: AlertDialog? = null

    fun open(request: JSONObject, complete: (JSONObject) -> Unit) {
        require(current == null) { "请先完成当前输入" }
        check(!activity.isFinishing && !activity.isDestroyed)
        val kind = request.optString("type", "text")
        require(kind in setOf("text", "password", "tel", "number", "email", "search", "url"))
        val multiline = request.optBoolean("multiline") && kind != "password"
        val maximum = request.optInt("maxLength", 8192).coerceIn(1, 8192)
        val theme = if (darkMode()) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
        val context = ContextThemeWrapper(activity, theme)
        val density = activity.resources.displayMetrics.density
        val editor = EditText(context).apply {
            id = View.generateViewId()
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            inputType = when {
                kind == "password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                kind == "tel" -> InputType.TYPE_CLASS_PHONE
                kind == "number" || request.optString("inputMode") == "decimal" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                request.optString("inputMode") == "numeric" -> InputType.TYPE_CLASS_NUMBER
                kind == "email" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                kind == "url" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
            setSingleLine(!multiline)
            if (multiline) { minLines = 3; maxLines = 8 }
            filters = arrayOf(InputFilter.LengthFilter(maximum))
            setText(request.optString("value").take(maximum))
            setSelection(text?.length ?: 0)
            hint = request.optString("placeholder").take(200)
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or (if (multiline) EditorInfo.IME_ACTION_NONE else EditorInfo.IME_ACTION_DONE)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * density).toInt()
            setPadding(padding, (8 * density).toInt(), padding, (8 * density).toInt())
            addView(editor, LinearLayout.LayoutParams(-1, -2))
        }
        var replied = false
        val dialog = AlertDialog.Builder(context)
            .setTitle(request.optString("title", "输入内容").take(80))
            .setView(content).setPositiveButton("完成", null).setNegativeButton("取消", null).create()
        current = dialog
        fun finish(cancelled: Boolean) {
            if (replied) return
            replied = true
            val result = JSONObject().put("cancelled", cancelled)
            if (!cancelled) result.put("value", editor.text?.toString().orEmpty())
            dialog.dismiss()
            complete(result)
        }
        dialog.setOnDismissListener {
            current = null
            editor.text?.clear()
            if (!replied) { replied = true; complete(JSONObject().put("cancelled", true)) }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { finish(false) }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { finish(true) }
            editor.setOnEditorActionListener { _, action, _ ->
                if (!multiline && action == EditorInfo.IME_ACTION_DONE) { finish(false); true } else false
            }
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            editor.requestFocus()
            // Let the WebView finish losing window focus before opening the native IME.
            editor.postDelayed({
                if (dialog.isShowing && editor.hasWindowFocus())
                    (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }, 150)
        }
        try { dialog.show() }
        catch (error: Exception) { current = null; editor.text?.clear(); throw error }
    }

    fun close() { current?.dismiss() }
}
