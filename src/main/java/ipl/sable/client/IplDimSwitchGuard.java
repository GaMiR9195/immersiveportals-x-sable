package ipl.sable.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.network.client.ClientSableInterpolationState;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.mixin.client.InterpolationClockAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Окно в одну секунду после того, как игрок сменил измерение через портал IP.
 *
 * <p>Русский комментарий (почему): при входе в измерение саб-уровни на мгновение дёргаются и
 * темнеют, а потом сразу становятся нормальными. Надёжно воспроизвести это без игры нельзя,
 * поэтому здесь три вещи сразу:
 * <ul>
 *   <li><b>Непрерывность позы.</b> Каждый кадр запоминается показанная поза каждого саб-уровня
 *   и её скорость. В окне после смены измерения, если новая поза Sable отскакивает от
 *   продолжения этого движения больше чем на 2 см (+25 % пути за кадр), разница не
 *   показывается рывком, а гасится за 0,2 с. Поза Sable при этом не меняется: правится только
 *   то, что рисуется.</li>
 *   <li><b>Небесный свет.</b> В первые 0,5 с масштаб небесного света саб-уровня не может
 *   упасть больше чем на 2 ступени ниже значения до перехода — короткий провал выглядел бы
 *   как чёрная вспышка.</li>
 *   <li><b>Диагностика.</b> Две строки {@code [IPL-DIM]} на переход: состояние часов обоих
 *   уровней в момент смены и итог окна (сколько раз сработали поправки, исправленный флаг
 *   затенения секций, скачки часов). По ним видно, какая из причин была на самом деле.</li>
 * </ul>
 * Всё, кроме счётчиков компиляции, — только поток отрисовки.
 */
public final class IplDimSwitchGuard {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable");

    private static final long WINDOW_NANOS = 1_000_000_000L;
    private static final long SKY_HOLD_NANOS = 500_000_000L;
    /** За сколько тиков гасится поправка позы (0,2 с). */
    private static final double DECAY_TICKS = 4.0;
    /** История старше 0,25 с не продолжает движение: саб-уровень тогда просто не был виден. */
    private static final double MAX_HISTORY_TICKS = 5.0;
    private static final double MIN_JUMP_BLOCKS = 0.02;
    private static final double MAX_JUMP_BLOCKS = 3.0;
    private static final double MIN_TURN_RAD = Math.toRadians(0.3);
    private static final double MAX_TURN_RAD = Math.toRadians(45.0);
    private static final int SKY_DROP = 3;

    // ---- Время кадра (поток отрисовки) ----
    private static long clientTicks;
    private static long frame;
    private static float framePt;
    private static long lastFrameNanos;

    // ---- Окно после смены измерения ----
    private static volatile long windowEndNanos;
    private static long skyHoldEndNanos;
    private static boolean summaryPending;
    private static String switchLabel = "";
    private static int framesInWindow;
    private static long maxFrameNanos;
    private static int poseFixes;
    private static double maxFixBlocks;
    private static double maxFixRad;
    private static String maxFixSub = "-";
    private static int skyHolds;
    private static final AtomicInteger SHADE_FIXES_TOTAL = new AtomicInteger();
    private static final AtomicInteger SHADE_FIXES = new AtomicInteger();
    private static final AtomicInteger COMPILES_SYNC = new AtomicInteger();
    private static final AtomicInteger COMPILES_ASYNC = new AtomicInteger();
    private static int clockAnomalies;
    private static final List<String> CLOCK_DETAILS = new ArrayList<>();

    private IplDimSwitchGuard() {}

    // ------------------------------------------------------------------------
    // Хуки
    // ------------------------------------------------------------------------

    /** {@code Minecraft.tick}, только когда игра не на паузе. */
    public static void onClientTick() {
        clientTicks++;
    }

    /** Начало кадра, перед {@code GameRenderer.render}: частичный тик кадра уже известен. */
    public static void beginFrame() {
        long now = System.nanoTime();
        frame++;
        framePt = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
        if (summaryPending) {
            if (now <= windowEndNanos) {
                framesInWindow++;
                if (lastFrameNanos != 0L) maxFrameNanos = Math.max(maxFrameNanos, now - lastFrameNanos);
            } else {
                summaryPending = false;
                logSummary();
            }
        }
        lastFrameNanos = now;
    }

    /** Конец {@code ClientTeleportationManager.changePlayerDimension}. */
    public static void onPlayerChangedDimension(ClientLevel from, ClientLevel to) {
        if (summaryPending) logSummary(); // второй переход внутри окна: итог первого отдельно
        long now = System.nanoTime();
        windowEndNanos = now + WINDOW_NANOS;
        skyHoldEndNanos = now + SKY_HOLD_NANOS;
        summaryPending = true;
        switchLabel = from.dimension().location() + " -> " + to.dimension().location();
        framesInWindow = 0;
        maxFrameNanos = 0L;
        poseFixes = 0;
        maxFixBlocks = 0.0;
        maxFixRad = 0.0;
        maxFixSub = "-";
        skyHolds = 0;
        SHADE_FIXES.set(0);
        COMPILES_SYNC.set(0);
        COMPILES_ASYNC.set(0);
        clockAnomalies = 0;
        CLOCK_DETAILS.clear();
        LOG.info("[IPL-DIM] switch {}: from {} | to {}", switchLabel, describe(from), describe(to));
    }

    private static boolean inWindow(long now) {
        return now <= windowEndNanos;
    }

    // ------------------------------------------------------------------------
    // Поза
    // ------------------------------------------------------------------------

    /** Состояние одного саб-уровня; живёт в его объекте ({@code ClientSubLevelMixin}). */
    public static final class Track {
        private long frame = -1L;
        private boolean hasHistory;
        private double time;
        private long transitFrame;
        private final Vector3d shownPos = new Vector3d();
        private final Quaterniond shownRot = new Quaterniond();
        /** Блоков за тик и радиан за тик (вектор поворота), сглажено по кадрам. */
        private final Vector3d velocity = new Vector3d();
        private final Vector3d spin = new Vector3d();
        private final Vector3d offsetPos = new Vector3d();
        private final Quaterniond offsetRot = new Quaterniond();
        private double offsetStart = Double.NaN;
        private double weight;
        private final Pose3d out = new Pose3d();
        private volatile int skyBefore = -1;
    }

    /**
     * Поза, которую надо нарисовать вместо {@code nativePose}. Вне окна и без активной поправки
     * возвращает саму {@code nativePose}; объект Sable никогда не меняется.
     *
     * @param pt частичный тик вызова или {@code NaN} — частичный тик кадра
     */
    public static Pose3dc smooth(ClientSubLevel sub, Track track, Pose3dc nativePose, float pt) {
        // Перегрузка без аргумента вызывает перегрузку с тиком: второй проход ничего не меняет.
        if (nativePose == track.out) return nativePose;
        if (!RenderSystem.isOnRenderThread()) return nativePose;
        double time = clientTicks + (Float.isNaN(pt) ? framePt : pt);
        if (track.frame != frame) {
            track.frame = frame;
            sample(sub, track, nativePose, time);
        }
        if (track.weight <= 0.0) return nativePose;
        Pose3d out = track.out.set(nativePose);
        out.position().fma(track.weight, track.offsetPos);
        Quaterniond partial = new Quaterniond().slerp(track.offsetRot, track.weight);
        partial.mul(nativePose.orientation(), out.orientation());
        return out;
    }

    /** Первый вызов позы в кадре: обновить историю и решить, нужна ли поправка. */
    private static void sample(ClientSubLevel sub, Track track, Pose3dc nativePose, double time) {
        long transitFrame = IplParentDimSync.frame(sub.getUniqueId());
        double dt = time - track.time;
        boolean history = track.hasHistory && transitFrame == track.transitFrame
            && dt > 1.0e-4 && dt <= MAX_HISTORY_TICKS;

        // Вес текущей поправки в этом кадре (1 → 0 за DECAY_TICKS).
        if (!Double.isNaN(track.offsetStart)) {
            double age = (time - track.offsetStart) / DECAY_TICKS;
            track.weight = age >= 1.0 ? 0.0 : 1.0 - smoothstep(Math.max(0.0, age));
            if (track.weight <= 0.0) track.offsetStart = Double.NaN;
        } else {
            track.weight = 0.0;
        }

        Vector3d shownPos = new Vector3d(nativePose.position()).fma(track.weight, track.offsetPos);
        Quaterniond shownRot = new Quaterniond().slerp(track.offsetRot, track.weight)
            .mul(nativePose.orientation());

        long now = System.nanoTime();
        if (history && inWindow(now)) {
            Vector3d expectedPos = new Vector3d(track.shownPos).fma(dt, track.velocity);
            Quaterniond expectedRot = rotationOf(track.spin, dt).mul(track.shownRot);
            double frameMove = track.velocity.length() * dt;
            double frameTurn = track.spin.length() * dt;
            double jump = shownPos.distance(expectedPos);
            double turn = angleBetween(shownRot, expectedRot);
            boolean teleport = jump > MAX_JUMP_BLOCKS || turn > MAX_TURN_RAD;
            if (teleport) {
                history = false;
                track.weight = 0.0;
                track.offsetStart = Double.NaN;
            } else if (jump > MIN_JUMP_BLOCKS + 0.25 * frameMove || turn > MIN_TURN_RAD + 0.25 * frameTurn) {
                // Показать продолжение движения, а разницу с Sable гасить с этого кадра.
                track.offsetPos.set(expectedPos).sub(nativePose.position());
                nativePose.orientation().conjugate(track.offsetRot).premul(expectedRot).normalize();
                track.offsetStart = time;
                track.weight = 1.0;
                shownPos.set(expectedPos);
                shownRot.set(expectedRot);
                notePoseFix(sub, jump, turn);
            }
        }

        if (history) {
            // Скорость по показанным позам: так она не зависит от того, отскочила ли поза Sable.
            Vector3d v = new Vector3d(shownPos).sub(track.shownPos).div(dt);
            Vector3d w = rotationVector(new Quaterniond(shownRot).mul(new Quaterniond(track.shownRot).conjugate()))
                .div(dt);
            track.velocity.lerp(v, 0.5);
            track.spin.lerp(w, 0.5);
        } else {
            track.velocity.zero();
            track.spin.zero();
        }
        track.shownPos.set(shownPos);
        track.shownRot.set(shownRot);
        track.time = time;
        track.transitFrame = transitFrame;
        track.hasHistory = true;
    }

    private static void notePoseFix(ClientSubLevel sub, double jump, double turn) {
        poseFixes++;
        if (jump > maxFixBlocks || turn > maxFixRad) maxFixSub = sub.getUniqueId().toString();
        maxFixBlocks = Math.max(maxFixBlocks, jump);
        maxFixRad = Math.max(maxFixRad, turn);
    }

    /** Масштаб небесного света: в первые 0,5 с после перехода не проваливается. */
    public static int holdSkyLight(Track track, int value) {
        long now = System.nanoTime();
        if (now > skyHoldEndNanos) {
            track.skyBefore = value;
            return value;
        }
        int before = track.skyBefore;
        if (before >= 0 && value <= before - SKY_DROP) {
            if (RenderSystem.isOnRenderThread()) skyHolds++;
            return before;
        }
        return value;
    }

    // ------------------------------------------------------------------------
    // Секции и часы (диагностика)
    // ------------------------------------------------------------------------

    /** Компиляция секции саб-уровня; {@code fixed} — флаг затенения Sable был неверным. */
    public static void noteSubLevelSectionCompile(boolean renderThread, boolean fixed) {
        if (fixed) SHADE_FIXES_TOTAL.incrementAndGet();
        if (!inWindow(System.nanoTime())) return;
        if (fixed) SHADE_FIXES.incrementAndGet();
        (renderThread ? COMPILES_SYNC : COMPILES_ASYNC).incrementAndGet();
    }

    /** Конец тика часов: указатель должен шагнуть примерно на 1. */
    public static void noteClockStep(ClientSableInterpolationState clock) {
        if (!inWindow(System.nanoTime())) return;
        double step = clock.mostRecentInterpolationTick - clock.lastInterpolationTick;
        if (Math.abs(step - 1.0) <= 0.25) return;
        noteClock(clock, String.format(Locale.ROOT, "step %.2f", step));
    }

    /** Начало {@code receiveInfo}: сброс часов или разрыв в тиках сервера. */
    public static void noteClockInfo(ClientSableInterpolationState clock, double mostRecent, int gameTick,
                                     boolean first, boolean wasStopped, boolean stopped) {
        if (!inWindow(System.nanoTime()) || gameTick < mostRecent) return;
        if (first || wasStopped && !stopped) {
            noteClock(clock, "reset to " + gameTick);
        } else if (gameTick - mostRecent > 2.0) {
            noteClock(clock, String.format(Locale.ROOT, "server gap %.0f", gameTick - mostRecent));
        }
    }

    private static void noteClock(ClientSableInterpolationState clock, String what) {
        if (!RenderSystem.isOnRenderThread()) return;
        clockAnomalies++;
        if (CLOCK_DETAILS.size() < 3) CLOCK_DETAILS.add(dimensionOf(clock) + " " + what);
    }

    // ------------------------------------------------------------------------
    // Лог
    // ------------------------------------------------------------------------

    private static void logSummary() {
        LOG.info(String.format(Locale.ROOT,
            "[IPL-DIM] after switch %s: frames=%d maxFrame=%.1fms poseFixes=%d (max %.1fcm %.2fdeg, %s)"
                + " skyHolds=%d shadeFixes=%d (total %d) subLevelCompiles sync=%d async=%d"
                + " clockAnomalies=%d %s",
            switchLabel, framesInWindow, maxFrameNanos / 1.0e6, poseFixes, maxFixBlocks * 100.0,
            Math.toDegrees(maxFixRad), maxFixSub, skyHolds, SHADE_FIXES.get(), SHADE_FIXES_TOTAL.get(),
            COMPILES_SYNC.get(), COMPILES_ASYNC.get(), clockAnomalies, CLOCK_DETAILS));
    }

    private static String describe(ClientLevel level) {
        ClientSubLevelContainer container = ClientSubLevelContainer.getContainer(level);
        if (container == null) return "no container";
        ClientSableInterpolationState clock = container.getInterpolation();
        InterpolationClockAccessor raw = (InterpolationClockAccessor) clock;
        return String.format(Locale.ROOT, "subLevels=%d clock{iT=%.2f M=%.2f ptr=%.2f est=%.3f %s}",
            container.getAllSubLevels().size(), raw.ipl$getInterpolationTick(), raw.ipl$getMostRecentTick(),
            clock.getTickPointer(), raw.ipl$getEstimatedServerTickSpeed(),
            clock.isStopped() ? "stopped" : "running");
    }

    private static String dimensionOf(ClientSableInterpolationState clock) {
        for (ClientLevel level : IplClientProjections.clientLevels()) {
            ClientSubLevelContainer container = ClientSubLevelContainer.getContainer(level);
            if (container != null && container.getInterpolation() == clock) {
                return level.dimension().location().toString();
            }
        }
        return "?";
    }

    // ------------------------------------------------------------------------
    // Математика
    // ------------------------------------------------------------------------

    private static double smoothstep(double x) {
        double t = Math.min(1.0, x);
        return t * t * (3.0 - 2.0 * t);
    }

    /** Поворот на вектор {@code spin * dt} (ось · угол). */
    private static Quaterniond rotationOf(Vector3d spin, double dt) {
        double angle = spin.length() * dt;
        if (angle < 1.0e-9) return new Quaterniond();
        return new Quaterniond().rotationAxis(angle, spin.x, spin.y, spin.z);
    }

    /** Вектор поворота (ось · угол) кватерниона по кратчайшему пути. */
    private static Vector3d rotationVector(Quaterniond q) {
        q.normalize();
        if (q.w < 0.0) q.set(-q.x, -q.y, -q.z, -q.w);
        double sinHalf = Math.sqrt(q.x * q.x + q.y * q.y + q.z * q.z);
        if (sinHalf < 1.0e-12) return new Vector3d();
        double angle = 2.0 * Math.atan2(sinHalf, q.w);
        return new Vector3d(q.x, q.y, q.z).mul(angle / sinHalf);
    }

    private static double angleBetween(Quaterniondc a, Quaterniondc b) {
        double dot = Math.abs(a.x() * b.x() + a.y() * b.y() + a.z() * b.z() + a.w() * b.w());
        return 2.0 * Math.acos(Math.min(1.0, dot));
    }
}
