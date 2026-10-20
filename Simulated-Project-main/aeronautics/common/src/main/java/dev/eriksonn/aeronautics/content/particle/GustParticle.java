package dev.eriksonn.aeronautics.content.particle;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ryanhcode.sable.api.particle.ParticleSubLevelKickable;
import dev.ryanhcode.sable.mixinterface.particle.ParticleExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;
import org.joml.Quaterniondc;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class GustParticle extends TextureSheetParticle implements ParticleSubLevelKickable {

	private static final float FPS = 16.0f;
	private static final float FRAMES = 8.0f;

	private final Quaternionf orientation;
	private final Quaternionf renderOrientation = new Quaternionf();
	private final Quaternionf subLevelOrientation = new Quaternionf();

	protected GustParticle(final ClientLevel level, final double x, final double y, final double z, final Quaternionf orientation) {
		super(level, x, y, z);
		this.orientation = orientation.normalize();
		this.quadSize = 2;
		this.lifetime = (int) (FRAMES / FPS * 20) - 1;
		this.alpha = 0.25f;
	}

	@Override
	public void render(final VertexConsumer buffer, final Camera renderInfo, final float partialTicks) {
        this.renderOrientation.set(this.orientation);

		final ParticleExtension extension = (ParticleExtension) this;
		if (extension.sable$getTrackingSubLevel() instanceof final ClientSubLevel subLevel) {
			final Quaterniondc orientation1 = subLevel.renderPose().orientation();
            this.renderOrientation.premul(this.subLevelOrientation.set(orientation1));
		}
		this.renderRotatedQuad(buffer, renderInfo, this.renderOrientation, partialTicks);
	}

	@Override
	protected void renderRotatedQuad(final VertexConsumer buffer, final Quaternionf quaternion, final float x, final float y, final float z, final float partialTicks) {
		final float f = this.getQuadSize(partialTicks);
		final float f1 = this.getU0();
		final float f2 = this.getU1();
		final float f3 = this.getV0();
		final float f4 = this.getV1();
		final int i = this.getLightColor(partialTicks);
		this.renderVertex(buffer, quaternion, x, y, z, 1.0F, -1.0F, f, f2, f4, i);
		this.renderVertex(buffer, quaternion, x, y, z, 1.0F, 1.0F, f, f2, f3, i);
		this.renderVertex(buffer, quaternion, x, y, z, -1.0F, 1.0F, f, f1, f3, i);
		this.renderVertex(buffer, quaternion, x, y, z, -1.0F, -1.0F, f, f1, f4, i);

		this.renderVertex(buffer, quaternion, x, y, z, -1.0F, -1.0F, f, f1, f4, i);
		this.renderVertex(buffer, quaternion, x, y, z, -1.0F, 1.0F, f, f1, f3, i);
		this.renderVertex(buffer, quaternion, x, y, z, 1.0F, 1.0F, f, f2, f3, i);
		this.renderVertex(buffer, quaternion, x, y, z, 1.0F, -1.0F, f, f2, f4, i);
	}

	private void renderVertex(final VertexConsumer buffer, final Quaternionf quaternion, final float x, final float y, final float z, final float xOffset, final float yOffset, final float quadSize, final float u, final float v, final int packedLight) {
		final Vector3f vector3f = (new Vector3f(xOffset, yOffset, 0.0F)).rotate(quaternion).mul(quadSize).add(x, y, z);
		buffer.addVertex(vector3f.x(), vector3f.y(), vector3f.z()).setUv(u, v).setColor(this.rCol, this.gCol, this.bCol, this.alpha).setLight(packedLight);
	}

	@Override
	protected float getU0() {
		float offset = getFrameOffset(this.getFrame());
		return super.getU0() + offset;
	}

	@Override
	protected float getU1() {
		float offset = getFrameOffset((this.getFrame() + 1));
		return super.getU0() + offset;
	}

	private float getFrameOffset(int frame) {
		float width = this.sprite.getU1() - this.sprite.getU0();
		float frameWidth = width / FRAMES;
		return frameWidth * frame;
	}

	private int getFrame() {
		final float age = this.age / 20f;
		return (int) (age * FPS);
	}

	@Override
	public void pickSprite(final SpriteSet sprite) {
		super.pickSprite(sprite);
	}

	@Override
	public void tick() {
		super.tick();
	}

	@Override
	public ParticleRenderType getRenderType() {
		return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
	}

	@Override
	public boolean sable$shouldKickFromTracking() {
		return false;
	}

	@Override
	public boolean sable$shouldCollideWithTrackingSubLevel() {
		return false;
	}

	public static class Factory implements ParticleProvider<GustParticleData> {
		private final SpriteSet spriteSet;

		public Factory(final SpriteSet animatedSprite) {
			this.spriteSet = animatedSprite;
		}

		public Particle createParticle(final GustParticleData data, final ClientLevel worldIn, final double x, final double y, final double z,
									   final double xSpeed, final double ySpeed, final double zSpeed) {
			final GustParticle particle = new GustParticle(worldIn, x, y, z, data.orientation());
			particle.setSprite(this.spriteSet.get(worldIn.random));
			return particle;
		}

	}
}
