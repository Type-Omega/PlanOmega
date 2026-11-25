package com.typeomega.planomega.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Bounded immutable snapshot undo/redo stack for {@link PlanGraph}.
 */
public final class UndoHistory {

    private final int maxEntries;
    private final Deque<PlanGraph> undoStack = new ArrayDeque<>();
    private final Deque<PlanGraph> redoStack = new ArrayDeque<>();

    public UndoHistory(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be > 0");
        }
        this.maxEntries = maxEntries;
    }

    public void recordBeforeEdit(PlanGraph previousState) {
        Objects.requireNonNull(previousState, "previousState");
        if (undoStack.size() >= maxEntries) {
            undoStack.removeLast();
        }
        undoStack.push(previousState);
        redoStack.clear();
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public PlanGraph undo(PlanGraph currentState) {
        if (undoStack.isEmpty()) {
            return currentState;
        }
        redoStack.push(currentState);
        return undoStack.pop();
    }

    public PlanGraph redo(PlanGraph currentState) {
        if (redoStack.isEmpty()) {
            return currentState;
        }
        undoStack.push(currentState);
        return redoStack.pop();
    }

    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }
}
