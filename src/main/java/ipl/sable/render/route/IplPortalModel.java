package ipl.sable.render.route;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Vector3d;

/** What the level passes of one render frame draw of one block-entity model. */
interface IplPortalModel {

	/** Whether any part of the model is in {@code level}. */
	boolean drawsIn(Level level);

	/** World box of the parts in {@code level}; null when unknown (never culled). */
	@Nullable
	AABB boundsIn(Level level);

	/** Writes the parts in {@code level}, relative to {@code camera}, into {@code buffers}. */
	void emit(Level level, MultiBufferSource buffers, Vec3 camera);

	/** {@code box} placed by {@code m}: the box around its eight transformed corners. */
	static AABB transform(AABB box, Matrix4d m) {
		double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
		double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
		Vector3d c = new Vector3d();
		for (int i = 0; i < 8; i++) {
			m.transformPosition((i & 1) == 0 ? box.minX : box.maxX, (i & 2) == 0 ? box.minY : box.maxY,
				(i & 4) == 0 ? box.minZ : box.maxZ, c);
			minX = Math.min(minX, c.x); minY = Math.min(minY, c.y); minZ = Math.min(minZ, c.z);
			maxX = Math.max(maxX, c.x); maxY = Math.max(maxY, c.y); maxZ = Math.max(maxZ, c.z);
		}
		return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
	}
}
