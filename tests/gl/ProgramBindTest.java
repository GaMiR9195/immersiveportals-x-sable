package ipl.sable.render;

import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL41;
import qouteall.imm_ptl.core.render.FrontClipping;

/** IplProgramBindHook against GL/IP fixtures: which program, slot and space every upload hits. */
public final class ProgramBindTest {
    private static final int SLOTS = IplClipUniformPatcher.SLOTS;
    private static final int WORLD = 110, EYE = 120, INPUT = 130;
    private static int assertions;

    public static void main(String[] args) {
        check(IplProgramBindHook.subLevelUniformName(0).equals("ipl_subLevelClipEquation"),
            "element 0 by the bare array name");
        check(IplProgramBindHook.subLevelUniformName(3).equals("ipl_subLevelClipEquation[3]"),
            "element i by its indexed name");

        for (int p = 1; p <= 128; p++) IplProgramRegistry.register(p, "other");
        IplProgramRegistry.register(1, "rendertype_solid");
        IplProgramRegistry.register(2, "entity");

        slots(1, INPUT, SLOTS);
        int q = GL20.queries;
        slots(1, INPUT, SLOTS);
        check(q == GL20.queries, "location cache hit");
        slots(2, EYE, SLOTS);
        slots(3, WORLD, SLOTS);
        IplClipUniformPatcher.chunk = false;
        slots(1, WORLD, SLOTS); // BER on a rendertype_* program outside Sable's chunk bracket
        IplClipUniformPatcher.chunk = true;

        IplClipUniformPatcher.planes = 2;
        slots(1, INPUT, 2);

        ShaderInstance shader = new ShaderInstance();
        shader.id = 2;
        IplProgramBindHook.prepareShader(shader);
        check(shader.sub[0].value[0] == EYE && shader.sub[1].value[0] == EYE + 1,
            "Java uniforms synchronized before apply");
        check(shader.sub[2].value[0] == 0 && shader.sub[SLOTS - 1].value[3] == 1,
            "unused Java slots neutral");
        check(shader.portal.value[0] == 31, "IP slot-0 Java uniform synchronized from the cached eye equation");
        IplClipUniformPatcher.active = false;
        slots(2, EYE, 0);
        IplProgramBindHook.prepareShader(shader);
        boolean reset = true;
        for (int i = 0; i < SLOTS; i++) reset &= shader.sub[i].value[0] == 0 && shader.sub[i].value[3] == 1;
        check(reset, "Java uniforms reset outside bracket");

        GL11.enabled.add(GL11.GL_CLIP_PLANE0);
        GL41.writes.clear();
        IplProgramBindHook.onBind(1);
        check(GL41.writes.isEmpty(), "no portal or sub bracket no work");
        check(!GL11.enabled.contains(GL11.GL_CLIP_PLANE0), "stale slot 0 disabled outside a portal pass");
        check(enabledSlots() == 0, "no sub-level slot enabled outside a bracket");

        IplClipUniformPatcher.active = true;
        IplClipUniformPatcher.planes = SLOTS;
        GL41.writes.clear();
        IplProgramBindHook.onBind(1);
        check(GL41.writes.size() == SLOTS, "128 registered programs do not cause broadcast");
        check(enabledSlots() == SLOTS, "every sub-level slot enabled");
        check(!GL11.enabled.contains(GL11.GL_CLIP_PLANE0), "sub-level bracket leaves IP's slot 0 alone");

        // A program that declares fewer elements gets only its own slots switched on.
        GL11.enabled.clear();
        GL20.declared = 1;
        GL41.writes.clear();
        IplProgramBindHook.onBind(4);
        check(GL41.writes.size() == 1 && GL41.writes.get(0).loc() == 20, "only declared elements written");
        check(enabledSlots() == 1 && GL11.enabled.contains(GL30.GL_CLIP_DISTANCE0 + 1),
            "only declared slots enabled");
        GL20.declared = SLOTS;

        GL41.writes.clear();
        FrontClipping.isClippingEnabled = true;
        IplProgramBindHook.onBind(2);
        check(GL41.writes.size() == 1 + SLOTS, "portal plus independent sub cuts");
        check(GL41.writes.get(0).loc() == 10 && GL41.writes.get(0).a() == 21, "portal uses eye space");
        check(GL11.enabled.contains(GL11.GL_CLIP_PLANE0), "slot 0 enabled with portal clipping");
        IplClipUniformPatcher.scopedProgram = 2;
        IplClipUniformPatcher.scoped = new double[]{41, 0, 0, 1};
        GL41.writes.clear();
        IplProgramBindHook.onBind(2);
        check(GL41.writes.get(0).a() == 41, "scoped Sable chunk equation replaces IP's slot 0");
        IplClipUniformPatcher.scopedProgram = -1;
        FrontClipping.isClippingEnabled = false;

        IplProgramRegistry.unregister(1);
        check(!IplProgramRegistry.usesVanillaSubLevelInputSpace(1), "delete removes space identity");
        GL20.missing = true;
        IplProgramBindHook.forgetProgram(1);
        GL41.writes.clear();
        IplProgramBindHook.uploadSubLevel(1);
        check(GL41.writes.isEmpty(), "relink invalidates old locations");
        q = GL20.queries;
        IplProgramBindHook.uploadSubLevel(1);
        check(q == GL20.queries, "negative locations cached");
        GL20.missing = false;
        IplProgramBindHook.forgetProgram(1);
        slots(1, WORLD, SLOTS);
        GL41.writes.clear();
        IplProgramBindHook.uploadSubLevel(0);
        check(GL41.writes.isEmpty(), "program zero ignored");
        System.out.println("PASS: " + assertions + " program-bind fixture assertions");
    }

    /** {@code uploadSubLevel}: one write per slot, the first {@code live} in {@code space}. */
    private static void slots(int program, int space, int live) {
        GL41.writes.clear();
        IplProgramBindHook.uploadSubLevel(program);
        check(GL41.writes.size() == SLOTS, "one write per slot");
        for (int i = 0; i < SLOTS; i++) {
            GL41.Write write = GL41.writes.get(i);
            check(write.p() == program, "bound program only");
            check(write.loc() == 20 + i, "slot " + i + " at its own element");
            if (i < live) check(write.a() == space + i && write.d() == 1, "slot " + i + " in the right space");
            else check(write.a() == 0 && write.d() == 1, "slot " + i + " neutral");
        }
        check(GL41.writes.stream().noneMatch(w -> w.loc() == 10), "subcut cannot stomp portal slot");
    }

    private static int enabledSlots() {
        int n = 0;
        for (int i = 1; i <= SLOTS; i++) if (GL11.enabled.contains(GL30.GL_CLIP_DISTANCE0 + i)) n++;
        return n;
    }

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
