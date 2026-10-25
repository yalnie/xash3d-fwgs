package su.xash.engine.ui.extrakeys

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import com.google.android.material.textfield.TextInputEditText

class ConsoleEditText @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = com.google.android.material.R.attr.editTextStyle
) : TextInputEditText(context, attrs, defStyleAttr) {

	interface OnConsoleKeyListener {
		fun isSpecialModifierActive(): Boolean
		fun onModifiedCharTyped(ch: Char): Boolean
		fun onModifiedBackspace(): Boolean
		fun onConsoleKeyDown(keyCode: Int, event: KeyEvent): Boolean
	}

	var consoleKeyListener: OnConsoleKeyListener? = null

	override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
		val base = super.onCreateInputConnection(outAttrs) ?: return null
		return object : InputConnectionWrapper(base, true) {
			override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
				val listener = consoleKeyListener
				if (listener != null && listener.isSpecialModifierActive() && !text.isNullOrEmpty()) {
					super.finishComposingText()
					if (listener.onModifiedCharTyped(text[text.length - 1])) {
						return true
					}
				}
				return super.commitText(text, newCursorPosition)
			}

			override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
				val listener = consoleKeyListener
				if (listener != null && listener.isSpecialModifierActive() && !text.isNullOrEmpty()) {
					super.finishComposingText()
					if (listener.onModifiedCharTyped(text[text.length - 1])) {
						return true
					}
				}
				return super.setComposingText(text, newCursorPosition)
			}

			override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
				val listener = consoleKeyListener
				if (beforeLength > 0 && afterLength == 0 && listener != null && listener.isSpecialModifierActive()) {
					super.finishComposingText()
					if (listener.onModifiedBackspace()) {
						return true
					}
				}
				return super.deleteSurroundingText(beforeLength, afterLength)
			}

			override fun sendKeyEvent(event: KeyEvent): Boolean {
				val listener = consoleKeyListener
				if (event.action == KeyEvent.ACTION_DOWN && listener != null) {
					if (listener.onConsoleKeyDown(event.keyCode, event)) {
						return true
					}
				}
				return super.sendKeyEvent(event)
			}
		}
	}

	override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
		if (consoleKeyListener?.onConsoleKeyDown(keyCode, event) == true) {
			return true
		}
		return super.onKeyDown(keyCode, event)
	}
}
