package su.xash.engine.ui.extrakeys;

import androidx.annotation.NonNull;

public class ExtraKeysInfo {

	private final ExtraKeyButton[][] mButtons;

	public ExtraKeysInfo(@NonNull String[][] keyNames) {
		mButtons = new ExtraKeyButton[keyNames.length][];
		for (int row = 0; row < keyNames.length; row++) {
			mButtons[row] = new ExtraKeyButton[keyNames[row].length];
			for (int col = 0; col < keyNames[row].length; col++) {
				mButtons[row][col] = new ExtraKeyButton(keyNames[row][col]);
			}
		}
	}

	public ExtraKeysInfo(@NonNull ExtraKeyButton[][] buttons) {
		mButtons = buttons;
	}

	@NonNull
	public ExtraKeyButton[][] getMatrix() {
		return mButtons;
	}
}
