package qouteall.imm_ptl.core.render.optimization;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GLCapabilities;

import java.util.function.Consumer;

/**
 * Pre-allocated buffer and vertex-array objects handed out by {@code GlStateManager._glGenBuffers}
 * and {@code _glGenVertexArrays}.
 *
 * <p>The pool must hold real OBJECTS, not just reserved names: a {@code glGen*} name becomes an
 * object only when first bound, and direct-state-access callers (Veil's {@code glNamedBuffer*},
 * {@code glVertexArray*}) never bind, so they would get {@code GL_INVALID_OPERATION}. With DSA
 * available the pool is filled by {@code glCreate*}, which returns initialized objects; without
 * DSA no caller can use DSA and {@code glGen*} names are what the driver hands out anyway.
 */
public class GLResourceCache {

	private final Consumer<int[]> creator;
	private final Consumer<int[]> generator;
	private final IntList ids = new IntArrayList();

	public static final GLResourceCache bufferCache = new GLResourceCache(GL45::glCreateBuffers, GL15::glGenBuffers);
	public static final GLResourceCache vertexArrayCache =
		new GLResourceCache(GL45::glCreateVertexArrays, GL30::glGenVertexArrays);

	private GLResourceCache(Consumer<int[]> creator, Consumer<int[]> generator) {
		this.creator = creator;
		this.generator = generator;
	}

	public static void init() {
	}

	public int getNewResourceId() {
		if (ids.isEmpty()) reserve(1000);
		return ids.removeInt(ids.size() - 1);
	}

	private void reserve(int num) {
		int[] buf = new int[num];
		(supportsDirectStateAccess() ? creator : generator).accept(buf);
		ids.addElements(ids.size(), buf);
	}

	private static boolean supportsDirectStateAccess() {
		GLCapabilities caps = GL.getCapabilities();
		return caps.OpenGL45 || caps.GL_ARB_direct_state_access;
	}
}
