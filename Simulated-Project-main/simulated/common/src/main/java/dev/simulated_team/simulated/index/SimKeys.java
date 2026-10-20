package dev.simulated_team.simulated.index;

import com.mojang.blaze3d.platform.InputConstants;
import com.simibubi.create.AllKeys;
import dev.simulated_team.simulated.Simulated;
import net.createmod.catnip.client.ConflictSafeKeyMapping;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public enum SimKeys {
	ROTATE_MODE("rotate_mode", GLFW.GLFW_KEY_TAB, "Physics Staff Rotate Mode"),
	SCROLL_UP("scroll_up", InputConstants.UNKNOWN.getValue(), "Scroll Up"),
	SCROLL_DOWN("scroll_down", InputConstants.UNKNOWN.getValue(), "Scroll Down"),
	;


	private KeyMapping keybind;
	private final String description;
	private final String translation;
	private final int key;
	private final boolean modifiable;
	private final boolean conflictSafe;

	SimKeys(final int defaultKey) {
		this("", defaultKey, "");
	}

	SimKeys(final String description, final int defaultKey, final String translation) {
		this(description, defaultKey, translation, false);
	}

	SimKeys(final String description, final int defaultKey, final String translation, final boolean conflictSafe) {
		this.description = Simulated.MOD_ID + ".keyinfo." + description;
		this.key = defaultKey;
		this.modifiable = !description.isEmpty();
		this.translation = translation;
		this.conflictSafe = conflictSafe;
	}

	public static void provideLang(final BiConsumer<String, String> consumer) {
		for (final SimKeys key : values())
			if (key.modifiable)
				consumer.accept(key.description, key.translation);
	}

	public static void registerTo(final Consumer<KeyMapping> consumer) {
		for (final SimKeys key : values()) {
			if (key.conflictSafe) {
				key.keybind = new ConflictSafeKeyMapping(key.description, key.key, Simulated.MOD_NAME);
			} else {
				key.keybind = new KeyMapping(key.description, key.key, Simulated.MOD_NAME);
			}
			if (!key.modifiable)
				continue;

			consumer.accept(key.keybind);
		}
	}

	public KeyMapping getKeybind() {
		return this.keybind;
	}

	public boolean isPressed() {
		if (!this.modifiable)
			return AllKeys.isKeyDown(this.key);
		return this.keybind != null && this.keybind.isDown();
	}

	public String getBoundKey() {
		return this.keybind.getTranslatedKeyMessage()
			.getString()
			.toUpperCase();
	}
}
