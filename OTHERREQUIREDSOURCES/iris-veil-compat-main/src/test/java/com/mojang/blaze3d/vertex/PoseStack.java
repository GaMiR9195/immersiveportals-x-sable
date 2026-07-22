package com.mojang.blaze3d.vertex;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

public final class PoseStack {
    private final Pose pose = new Pose();

    public Pose last() {
        return pose;
    }

    public static final class Pose {
        private final Matrix4f pose = new Matrix4f();
        private final Matrix3f normal = new Matrix3f();

        public Matrix4f pose() {
            return pose;
        }

        public Matrix3f normal() {
            return normal;
        }
    }
}
