package su.xash.engine.ui.extrakeys;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class ExtraKeyButton {

	private final String mKey;
	private final boolean mMacro;
	private final String mDisplay;
	@Nullable
	private final ExtraKeyButton mPopup;

	public ExtraKeyButton(@NonNull String key) {
		this(key, null, false);
	}

	public ExtraKeyButton(@NonNull String key, @Nullable ExtraKeyButton popup) {
		this(key, popup, false);
	}

	public ExtraKeyButton(@NonNull String key, @Nullable ExtraKeyButton popup, boolean macro) {
		this(
			replaceAlias(ExtraKeysConstants.CONTROL_CHARS_ALIASES, key),
			ExtraKeysConstants.EXTRA_KEY_DISPLAY_MAPS.DEFAULT_CHAR_DISPLAY.get(
				replaceAlias(ExtraKeysConstants.CONTROL_CHARS_ALIASES, key),
				replaceAlias(ExtraKeysConstants.CONTROL_CHARS_ALIASES, key)
			),
			popup,
			macro
		);
	}

	public ExtraKeyButton(
		@NonNull String key,
		@NonNull String display,
		@Nullable ExtraKeyButton popup,
		boolean macro
	) {
		mKey = key;
		mDisplay = display;
		mPopup = popup;
		mMacro = macro;
	}

	public static String replaceAlias(@NonNull ExtraKeysConstants.ExtraKeyDisplayMap aliases, @NonNull String key) {
		return aliases.get(key, key);
	}

	@NonNull
	public String getKey() {
		return mKey;
	}

	public boolean isMacro() {
		return mMacro;
	}

	@NonNull
	public String getDisplay() {
		return mDisplay;
	}

	@Nullable
	public ExtraKeyButton getPopup() {
		return mPopup;
	}
}
