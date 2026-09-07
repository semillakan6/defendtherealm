package com.createdtr.defendtherealm.encounter;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Dependency-independent lifecycle. Only the server owner may mutate an encounter. */
public final class Encounter {
    public enum State { VALIDATING, SPAWNING, APPROACHING, ENGAGING, DESTROYING, CLEANING_UP, COMPLETED }
    public enum Reason { NONE, CANCELLED, INVALID_PLACEMENT, LOST_TARGET, WEAPON_FAILURE, DEFEATED,
        OBSTRUCTED, TIMEOUT, RECOVERY_FAILED, INTEGRATION_UNAVAILABLE }

    private final UUID id;
    private final Map<Long, Integer> weights;
    private final Set<Long> lost = new HashSet<>();
    private final Set<UUID> owned = new HashSet<>();
    private final int totalWeight;
    private State state = State.VALIDATING;
    private Reason reason = Reason.NONE;

    public Encounter(UUID id, Map<Long, Integer> weights) {
        this.id = Objects.requireNonNull(id);
        this.weights = Map.copyOf(weights);
        if (weights.isEmpty() || weights.values().stream().anyMatch(w -> w != 1 && w != 3)) {
            throw new IllegalArgumentException("Integrity requires structural (1) or functional (3) weights");
        }
        totalWeight = weights.values().stream().reduce(0, Math::addExact);
    }

    public UUID id() { return id; }
    public State state() { return state; }
    public Reason reason() { return reason; }
    public Map<Long, Integer> weights() { return weights; }
    public Set<Long> lost() { return Set.copyOf(lost); }
    public Set<UUID> owned() { return Set.copyOf(owned); }
    public int lostWeight() { return lost.stream().mapToInt(weights::get).sum(); }
    public int totalWeight() { return totalWeight; }
    public boolean combatEnabled() { return state == State.ENGAGING; }

    public void advance(State next) {
        boolean valid = switch (state) {
            case VALIDATING -> next == State.SPAWNING;
            case SPAWNING -> next == State.APPROACHING;
            case APPROACHING -> next == State.ENGAGING;
            case DESTROYING -> next == State.CLEANING_UP;
            case CLEANING_UP -> next == State.COMPLETED && owned.isEmpty();
            default -> false;
        };
        if (!valid) throw new IllegalStateException("Invalid encounter transition: " + state + " -> " + next);
        state = next;
    }

    public boolean recordLoss(long block) {
        if (state != State.APPROACHING && state != State.ENGAGING) return false;
        if (!weights.containsKey(block) || !lost.add(block)) return false;
        // Integer comparison avoids rounding at the 40% boundary.
        if ((long) lostWeight() * 5 >= (long) totalWeight * 2) terminate(Reason.DEFEATED);
        return true;
    }

    public void terminate(Reason why) {
        if (why == Reason.NONE) throw new IllegalArgumentException("Termination requires a reason");
        if (state == State.COMPLETED || state == State.CLEANING_UP) return;
        if (state == State.DESTROYING && why == Reason.DEFEATED) return;
        if (state != State.DESTROYING) reason = why;
        state = why == Reason.DEFEATED && state != State.DESTROYING
                ? State.DESTROYING : State.CLEANING_UP;
    }

    public void own(UUID object) {
        if (state == State.COMPLETED) throw new IllegalStateException("Completed encounter cannot own objects");
        owned.add(Objects.requireNonNull(object));
    }

    public boolean inherit(UUID child, UUID parent) {
        if (!owned.contains(parent) || state == State.COMPLETED) return false;
        return owned.add(Objects.requireNonNull(child));
    }

    /** Call only after confirmed removal, never just because an object is unloaded. */
    public void acknowledgeRemoval(UUID object) { owned.remove(object); }

    public static Encounter restore(UUID id, Map<Long, Integer> weights, Set<Long> lost,
            Set<UUID> owned, State state, Reason reason) {
        Encounter result = new Encounter(id, weights);
        if (!weights.keySet().containsAll(lost)) throw new IllegalArgumentException("Unknown integrity block");
        boolean terminal = state == State.DESTROYING || state == State.CLEANING_UP || state == State.COMPLETED;
        if (terminal != (reason != Reason.NONE)
                || (state == State.DESTROYING && reason != Reason.DEFEATED)
                || (state == State.COMPLETED && !owned.isEmpty())) {
            throw new IllegalArgumentException("Inconsistent persisted lifecycle");
        }
        result.lost.addAll(lost);
        result.owned.addAll(owned);
        result.state = Objects.requireNonNull(state);
        result.reason = Objects.requireNonNull(reason);
        if (!terminal && (long) result.lostWeight() * 5 >= (long) result.totalWeight * 2) {
            throw new IllegalArgumentException("Active encounter exceeds defeat threshold");
        }
        return result;
    }
}
