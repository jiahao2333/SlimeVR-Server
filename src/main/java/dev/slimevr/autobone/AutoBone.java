package dev.slimevr.autobone;

import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import dev.slimevr.VRServer;
import dev.slimevr.autobone.errors.*;
import dev.slimevr.poserecorder.PoseFrameIO;
import dev.slimevr.poserecorder.PoseFrameSkeleton;
import dev.slimevr.poserecorder.PoseFrameTracker;
import dev.slimevr.poserecorder.PoseFrames;
import dev.slimevr.vr.processor.HumanPoseProcessor;
import dev.slimevr.vr.processor.TransformNode;
import dev.slimevr.vr.processor.skeleton.*;
import dev.slimevr.vr.trackers.TrackerRole;
import io.eiren.util.StringUtils;
import io.eiren.util.collections.FastList;
import io.eiren.util.logging.LogManager;
import org.apache.commons.lang3.tuple.Pair;

import java.io.File;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;


public class AutoBone {

	private static final File saveDir = new File("Recordings");
	private static final File loadDir = new File("LoadRecordings");
	// This is filled by reloadConfigValues()
	public final EnumMap<BoneType, Float> offsets = new EnumMap<BoneType, Float>(
		BoneType.class
	);

	public final FastList<BoneType> adjustOffsets = new FastList<BoneType>(
		new BoneType[] {
			BoneType.HEAD,
			BoneType.NECK,
			BoneType.CHEST,
			BoneType.WAIST,
			BoneType.HIP,

			// This one doesn't seem to work very well and is generally going to
			// be similar between users
			// BoneType.LEFT_HIP,

			BoneType.LEFT_UPPER_LEG,
			BoneType.LEFT_LOWER_LEG,
		}
	);

	public final FastList<BoneType> heightOffsets = new FastList<BoneType>(
		new BoneType[] {
			BoneType.NECK,
			BoneType.CHEST,
			BoneType.WAIST,
			BoneType.HIP,

			BoneType.LEFT_UPPER_LEG,
			BoneType.RIGHT_UPPER_LEG,
			BoneType.LEFT_LOWER_LEG,
			BoneType.RIGHT_LOWER_LEG,
		}
	);

	public final FastList<SkeletonConfigValue> legacyHeightConfigs = new FastList<SkeletonConfigValue>(
		new SkeletonConfigValue[] {
			SkeletonConfigValue.NECK,
			SkeletonConfigValue.TORSO,

			SkeletonConfigValue.LEGS_LENGTH,
		}
	);

	public final EnumMap<SkeletonConfigValue, Float> legacyConfigs = new EnumMap<SkeletonConfigValue, Float>(
		SkeletonConfigValue.class
	);

	protected final VRServer server;
	public int cursorIncrement = 2;
	public int minDataDistance = 1;
	public int maxDataDistance = 1;
	public int numEpochs = 100;
	public float initialAdjustRate = 10f;
	public float adjustRateMultiplier = 0.995f;

	// #region Error functions
	public SlideError slideError = new SlideError();
	public float slideErrorFactor = 0.0f;

	public OffsetSlideError offsetSlideError = new OffsetSlideError();
	public float offsetSlideErrorFactor = 1.0f;

	public FootHeightOffsetError footHeightOffsetError = new FootHeightOffsetError();
	public float footHeightOffsetErrorFactor = 0.0f;

	public BodyProportionError bodyProportionError = new BodyProportionError();
	public float bodyProportionErrorFactor = 0.2f;

	public HeightError heightError = new HeightError();
	public float heightErrorFactor = 0.0f;

	public PositionError positionError = new PositionError();
	public float positionErrorFactor = 0.0f;

	public PositionOffsetError positionOffsetError = new PositionOffsetError();
	public float positionOffsetErrorFactor = 0.0f;
	// #endregion

	public boolean randomizeFrameOrder = true;
	public boolean scaleEachStep = true;

	public boolean calcInitError = false;
	public float targetHeight = -1;

	// TODO hip tracker stuff... Hip tracker should be around 3 to 5
	// centimeters.
	// Human average is probably 1.1235 (SD 0.07)
	public float legBodyRatio = 1.1235f;
	// SD of 0.07, capture 68% within range
	public float legBodyRatioRange = 0.07f;
	// kneeLegRatio seems to be around 0.54 to 0.6 after asking a few people in
	// the
	// SlimeVR discord.
	public float kneeLegRatio = 0.55f;
	// kneeLegRatio seems to be around 0.55 to 0.64 after asking a few people in
	// the
	// SlimeVR discord. TODO : Chest should be a bit shorter (0.54?) if user has
	// an
	// additional hip tracker.
	public float chestTorsoRatio = 0.57f;

	private final Random rand = new Random();

	public AutoBone(VRServer server) {
		this.server = server;
		reloadConfigValues();

		this.minDataDistance = server.config
			.getInt("autobone.minimumDataDistance", this.minDataDistance);
		this.maxDataDistance = server.config
			.getInt("autobone.maximumDataDistance", this.maxDataDistance);

		this.numEpochs = server.config.getInt("autobone.epochCount", this.numEpochs);

		this.initialAdjustRate = server.config
			.getFloat("autobone.adjustRate", this.initialAdjustRate);
		this.adjustRateMultiplier = server.config
			.getFloat("autobone.adjustRateMultiplier", this.adjustRateMultiplier);

		this.slideErrorFactor = server.config
			.getFloat("autobone.slideErrorFactor", this.slideErrorFactor);
		this.offsetSlideErrorFactor = server.config
			.getFloat("autobone.offsetSlideErrorFactor", this.offsetSlideErrorFactor);
		this.footHeightOffsetErrorFactor = server.config
			.getFloat("autobone.offsetErrorFactor", this.footHeightOffsetErrorFactor);
		this.bodyProportionErrorFactor = server.config
			.getFloat("autobone.proportionErrorFactor", this.bodyProportionErrorFactor);
		this.heightErrorFactor = server.config
			.getFloat("autobone.heightErrorFactor", this.heightErrorFactor);
		this.positionErrorFactor = server.config
			.getFloat("autobone.positionErrorFactor", this.positionErrorFactor);
		this.positionOffsetErrorFactor = server.config
			.getFloat("autobone.positionOffsetErrorFactor", this.positionOffsetErrorFactor);

		this.calcInitError = server.config.getBoolean("autobone.calculateInitialError", true);
		this.targetHeight = server.config.getFloat("autobone.manualTargetHeight", -1f);
	}

	// Mean square error function
	protected static float errorFunc(float errorDeriv) {
		return 0.5f * (errorDeriv * errorDeriv);
	}

	public static File getLoadDir() {
		return loadDir;
	}

	public void reloadConfigValues() {
		reloadConfigValues(null);
	}

	public void reloadConfigValues(List<PoseFrameTracker> trackers) {
		for (BoneType offset : adjustOffsets) {
			offsets.put(offset, 0.4f);
		}
	}

	public Vector3f getBoneDirection(
		HumanSkeleton skeleton,
		BoneType node,
		boolean rightSide,
		Vector3f buffer
	) {
		if (buffer == null) {
			buffer = new Vector3f();
		}

		switch (node) {
			case LEFT_HIP:
			case RIGHT_HIP:
				node = rightSide ? BoneType.RIGHT_HIP : BoneType.LEFT_HIP;
				break;

			case LEFT_UPPER_LEG:
			case RIGHT_UPPER_LEG:
				node = rightSide ? BoneType.RIGHT_UPPER_LEG : BoneType.LEFT_UPPER_LEG;
				break;

			case LEFT_LOWER_LEG:
			case RIGHT_LOWER_LEG:
				node = rightSide ? BoneType.RIGHT_LOWER_LEG : BoneType.LEFT_LOWER_LEG;
				break;
		}

		TransformNode relevantTransform = skeleton.getTailNodeOfBone(node);
		return relevantTransform.worldTransform
			.getTranslation()
			.subtract(relevantTransform.getParent().worldTransform.getTranslation(), buffer)
			.normalizeLocal();
	}

	public float getDotProductDiff(
		HumanSkeleton skeleton1,
		HumanSkeleton skeleton2,
		BoneType node,
		boolean rightSide,
		Vector3f offset
	) {
		Vector3f normalizedOffset = offset.normalize();

		Vector3f boneRotation = new Vector3f();
		getBoneDirection(skeleton1, node, rightSide, boneRotation);
		float dot1 = normalizedOffset.dot(boneRotation);

		getBoneDirection(skeleton2, node, rightSide, boneRotation);
		float dot2 = normalizedOffset.dot(boneRotation);

		return dot2 - dot1;
	}

	/**
	 * A simple utility method to get the {@link Skeleton} from the
	 * {@link VRServer}
	 *
	 * @return The {@link Skeleton} associated with the {@link VRServer}, or
	 * null if there is none available
	 * @see {@link VRServer}, {@link Skeleton}
	 */
	private Skeleton getSkeleton() {
		HumanPoseProcessor humanPoseProcessor = server != null ? server.humanPoseProcessor : null;
		return humanPoseProcessor != null ? humanPoseProcessor.getSkeleton() : null;
	}

	public void applyAndSaveConfig() {
		if (!applyAndSaveConfig(getSkeleton())) {
			// Unable to apply to skeleton, save directly
			// saveConfigs();
		}
	}

	public boolean applyConfig(
		BiConsumer<SkeletonConfigValue, Float> configConsumer,
		Map<BoneType, Float> offsets
	) {
		if (configConsumer == null || offsets == null) {
			return false;
		}

		try {
			Float headOffset = offsets.get(BoneType.HEAD);
			if (headOffset != null) {
				configConsumer.accept(SkeletonConfigValue.HEAD, headOffset);
			}

			Float neckOffset = offsets.get(BoneType.NECK);
			if (neckOffset != null) {
				configConsumer.accept(SkeletonConfigValue.NECK, neckOffset);
			}

			Float chestOffset = offsets.get(BoneType.CHEST);
			Float hipOffset = offsets.get(BoneType.HIP);
			Float waistOffset = offsets.get(BoneType.WAIST);
			if (chestOffset != null && hipOffset != null && waistOffset != null) {
				configConsumer
					.accept(SkeletonConfigValue.TORSO, chestOffset + hipOffset + waistOffset);
			}

			if (chestOffset != null) {
				configConsumer.accept(SkeletonConfigValue.CHEST, chestOffset);
			}

			if (hipOffset != null) {
				configConsumer.accept(SkeletonConfigValue.WAIST, hipOffset);
			}

			Float hipWidthOffset = offsets.get(BoneType.LEFT_HIP);
			if (hipWidthOffset == null) {
				hipWidthOffset = offsets.get(BoneType.RIGHT_HIP);
			}
			if (hipWidthOffset != null) {
				configConsumer
					.accept(SkeletonConfigValue.HIPS_WIDTH, hipWidthOffset * 2f);
			}

			Float upperLegOffset = offsets.get(BoneType.LEFT_UPPER_LEG);
			if (upperLegOffset == null) {
				upperLegOffset = offsets.get(BoneType.RIGHT_UPPER_LEG);
			}
			Float lowerLegOffset = offsets.get(BoneType.LEFT_LOWER_LEG);
			if (lowerLegOffset == null) {
				lowerLegOffset = offsets.get(BoneType.RIGHT_LOWER_LEG);
			}
			if (upperLegOffset != null && lowerLegOffset != null) {
				configConsumer
					.accept(SkeletonConfigValue.LEGS_LENGTH, upperLegOffset + lowerLegOffset);
			}

			if (lowerLegOffset != null) {
				configConsumer.accept(SkeletonConfigValue.KNEE_HEIGHT, lowerLegOffset);
			}
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	public boolean applyConfig(BiConsumer<SkeletonConfigValue, Float> configConsumer) {
		return applyConfig(configConsumer, offsets);
	}

	public boolean applyConfig(
		Map<SkeletonConfigValue, Float> skeletonConfig,
		Map<BoneType, Float> offsets
	) {
		if (skeletonConfig == null) {
			return false;
		}

		return applyConfig(skeletonConfig::put, offsets);
	}

	public boolean applyConfig(Map<SkeletonConfigValue, Float> skeletonConfig) {
		return applyConfig(skeletonConfig, offsets);
	}

	public boolean applyConfig(SkeletonConfig skeletonConfig, Map<BoneType, Float> offsets) {
		if (skeletonConfig == null) {
			return false;
		}

		return applyConfig(skeletonConfig::setConfig, offsets);
	}

	public boolean applyConfig(SkeletonConfig skeletonConfig) {
		return applyConfig(skeletonConfig, offsets);
	}

	public boolean applyAndSaveConfig(Skeleton skeleton) {
		if (skeleton == null) {
			return false;
		}

		SkeletonConfig skeletonConfig = skeleton.getSkeletonConfig();
		if (!applyConfig(skeletonConfig))
			return false;

		skeletonConfig.saveToConfig(server.config);
		server.saveConfig();

		LogManager.info("[AutoBone] Configured skeleton bone lengths");
		return true;
	}

	public Float getConfig(BoneType config) {
		return offsets.get(config);
	}

	public <T> float sumSelectConfigs(
		List<T> selection,
		Function<T, Float> configs
	) {
		float sum = 0f;

		for (T config : selection) {
			Float length = configs.apply(config);
			if (length != null) {
				sum += length;
			}
		}

		return sum;
	}

	public <T> float sumSelectConfigs(
		List<T> selection,
		Map<T, Float> configs
	) {
		return sumSelectConfigs(selection, configs::get);
	}

	public float sumSelectConfigs(
		List<SkeletonConfigValue> selection,
		SkeletonConfig config
	) {
		return sumSelectConfigs(selection, config::getConfig);
	}

	public float getLengthSum(Map<BoneType, Float> configs) {
		return getLengthSum(configs, null);
	}

	public float getLengthSum(
		Map<BoneType, Float> configs,
		Map<BoneType, Float> configsAlt
	) {
		float length = 0f;

		if (configsAlt != null) {
			for (Entry<BoneType, Float> config : configsAlt.entrySet()) {
				// If there isn't a duplicate config
				if (!configs.containsKey(config.getKey())) {
					length += config.getValue();
				}
			}
		}

		for (Float boneLength : configs.values()) {
			length += boneLength;
		}

		return length;
	}

	public float getTargetHeight(PoseFrames frames) {
		float targetHeight;
		// Get the current skeleton from the server
		Skeleton skeleton = getSkeleton();
		if (skeleton != null) {
			// If there is a skeleton available, calculate the target height
			// from its configs
			targetHeight = sumSelectConfigs(legacyHeightConfigs, skeleton.getSkeletonConfig());
			LogManager
				.warning(
					"[AutoBone] Target height loaded from skeleton (Make sure you reset before running!): "
						+ targetHeight
				);
		} else {
			// Otherwise if there is no skeleton available, attempt to get the
			// max HMD height from the recording
			float hmdHeight = frames.getMaxHmdHeight();
			if (hmdHeight <= 0.50f) {
				LogManager
					.warning(
						"[AutoBone] Max headset height detected (Value seems too low, did you not stand up straight while measuring?): "
							+ hmdHeight
					);
			} else {
				LogManager.info("[AutoBone] Max headset height detected: " + hmdHeight);
			}

			// Estimate target height from HMD height
			targetHeight = hmdHeight;
		}

		return targetHeight;
	}

	public AutoBoneResults processFrames(PoseFrames frames, Consumer<Epoch> epochCallback)
		throws AutoBoneException {
		return processFrames(frames, -1f, epochCallback);
	}

	public AutoBoneResults processFrames(
		PoseFrames frames,
		float targetHeight,
		Consumer<Epoch> epochCallback
	) throws AutoBoneException {
		return processFrames(frames, true, targetHeight, epochCallback);
	}

	public AutoBoneResults processFrames(
		PoseFrames frames,
		boolean calcInitError,
		float targetHeight,
		Consumer<Epoch> epochCallback
	) throws AutoBoneException {
		final int frameCount = frames.getMaxFrameCount();

		List<PoseFrameTracker> trackers = frames.getTrackers();
		reloadConfigValues(trackers); // Reload configs and detect chest tracker
										// from the first frame

		final PoseFrameSkeleton skeleton1 = new PoseFrameSkeleton(
			trackers,
			null
		);
		final PoseFrameSkeleton skeleton2 = new PoseFrameSkeleton(
			trackers,
			null
		);

		EnumMap<BoneType, Float> intermediateOffsets = new EnumMap<BoneType, Float>(
			offsets
		);

		AutoBoneTrainingStep trainingStep = new AutoBoneTrainingStep(
			targetHeight,
			skeleton1,
			skeleton2,
			frames,
			intermediateOffsets
		);

		// If target height isn't specified, auto-detect
		if (targetHeight < 0f) {
			targetHeight = getTargetHeight(frames);
		}

		// Epoch loop, each epoch is one full iteration over the full dataset
		for (int epoch = calcInitError ? -1 : 0; epoch < numEpochs; epoch++) {
			float sumError = 0f;
			int errorCount = 0;

			float adjustRate = epoch >= 0
				? (initialAdjustRate * FastMath.pow(adjustRateMultiplier, epoch))
				: 0f;

			int[] randomFrameIndices = null;
			if (randomizeFrameOrder) {
				randomFrameIndices = new int[frameCount];

				int zeroPos = -1;
				for (int i = 0; i < frameCount; i++) {
					int index = rand.nextInt(frameCount);

					if (i > 0) {
						while (index == zeroPos || randomFrameIndices[index] > 0) {
							index = rand.nextInt(frameCount);
						}
					} else {
						zeroPos = index;
					}

					randomFrameIndices[index] = i;
				}
			}

			// Iterate over the frames using a cursor and an offset for
			// comparing frames a
			// certain number of frames apart
			for (
				int cursorOffset = minDataDistance; cursorOffset <= maxDataDistance
					&& cursorOffset < frameCount;
				cursorOffset++
			) {
				for (
					int frameCursor = 0; frameCursor < frameCount - cursorOffset;
					frameCursor += cursorIncrement
				) {
					int frameCursor2 = frameCursor + cursorOffset;

					applyConfig(skeleton1.skeletonConfig);
					skeleton2.skeletonConfig.setConfigs(skeleton1.skeletonConfig);

					if (randomizeFrameOrder) {
						trainingStep
							.setCursors(
								randomFrameIndices[frameCursor],
								randomFrameIndices[frameCursor2]
							);
					} else {
						trainingStep.setCursors(frameCursor, frameCursor2);
					}

					skeleton1.setCursor(trainingStep.getCursor1());
					skeleton2.setCursor(trainingStep.getCursor2());

					skeleton1.updatePose();
					skeleton2.updatePose();

					float totalLength = getLengthSum(offsets);
					float curHeight = sumSelectConfigs(heightOffsets, offsets);
					trainingStep.setCurrentHeight(curHeight);

					float errorDeriv = getErrorDeriv(trainingStep);
					float error = errorFunc(errorDeriv);

					// In case of fire
					if (Float.isNaN(error) || Float.isInfinite(error)) {
						// Extinguish
						LogManager
							.warning(
								"[AutoBone] Error value is invalid, resetting variables to recover"
							);
						reloadConfigValues(trackers);

						// Reset error sum values
						sumError = 0f;
						errorCount = 0;

						// Continue on new data
						continue;
					}

					// Store the error count for logging purposes
					sumError += errorDeriv;
					errorCount++;

					float adjustVal = error * adjustRate;

					// If there is no adjustment whatsoever, skip this
					if (adjustVal == 0f) {
						continue;
					}

					Vector3f slideLeft = skeleton2
						.getComputedTracker(TrackerRole.LEFT_FOOT).position
							.subtract(
								skeleton1.getComputedTracker(TrackerRole.LEFT_FOOT).position
							);

					Vector3f slideRight = skeleton2
						.getComputedTracker(TrackerRole.RIGHT_FOOT).position
							.subtract(
								skeleton1
									.getComputedTracker(TrackerRole.RIGHT_FOOT).position
							);

					intermediateOffsets.putAll(offsets);
					for (Entry<BoneType, Float> entry : offsets.entrySet()) {
						// Skip adjustment if the epoch is before starting (for
						// logging only)
						if (epoch < 0) {
							break;
						}

						float originalLength = entry.getValue();
						boolean isHeightVar = heightOffsets.contains(entry.getKey());

						float leftDotProduct = getDotProductDiff(
							skeleton1,
							skeleton2,
							entry.getKey(),
							false,
							slideLeft
						);

						float rightDotProduct = getDotProductDiff(
							skeleton1,
							skeleton2,
							entry.getKey(),
							true,
							slideRight
						);

						float dotLength = originalLength
							* ((leftDotProduct + rightDotProduct) / 2f);

						// Scale by the ratio for smooth adjustment and more
						// stable results
						float curAdjustVal = (adjustVal * -dotLength) / totalLength;
						float newLength = originalLength + curAdjustVal;

						// No small or negative numbers!!! Bad algorithm!
						if (newLength < 0.01f) {
							continue;
						}

						// Apply new offset length
						intermediateOffsets.put(entry.getKey(), newLength);
						applyConfig(skeleton1.skeletonConfig, intermediateOffsets);
						skeleton2.skeletonConfig.setConfigs(skeleton1.skeletonConfig);

						// Update the skeleton poses for the new offset length
						skeleton1.updatePose();
						skeleton2.updatePose();

						float newHeight = isHeightVar ? curHeight + curAdjustVal : curHeight;
						trainingStep.setCurrentHeight(newHeight);

						float newErrorDeriv = getErrorDeriv(trainingStep);

						if (newErrorDeriv < errorDeriv) {
							entry.setValue(newLength);
						}

						// Reset the length to minimize bias in other variables,
						// it's applied later
						intermediateOffsets.put(entry.getKey(), originalLength);
						applyConfig(skeleton1.skeletonConfig, intermediateOffsets);
						skeleton2.skeletonConfig.setConfigs(skeleton1.skeletonConfig);
					}

					if (scaleEachStep) {
						float stepHeight = sumSelectConfigs(heightOffsets, offsets);

						if (stepHeight > 0f) {
							float stepHeightDiff = targetHeight - stepHeight;
							for (Entry<BoneType, Float> entry : offsets.entrySet()) {
								// Only height variables
								if (
									entry.getKey() == BoneType.NECK
										|| !heightOffsets.contains(entry.getKey())
								)
									continue;

								float length = entry.getValue();

								// Multiply the diff by the length to height
								// ratio
								float adjVal = stepHeightDiff * (length / stepHeight);

								// Scale the length to fit the target height
								entry.setValue(Math.max(length + (adjVal / 2f), 0.01f));
							}
						}
					}
				}
			}

			// Calculate average error over the epoch
			float avgError = errorCount > 0 ? sumError / errorCount : -1f;
			LogManager.info("[AutoBone] Epoch " + (epoch + 1) + " average error: " + avgError);

			applyConfig(legacyConfigs);
			if (epochCallback != null) {
				epochCallback.accept(new Epoch(epoch + 1, numEpochs, avgError, legacyConfigs));
			}
		}

		float finalHeight = sumSelectConfigs(heightOffsets, offsets);
		LogManager
			.info(
				"[AutoBone] Target height: "
					+ targetHeight
					+ " New height: "
					+ finalHeight
			);

		return new AutoBoneResults(finalHeight, targetHeight, legacyConfigs);
	}

	protected float getErrorDeriv(AutoBoneTrainingStep trainingStep) throws AutoBoneException {
		float totalError = 0f;
		float sumWeight = 0f;

		if (slideErrorFactor > 0f) {
			totalError += slideError.getStepError(trainingStep) * slideErrorFactor;
			sumWeight += slideErrorFactor;
		}

		if (offsetSlideErrorFactor > 0f) {
			totalError += offsetSlideError.getStepError(trainingStep) * offsetSlideErrorFactor;
			sumWeight += offsetSlideErrorFactor;
		}

		if (footHeightOffsetErrorFactor > 0f) {
			totalError += footHeightOffsetError.getStepError(trainingStep)
				* footHeightOffsetErrorFactor;
			sumWeight += footHeightOffsetErrorFactor;
		}

		if (bodyProportionErrorFactor > 0f) {
			totalError += bodyProportionError.getStepError(trainingStep)
				* bodyProportionErrorFactor;
			sumWeight += bodyProportionErrorFactor;
		}

		if (heightErrorFactor > 0f) {
			totalError += heightError.getStepError(trainingStep) * heightErrorFactor;
			sumWeight += heightErrorFactor;
		}

		if (positionErrorFactor > 0f) {
			totalError += positionError.getStepError(trainingStep) * positionErrorFactor;
			sumWeight += positionErrorFactor;
		}

		if (positionOffsetErrorFactor > 0f) {
			totalError += positionOffsetError.getStepError(trainingStep)
				* positionOffsetErrorFactor;
			sumWeight += positionOffsetErrorFactor;
		}

		return sumWeight > 0f ? totalError / sumWeight : 0f;
	}

	public String getLengthsString() {
		final StringBuilder configInfo = new StringBuilder();
		this.offsets.forEach((key, value) -> {
			if (configInfo.length() > 0) {
				configInfo.append(", ");
			}

			configInfo.append(key.toString() + ": " + StringUtils.prettyNumber(value * 100f, 2));
		});

		return configInfo.toString();
	}

	public void saveRecording(PoseFrames frames) {
		if (saveDir.isDirectory() || saveDir.mkdirs()) {
			File saveRecording;
			int recordingIndex = 1;
			do {
				saveRecording = new File(saveDir, "ABRecording" + recordingIndex++ + ".pfr");
			} while (saveRecording.exists());

			LogManager
				.info("[AutoBone] Exporting frames to \"" + saveRecording.getPath() + "\"...");
			if (PoseFrameIO.writeToFile(saveRecording, frames)) {
				LogManager
					.info(
						"[AutoBone] Done exporting! Recording can be found at \""
							+ saveRecording.getPath()
							+ "\"."
					);
			} else {
				LogManager
					.severe(
						"[AutoBone] Failed to export the recording to \""
							+ saveRecording.getPath()
							+ "\"."
					);
			}
		} else {
			LogManager
				.severe(
					"[AutoBone] Failed to create the recording directory \""
						+ saveDir.getPath()
						+ "\"."
				);
		}
	}

	public List<Pair<String, PoseFrames>> loadRecordings() {
		List<Pair<String, PoseFrames>> recordings = new FastList<Pair<String, PoseFrames>>();
		if (loadDir.isDirectory()) {
			File[] files = loadDir.listFiles();
			if (files != null) {
				for (File file : files) {
					if (
						file.isFile()
							&& org.apache.commons.lang3.StringUtils
								.endsWithIgnoreCase(file.getName(), ".pfr")
					) {
						LogManager
							.info(
								"[AutoBone] Detected recording at \""
									+ file.getPath()
									+ "\", loading frames..."
							);
						PoseFrames frames = PoseFrameIO.readFromFile(file);

						if (frames == null) {
							LogManager
								.severe("Reading frames from \"" + file.getPath() + "\" failed...");
						} else {
							recordings.add(Pair.of(file.getName(), frames));
						}
					}
				}
			}
		}

		return recordings;
	}

	public class Epoch {

		public final int epoch;
		public final int totalEpochs;
		public final float epochError;
		public final EnumMap<SkeletonConfigValue, Float> configValues;

		public Epoch(
			int epoch,
			int totalEpochs,
			float epochError,
			EnumMap<SkeletonConfigValue, Float> configValues
		) {
			this.epoch = epoch;
			this.totalEpochs = totalEpochs;
			this.epochError = epochError;
			this.configValues = configValues;
		}

		@Override
		public String toString() {
			return "Epoch: " + epoch + ", Epoch Error: " + epochError;
		}
	}

	public class AutoBoneResults {

		public final float finalHeight;
		public final float targetHeight;
		public final EnumMap<SkeletonConfigValue, Float> configValues;

		public AutoBoneResults(
			float finalHeight,
			float targetHeight,
			EnumMap<SkeletonConfigValue, Float> configValues
		) {
			this.finalHeight = finalHeight;
			this.targetHeight = targetHeight;
			this.configValues = configValues;
		}

		public float getHeightDifference() {
			return FastMath.abs(targetHeight - finalHeight);
		}
	}
}
