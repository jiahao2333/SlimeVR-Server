package dev.slimevr.vr.processor.skeleton;

import java.util.HashMap;
import java.util.Map;


public enum SkeletonConfigToggle {

	EXTENDED_SPINE_MODEL("Extended spine model", "extendedSpine", true),
	EXTENDED_PELVIS_MODEL("Extended pelvis model", "extendedPelvis", true),
	EXTENDED_KNEE_MODEL("Extended knee model", "extendedKnee", true),
	FORCE_ARMS_FROM_HMD("Force arms from HMD", "forceArmsFromHMD", false),;

	public static final SkeletonConfigToggle[] values = values();
	private static final String CONFIG_PREFIX = "skeleton.toggles.";
	private static final Map<String, SkeletonConfigToggle> byStringVal = new HashMap<>();

	static {
		for (SkeletonConfigToggle configVal : values()) {
			byStringVal.put(configVal.stringVal.toLowerCase(), configVal);
		}
	}

	public final String stringVal;
	public final String configKey;
	public final boolean defaultValue;

	SkeletonConfigToggle(String stringVal, String configKey, boolean defaultValue) {
		this.stringVal = stringVal;
		this.configKey = CONFIG_PREFIX + configKey;

		this.defaultValue = defaultValue;
	}

	public static SkeletonConfigToggle getByStringValue(String stringVal) {
		return stringVal == null ? null : byStringVal.get(stringVal.toLowerCase());
	}
}
