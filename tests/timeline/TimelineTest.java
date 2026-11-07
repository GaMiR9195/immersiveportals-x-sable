package ipl.sable.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class TimelineTest {
    private static int assertions;
    private record Packet(String body, int tick, String name) {}
    private static void check(boolean ok, String message) {
        assertions++;
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(IplSessionTimeline.renderTime(19, 20, 0.0f) == 19, "previous pose at frame start");
        check(IplSessionTimeline.renderTime(19, 20, 0.5f) == 19.5, "same partial tick as Sable");
        check(IplSessionTimeline.renderTime(19, 20, 1.0f) == 20, "current pose at frame end");
        check(!IplSessionTimeline.shown(20, 19.99), "do not remove a session at the logical endpoint");
        check(IplSessionTimeline.shown(20, 20), "apply removal on the displayed endpoint");
        check(IplSessionTimeline.shown(20, 21), "late packets apply immediately");
        check(!IplSessionTimeline.shown(120, 20), "maximum supported delay remains queued");
        check(IplSessionTimeline.shown(121, 20), "clock reset cannot leave packets stuck forever");
        var queue = new ArrayDeque<Packet>();
        queue.add(new Packet("A", 22, "A-sessions"));
        queue.add(new Packet("B", 20, "B-sessions"));
        queue.add(new Packet("A", 21, "A-labels")); // arrival order must win within A
        queue.add(new Packet("B", 20, "B-labels"));
        var applied = new ArrayList<String>();
        IplSessionTimeline.drain(queue, Packet::body, p -> p.tick <= 20, p -> applied.add(p.name));
        check(applied.equals(List.of("B-sessions", "B-labels")), "future body A must not block B");
        check(queue.size() == 2, "only applied entries removed");
        IplSessionTimeline.drain(queue, Packet::body, p -> p.tick <= 21, p -> applied.add(p.name));
        check(queue.size() == 2, "labels cannot overtake sessions of the same body");
        IplSessionTimeline.drain(queue, Packet::body, p -> p.tick <= 22, p -> applied.add(p.name));
        check(applied.equals(List.of("B-sessions", "B-labels", "A-sessions", "A-labels")), "per-body FIFO");
        check(queue.isEmpty(), "all due payloads drained");
        IplSessionTimeline.drain(queue, Packet::body, p -> true, p -> { throw new AssertionError("empty queue"); });
        check(queue.isEmpty(), "empty drain safe");
        System.out.println("PASS: " + assertions + " render-timeline assertions");
    }
}
