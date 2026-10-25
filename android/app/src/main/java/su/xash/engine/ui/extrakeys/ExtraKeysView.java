package su.xash.engine.ui.extrakeys;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.GridLayout;
import android.widget.PopupWindow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A {@link View} showing extra keys (such as Escape, Ctrl, Alt) not normally available on an Android soft
 * keyboards.
 */
public final class ExtraKeysView extends GridLayout {

	/** The client for the {@link ExtraKeysView}. */
	public interface IExtraKeysView {

		/**
		 * This is called by {@link ExtraKeysView} when a button is clicked. This is also called
		 * for {@link #mRepetitiveKeys} and {@link ExtraKeyButton} that have a popup set.
		 * However, this is not called for {@link #mSpecialButtons}, whose state can instead be read
		 * via a call to {@link #readSpecialButton(SpecialButton, boolean)}.
		 *
		 * @param view The view that was clicked.
		 * @param buttonInfo The {@link ExtraKeyButton} for the button that was clicked.
		 * @param button The {@link MaterialButton} that was clicked.
		 */
		void onExtraKeyButtonClick(View view, ExtraKeyButton buttonInfo, MaterialButton button);

		/**
		 * This is called by {@link ExtraKeysView} when a button is clicked so that the client
		 * can perform any haptic feedback. This is only called in the {@link MaterialButton.OnClickListener}
		 * and not for every repeat. Its also called for {@link #mSpecialButtons}.
		 */
		boolean performExtraKeyButtonHapticFeedback(View view, ExtraKeyButton buttonInfo, MaterialButton button);

		/**
		 * Called when a {@link SpecialButton} (such as CTRL or ALT) is toggled by the user.
		 */
		default void onSpecialButtonToggled(SpecialButton specialButton, boolean isActive, boolean isLocked) {}

	}

	/** Defines the default fallback value for {@link #mButtonTextColor}. */
	public static final int DEFAULT_BUTTON_TEXT_COLOR = 0xFFFFFFFF;
	/** Defines the default fallback value for {@link #mButtonActiveTextColor}. */
	public static final int DEFAULT_BUTTON_ACTIVE_TEXT_COLOR = 0xFF80DEEA;
	/** Defines the default fallback value for {@link #mButtonBackgroundColor}. */
	public static final int DEFAULT_BUTTON_BACKGROUND_COLOR = 0x00000000;
	/** Defines the default fallback value for {@link #mButtonActiveBackgroundColor}. */
	public static final int DEFAULT_BUTTON_ACTIVE_BACKGROUND_COLOR = 0xFF7F7F7F;

	/** Defines the minimum allowed duration in milliseconds for {@link #mLongPressTimeout}. */
	public static final int MIN_LONG_PRESS_DURATION = 200;
	/** Defines the maximum allowed duration in milliseconds for {@link #mLongPressTimeout}. */
	public static final int MAX_LONG_PRESS_DURATION = 3000;
	/** Defines the fallback duration in milliseconds for {@link #mLongPressTimeout}. */
	public static final int FALLBACK_LONG_PRESS_DURATION = 400;

	/** Defines the minimum allowed duration in milliseconds for {@link #mLongPressRepeatDelay}. */
	public static final int MIN_LONG_PRESS__REPEAT_DELAY = 5;
	/** Defines the maximum allowed duration in milliseconds for {@link #mLongPressRepeatDelay}. */
	public static final int MAX_LONG_PRESS__REPEAT_DELAY = 2000;
	/** Defines the default duration in milliseconds for {@link #mLongPressRepeatDelay}. */
	public static final int DEFAULT_LONG_PRESS_REPEAT_DELAY = 80;

	/** The implementation of the {@link IExtraKeysView} that acts as a client for the {@link ExtraKeysView}. */
	protected IExtraKeysView mExtraKeysViewClient;

	/** The map for the {@link SpecialButton} and their {@link SpecialButtonState}. */
	protected Map<SpecialButton, SpecialButtonState> mSpecialButtons;

	/** The keys for the {@link SpecialButton} added to {@link #mSpecialButtons}. */
	protected Set<String> mSpecialButtonsKeys;

	/**
	 * The list of keys for which auto repeat of key should be triggered if its extra keys button
	 * is long pressed.
	 */
	protected List<String> mRepetitiveKeys;

	/** The text color for the extra keys button. Defaults to {@link #DEFAULT_BUTTON_TEXT_COLOR}. */
	protected int mButtonTextColor;
	/** The text color for the extra keys button when its active. */
	protected int mButtonActiveTextColor;
	/** The background color for the extra keys button. Defaults to {@link #DEFAULT_BUTTON_BACKGROUND_COLOR}. */
	protected int mButtonBackgroundColor;
	/** The background color for the extra keys button when its active. */
	protected int mButtonActiveBackgroundColor;

	/** Defines whether text for the extra keys button should be all capitalized automatically. */
	protected boolean mButtonTextAllCaps = true;

	/** Defines the duration in milliseconds before a press turns into a long press. */
	protected int mLongPressTimeout;

	/** Defines the duration in milliseconds for the delay between trigger of each repeat of {@link #mRepetitiveKeys}. */
	protected int mLongPressRepeatDelay;

	/** The popup window shown if {@link ExtraKeyButton#getPopup()} returns a {@code non-null} value. */
	protected PopupWindow mPopupWindow;

	protected ScheduledExecutorService mScheduledExecutor;
	protected Handler mHandler;
	protected SpecialButtonsLongHoldRunnable mSpecialButtonsLongHoldRunnable;
	protected int mLongPressCount;

	public ExtraKeysView(Context context, AttributeSet attrs) {
		super(context, attrs);

		setRepetitiveKeys(ExtraKeysConstants.PRIMARY_REPETITIVE_KEYS);
		setSpecialButtons(getDefaultSpecialButtons(this));

		setButtonColors(
			DEFAULT_BUTTON_TEXT_COLOR,
			DEFAULT_BUTTON_ACTIVE_TEXT_COLOR,
			DEFAULT_BUTTON_BACKGROUND_COLOR,
			DEFAULT_BUTTON_ACTIVE_BACKGROUND_COLOR
		);

		setLongPressTimeout(ViewConfiguration.getLongPressTimeout());
		setLongPressRepeatDelay(DEFAULT_LONG_PRESS_REPEAT_DELAY);
	}

	/** Get {@link #mExtraKeysViewClient}. */
	public IExtraKeysView getExtraKeysViewClient() {
		return mExtraKeysViewClient;
	}

	/** Set {@link #mExtraKeysViewClient}. */
	public void setExtraKeysViewClient(IExtraKeysView extraKeysViewClient) {
		mExtraKeysViewClient = extraKeysViewClient;
	}

	/** Get {@link #mRepetitiveKeys}. */
	public List<String> getRepetitiveKeys() {
		if (mRepetitiveKeys == null) return null;
		return new ArrayList<>(mRepetitiveKeys);
	}

	/** Set {@link #mRepetitiveKeys}. Must not be {@code null}. */
	public void setRepetitiveKeys(@NonNull List<String> repetitiveKeys) {
		mRepetitiveKeys = repetitiveKeys;
	}

	/** Get {@link #mSpecialButtons}. */
	public Map<SpecialButton, SpecialButtonState> getSpecialButtons() {
		if (mSpecialButtons == null) return null;
		return new HashMap<>(mSpecialButtons);
	}

	/** Get {@link #mSpecialButtonsKeys}. */
	public Set<String> getSpecialButtonsKeys() {
		if (mSpecialButtonsKeys == null) return null;
		return new HashSet<>(mSpecialButtonsKeys);
	}

	/** Set {@link #mSpecialButtonsKeys}. Must not be {@code null}. */
	public void setSpecialButtons(@NonNull Map<SpecialButton, SpecialButtonState> specialButtons) {
		mSpecialButtons = specialButtons;
		Set<String> keys = new HashSet<>();
		for (SpecialButton sb : this.mSpecialButtons.keySet()) {
			keys.add(sb.getKey());
		}
		mSpecialButtonsKeys = keys;
	}

	/**
	 * Set the {@link ExtraKeysView} button colors.
	 */
	public void setButtonColors(int buttonTextColor, int buttonActiveTextColor, int buttonBackgroundColor, int buttonActiveBackgroundColor) {
		mButtonTextColor = buttonTextColor;
		mButtonActiveTextColor = buttonActiveTextColor;
		mButtonBackgroundColor = buttonBackgroundColor;
		mButtonActiveBackgroundColor = buttonActiveBackgroundColor;
	}

	/** Get {@link #mButtonTextColor}. */
	public int getButtonTextColor() {
		return mButtonTextColor;
	}

	/** Set {@link #mButtonTextColor}. */
	public void setButtonTextColor(int buttonTextColor) {
		mButtonTextColor = buttonTextColor;
	}

	/** Get {@link #mButtonActiveTextColor}. */
	public int getButtonActiveTextColor() {
		return mButtonActiveTextColor;
	}

	/** Set {@link #mButtonActiveTextColor}. */
	public void setButtonActiveTextColor(int buttonActiveTextColor) {
		mButtonActiveTextColor = buttonActiveTextColor;
	}

	/** Get {@link #mButtonBackgroundColor}. */
	public int getButtonBackgroundColor() {
		return mButtonBackgroundColor;
	}

	/** Set {@link #mButtonBackgroundColor}. */
	public void setButtonBackgroundColor(int buttonBackgroundColor) {
		mButtonBackgroundColor = buttonBackgroundColor;
	}

	/** Get {@link #mButtonActiveBackgroundColor}. */
	public int getButtonActiveBackgroundColor() {
		return mButtonActiveBackgroundColor;
	}

	/** Set {@link #mButtonActiveBackgroundColor}. */
	public void setButtonActiveBackgroundColor(int buttonActiveBackgroundColor) {
		mButtonActiveBackgroundColor = buttonActiveBackgroundColor;
	}

	/** Set {@link #mButtonTextAllCaps}. */
	public void setButtonTextAllCaps(boolean buttonTextAllCaps) {
		mButtonTextAllCaps = buttonTextAllCaps;
	}

	/** Get {@link #mLongPressTimeout}. */
	public int getLongPressTimeout() {
		return mLongPressTimeout;
	}

	/** Set {@link #mLongPressTimeout}. */
	public void setLongPressTimeout(int longPressDuration) {
		if (longPressDuration >= MIN_LONG_PRESS_DURATION && longPressDuration <= MAX_LONG_PRESS_DURATION) {
			mLongPressTimeout = longPressDuration;
		} else {
			mLongPressTimeout = FALLBACK_LONG_PRESS_DURATION;
		}
	}

	/** Get {@link #mLongPressRepeatDelay}. */
	public int getLongPressRepeatDelay() {
		return mLongPressRepeatDelay;
	}

	/** Set {@link #mLongPressRepeatDelay}. */
	public void setLongPressRepeatDelay(int longPressRepeatDelay) {
		if (longPressRepeatDelay >= MIN_LONG_PRESS__REPEAT_DELAY && longPressRepeatDelay <= MAX_LONG_PRESS__REPEAT_DELAY) {
			mLongPressRepeatDelay = longPressRepeatDelay;
		} else {
			mLongPressRepeatDelay = DEFAULT_LONG_PRESS_REPEAT_DELAY;
		}
	}

	/** Get the default map that can be used for {@link #mSpecialButtons}. */
	@NonNull
	public Map<SpecialButton, SpecialButtonState> getDefaultSpecialButtons(ExtraKeysView extraKeysView) {
		return new HashMap<SpecialButton, SpecialButtonState>() {{
			put(SpecialButton.CTRL, new SpecialButtonState(extraKeysView));
			put(SpecialButton.ALT, new SpecialButtonState(extraKeysView));
			put(SpecialButton.SHIFT, new SpecialButtonState(extraKeysView));
			put(SpecialButton.FN, new SpecialButtonState(extraKeysView));
		}};
	}

	/**
	 * Reload this instance of {@link ExtraKeysView} with the info passed in {@code extraKeysInfo}.
	 */
	public void reload(ExtraKeysInfo extraKeysInfo) {
		reload(extraKeysInfo, 36f * getResources().getDisplayMetrics().density);
	}

	/**
	 * Reload this instance of {@link ExtraKeysView} with the info passed in {@code extraKeysInfo}.
	 */
	@SuppressLint("ClickableViewAccessibility")
	public void reload(ExtraKeysInfo extraKeysInfo, float heightPx) {
		if (extraKeysInfo == null)
			return;

		for (SpecialButtonState state : mSpecialButtons.values())
			state.buttons = new ArrayList<>();

		removeAllViews();

		ExtraKeyButton[][] buttons = extraKeysInfo.getMatrix();

		setRowCount(buttons.length);
		setColumnCount(maximumLength(buttons));

		for (int row = 0; row < buttons.length; row++) {
			for (int col = 0; col < buttons[row].length; col++) {
				final ExtraKeyButton buttonInfo = buttons[row][col];

				MaterialButton button;
				if (isSpecialButton(buttonInfo)) {
					button = createSpecialButton(buttonInfo.getKey(), true);
					if (button == null) return;
				} else {
					button = new MaterialButton(getContext(), null, android.R.attr.buttonBarButtonStyle);
				}

				button.setText(buttonInfo.getDisplay());
				button.setTextColor(mButtonTextColor);
				button.setAllCaps(mButtonTextAllCaps);
				button.setPadding(0, 0, 0, 0);
				button.setInsetTop(0);
				button.setInsetBottom(0);
				button.setMinWidth(0);
				button.setMinHeight(0);
				button.setMinimumWidth(0);
				button.setMinimumHeight(0);
				button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);

				button.setOnClickListener(view -> {
					performExtraKeyButtonHapticFeedback(view, buttonInfo, button);
					onAnyExtraKeyButtonClick(view, buttonInfo, button);
				});

				button.setOnTouchListener((view, event) -> {
					switch (event.getAction()) {
						case MotionEvent.ACTION_DOWN:
							view.setBackgroundColor(mButtonActiveBackgroundColor);
							startScheduledExecutors(view, buttonInfo, button);
							return true;

						case MotionEvent.ACTION_MOVE:
							if (buttonInfo.getPopup() != null) {
								if (mPopupWindow == null && event.getY() < 0) {
									stopScheduledExecutors();
									view.setBackgroundColor(mButtonBackgroundColor);
									showPopup(view, buttonInfo.getPopup());
								}
								if (mPopupWindow != null && event.getY() > 0) {
									view.setBackgroundColor(mButtonActiveBackgroundColor);
									dismissPopup();
								}
							}
							return true;

						case MotionEvent.ACTION_CANCEL:
							view.setBackgroundColor(mButtonBackgroundColor);
							stopScheduledExecutors();
							return true;

						case MotionEvent.ACTION_UP:
							view.setBackgroundColor(mButtonBackgroundColor);
							stopScheduledExecutors();
							if (mLongPressCount == 0 || mPopupWindow != null) {
								if (mPopupWindow != null) {
									dismissPopup();
									if (buttonInfo.getPopup() != null) {
										onAnyExtraKeyButtonClick(view, buttonInfo.getPopup(), button);
									}
								} else {
									view.performClick();
								}
							}
							return true;

						default:
							return true;
					}
				});

				LayoutParams param = new GridLayout.LayoutParams();
				param.width = 0;
				if (Build.VERSION.SDK_INT == Build.VERSION_CODES.LOLLIPOP) {
					param.height = (int) (heightPx + 0.5);
				} else {
					param.height = 0;
				}
				param.setMargins(0, 0, 0, 0);
				param.columnSpec = GridLayout.spec(col, GridLayout.FILL, 1.f);
				param.rowSpec = GridLayout.spec(row, GridLayout.FILL, 1.f);
				button.setLayoutParams(param);

				addView(button);
			}
		}
	}

	public void onExtraKeyButtonClick(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
		if (mExtraKeysViewClient != null)
			mExtraKeysViewClient.onExtraKeyButtonClick(view, buttonInfo, button);
	}

	public void performExtraKeyButtonHapticFeedback(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
		if (mExtraKeysViewClient != null) {
			if (mExtraKeysViewClient.performExtraKeyButtonHapticFeedback(view, buttonInfo, button))
				return;
		}

		if (Settings.System.getInt(getContext().getContentResolver(),
			Settings.System.HAPTIC_FEEDBACK_ENABLED, 0) != 0) {

			if (Build.VERSION.SDK_INT >= 28) {
				button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
			} else {
				if (Settings.Global.getInt(getContext().getContentResolver(), "zen_mode", 0) != 2) {
					button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
				}
			}
		}
	}

	public void onAnyExtraKeyButtonClick(View view, @NonNull ExtraKeyButton buttonInfo, MaterialButton button) {
		if (isSpecialButton(buttonInfo)) {
			if (mLongPressCount > 0) return;
			SpecialButton sb = SpecialButton.valueOf(buttonInfo.getKey());
			SpecialButtonState state = mSpecialButtons.get(sb);
			if (state == null) return;

			state.setIsActive(!state.isActive);
			if (!state.isActive)
				state.setIsLocked(false);
			if (mExtraKeysViewClient != null)
				mExtraKeysViewClient.onSpecialButtonToggled(sb, state.isActive, state.isLocked);
		} else {
			onExtraKeyButtonClick(view, buttonInfo, button);
		}
	}

	public void startScheduledExecutors(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
		stopScheduledExecutors();
		mLongPressCount = 0;
		if (mRepetitiveKeys.contains(buttonInfo.getKey())) {
			if (mHandler == null)
				mHandler = new Handler(Looper.getMainLooper());
			mScheduledExecutor = Executors.newSingleThreadScheduledExecutor();
			mScheduledExecutor.scheduleWithFixedDelay(() -> {
				mLongPressCount++;
				mHandler.post(() -> onExtraKeyButtonClick(view, buttonInfo, button));
			}, mLongPressTimeout, mLongPressRepeatDelay, TimeUnit.MILLISECONDS);
		} else if (isSpecialButton(buttonInfo)) {
			SpecialButton sb = SpecialButton.valueOf(buttonInfo.getKey());
			SpecialButtonState state = mSpecialButtons.get(sb);
			if (state == null) return;
			if (mHandler == null)
				mHandler = new Handler(Looper.getMainLooper());
			mSpecialButtonsLongHoldRunnable = new SpecialButtonsLongHoldRunnable(sb, state);
			mHandler.postDelayed(mSpecialButtonsLongHoldRunnable, mLongPressTimeout);
		}
	}

	public void stopScheduledExecutors() {
		if (mScheduledExecutor != null) {
			mScheduledExecutor.shutdownNow();
			mScheduledExecutor = null;
		}

		if (mSpecialButtonsLongHoldRunnable != null && mHandler != null) {
			mHandler.removeCallbacks(mSpecialButtonsLongHoldRunnable);
			mSpecialButtonsLongHoldRunnable = null;
		}
	}

	public class SpecialButtonsLongHoldRunnable implements Runnable {
		public final SpecialButton mSpecialButton;
		public final SpecialButtonState mState;

		public SpecialButtonsLongHoldRunnable(SpecialButton specialButton, SpecialButtonState state) {
			mSpecialButton = specialButton;
			mState = state;
		}

		public void run() {
			mState.setIsLocked(!mState.isActive);
			mState.setIsActive(!mState.isActive);
			mLongPressCount++;
			if (mExtraKeysViewClient != null)
				mExtraKeysViewClient.onSpecialButtonToggled(mSpecialButton, mState.isActive, mState.isLocked);
		}
	}

	/**
	 * Check whether any {@link SpecialButton} is currently active without changing its state.
	 */
	public boolean isAnySpecialButtonActive() {
		for (SpecialButtonState state : mSpecialButtons.values()) {
			if (state.isCreated && state.isActive) {
				return true;
			}
		}
		return false;
	}

	void showPopup(View view, ExtraKeyButton extraButton) {
		int width = view.getMeasuredWidth();
		int height = view.getMeasuredHeight();
		MaterialButton button;
		if (isSpecialButton(extraButton)) {
			button = createSpecialButton(extraButton.getKey(), false);
			if (button == null) return;
		} else {
			button = new MaterialButton(getContext(), null, android.R.attr.buttonBarButtonStyle);
			button.setTextColor(mButtonTextColor);
		}
		button.setText(extraButton.getDisplay());
		button.setAllCaps(mButtonTextAllCaps);
		button.setPadding(0, 0, 0, 0);
		button.setMinHeight(0);
		button.setMinWidth(0);
		button.setMinimumWidth(0);
		button.setMinimumHeight(0);
		button.setWidth(width);
		button.setHeight(height);
		button.setBackgroundColor(mButtonActiveBackgroundColor);
		mPopupWindow = new PopupWindow(this);
		mPopupWindow.setWidth(LayoutParams.WRAP_CONTENT);
		mPopupWindow.setHeight(LayoutParams.WRAP_CONTENT);
		mPopupWindow.setContentView(button);
		mPopupWindow.setOutsideTouchable(true);
		mPopupWindow.setFocusable(false);
		mPopupWindow.showAsDropDown(view, 0, -2 * height);
	}

	public void dismissPopup() {
		mPopupWindow.setContentView(null);
		mPopupWindow.dismiss();
		mPopupWindow = null;
	}

	/** Check whether a {@link ExtraKeyButton} is a {@link SpecialButton}. */
	public boolean isSpecialButton(ExtraKeyButton button) {
		return mSpecialButtonsKeys.contains(button.getKey());
	}

	/**
	 * Read whether {@link SpecialButton} registered in {@link #mSpecialButtons} is active or not.
	 */
	@Nullable
	public Boolean readSpecialButton(SpecialButton specialButton, boolean autoSetInActive) {
		SpecialButtonState state = mSpecialButtons.get(specialButton);
		if (state == null) return null;

		if (!state.isCreated || !state.isActive)
			return false;

		if (autoSetInActive && !state.isLocked)
			state.setIsActive(false);

		return true;
	}

	public MaterialButton createSpecialButton(String buttonKey, boolean needUpdate) {
		SpecialButtonState state = mSpecialButtons.get(SpecialButton.valueOf(buttonKey));
		if (state == null) return null;
		state.setIsCreated(true);
		MaterialButton button = new MaterialButton(getContext(), null, android.R.attr.buttonBarButtonStyle);
		button.setTextColor(state.isActive ? mButtonActiveTextColor : mButtonTextColor);
		if (needUpdate) {
			state.buttons.add(button);
		}
		return button;
	}

	/**
	 * General util function to compute the longest column length in a matrix.
	 */
	public static int maximumLength(Object[][] matrix) {
		int m = 0;
		for (Object[] row : matrix)
			m = Math.max(m, row.length);
		return m;
	}

}
