package webserver.src;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * LoadBalancer — round-robin port selection using AtomicInteger.
 *
 * Networking concepts:
 *  • Round-robin load balancing — distributes requests cyclically across backends
 *  • AtomicInteger              — lock-free thread-safe counter
 *
 * Currently supports a single backend port per service, but is designed to
 * scale to multiple instances for horizontal scaling demonstrations.
 */
public class LoadBalancer {

    private final int[]         ports;
    private final AtomicInteger counter = new AtomicInteger(0);

    public LoadBalancer(int... ports) {
        if (ports.length == 0) throw new IllegalArgumentException("At least one port required");
        this.ports = ports.clone();
    }

    /**
     * Returns the next port in round-robin order.
     * Uses getAndIncrement() with modulo — thread-safe without locking.
     */
    public int nextPort() {
        int index = Math.abs(counter.getAndIncrement() % ports.length);
        return ports[index];
    }

    /** Returns all configured ports */
    public int[] getPorts() {
        return ports.clone();
    }

    /** Returns number of backend instances */
    public int size() {
        return ports.length;
    }
}
