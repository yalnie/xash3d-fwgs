package su.xash.engine.ui.extrakeys;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

public class SpecialButtonState {

	public boolean isCreated = false;
	public boolean isActive = false;
	public boolean isLocked = false;

	public List<MaterialButton> buttons = new ArrayList<>();

	private final ExtraKeysView mExtraKeysView;

	public SpecialButtonState(ExtraKeysView extraKeysView) {
		mExtraKeysView = extraKeysView;
	}

	public void setIsCreated(boolean value) {
		isCreated = value;
	}

	public void setIsActive(boolean value) {
		isActive = value;
		for (MaterialButton button : buttons) {
			button.setTextColor(value ? mExtraKeysView.getButtonActiveTextColor() : mExtraKeysView.getButtonTextColor());
		}
	}

	public void setIsLocked(boolean value) {
		isLocked = value;
	}
}
