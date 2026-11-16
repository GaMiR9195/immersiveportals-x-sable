package qouteall.imm_ptl.core.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;

/**
 * Compatibility with EntityCulling, which hides entities and block entities its ray tracer cannot
 * see from the player's camera.
 *
 * <ul>
 *   <li>A portal pass looks from another camera, often in another dimension, where those results
 *   mean nothing: the culling is switched off for every foreign world pass. The pass's own
 *   frustum and section culling still apply.</li>
 *   <li>Entities drawn in such a pass count as seen: in camera and not culled, the same state
 *   EntityCulling's tracer gives a visible entity (kept for its usual second). Its tracer looks
 *   from the player's camera only, so an entity seen through a portal is hidden for it; its tick
 *   culling would then skip the full tick (body turn, walk animation) while the head keeps
 *   interpolating.</li>
 *   <li>The tracer reads the player's level, not the level IP renders at that moment
 *   ({@code MixinEntityCullingBlockProvider}, {@code MixinEntityCullingModBase}).</li>
 * </ul>
 */
public final class EntityCullingCompat {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final boolean LOADED = ModList.get().isLoaded("entityculling");

	private static @Nullable Field instanceField;
	private static @Nullable Field configField;
	private static @Nullable Field skipEntitiesField;
	private static @Nullable Field skipBlockEntitiesField;
	private static @Nullable Class<?> cullableClass;
	private static @Nullable MethodHandle setOutOfCamera;
	private static @Nullable MethodHandle setCulled;
	private static boolean broken;

	private static int depth;
	private static @Nullable Object pausedConfig;
	private static boolean savedSkipEntities;
	private static boolean savedSkipBlockEntities;

	private EntityCullingCompat() {}

	public static void beginForeignView() {
		if (!LOADED || depth++ > 0) return;
		Object config = config();
		if (config == null) return;
		try {
			savedSkipEntities = skipEntitiesField.getBoolean(config);
			savedSkipBlockEntities = skipBlockEntitiesField.getBoolean(config);
			skipEntitiesField.setBoolean(config, true);
			skipBlockEntitiesField.setBoolean(config, true);
			pausedConfig = config;
		}
		catch (ReflectiveOperationException e) {
			disable(e);
		}
	}

	public static void endForeignView() {
		if (!LOADED || depth == 0 || --depth > 0) return;
		Object config = pausedConfig;
		pausedConfig = null;
		if (config == null) return;
		try {
			skipEntitiesField.setBoolean(config, savedSkipEntities);
			skipBlockEntitiesField.setBoolean(config, savedSkipBlockEntities);
		}
		catch (ReflectiveOperationException e) {
			disable(e);
		}
	}

	/** An entity drawn in a portal pass was seen. */
	public static void onEntityRendered(Entity entity) {
		if (depth == 0 || setOutOfCamera == null || setCulled == null || !cullableClass.isInstance(entity)) return;
		try {
			setOutOfCamera.invoke(entity, false);
			setCulled.invoke(entity, false);
		}
		catch (Throwable e) {
			setOutOfCamera = null;
			setCulled = null;
			LOGGER.warn("[Immersive Portals] EntityCulling tick culling compatibility disabled", e);
		}
	}

	/** The level of the player, which IP never swaps while rendering other dimensions. */
	public static ClientLevel getPlayerLevel(Minecraft client) {
		LocalPlayer player = client.player;
		return player != null && player.level() instanceof ClientLevel level ? level : client.level;
	}

	/** EntityCulling's live config, or null before it is initialized. */
	private static @Nullable Object config() {
		if (broken) return null;
		try {
			if (instanceField == null) {
				Class<?> base = Class.forName("dev.tr7zw.entityculling.EntityCullingModBase");
				configField = base.getField("config");
				Class<?> configClass = configField.getType();
				skipEntitiesField = configClass.getField("skipEntityCulling");
				skipBlockEntitiesField = configClass.getField("skipBlockEntityCulling");
				cullableClass = Class.forName("dev.tr7zw.entityculling.access.Cullable");
				setOutOfCamera = MethodHandles.publicLookup().findVirtual(
					cullableClass, "setOutOfCamera", MethodType.methodType(void.class, boolean.class)
				);
				setCulled = MethodHandles.publicLookup().findVirtual(
					cullableClass, "setCulled", MethodType.methodType(void.class, boolean.class)
				);
				instanceField = base.getField("instance");
			}
			Object instance = instanceField.get(null);
			return instance == null ? null : configField.get(instance);
		}
		catch (ReflectiveOperationException | RuntimeException e) {
			disable(e);
			return null;
		}
	}

	private static void disable(Exception e) {
		broken = true;
		setOutOfCamera = null;
		setCulled = null;
		LOGGER.warn("[Immersive Portals] EntityCulling compatibility disabled", e);
	}
}
