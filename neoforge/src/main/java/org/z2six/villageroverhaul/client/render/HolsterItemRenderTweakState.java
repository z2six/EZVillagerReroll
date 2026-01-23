// neoforge/src/main/java/org/z2six/villageroverhaul/client/render/HolsterItemRenderTweakState.java
package org.z2six.villageroverhaul.client.render;

/**
 * Thread-local tweaks applied by {@code ItemRendererHolsterRollMixin} while rendering holstered loadout items.
 */
public final class HolsterItemRenderTweakState {

    private HolsterItemRenderTweakState() {}

    public enum Axis { X, Y, Z }

    private static final ThreadLocal<State> TL = ThreadLocal.withInitial(State::new);

    private static final class State {
        int depth = 0;
        float rollDeg = 0.0f;
        Axis rollAxis = Axis.Z;
    }

    public static void push(float rollDeg, Axis rollAxis) {
        State s = TL.get();
        s.depth++;
        s.rollDeg = rollDeg;
        s.rollAxis = (rollAxis == null) ? Axis.Z : rollAxis;
    }

    public static void pop() {
        State s = TL.get();
        s.depth = Math.max(0, s.depth - 1);
        if (s.depth == 0) {
            s.rollDeg = 0.0f;
            s.rollAxis = Axis.Z;
        }
    }

    public static boolean active() {
        State s = TL.get();
        return s.depth > 0 && s.rollDeg != 0.0f;
    }

    public static float rollDeg() {
        return TL.get().rollDeg;
    }

    public static Axis rollAxis() {
        return TL.get().rollAxis;
    }
}
