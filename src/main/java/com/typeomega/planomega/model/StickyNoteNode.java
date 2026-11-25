package com.typeomega.planomega.model;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Freeform annotation card placed on the planner canvas.
 */
public record StickyNoteNode(
        String id,
        double x,
        double y,
        double width,
        double height,
        @Nullable String groupId,
        String title,
        String text,
        int color
) implements PlanNode {

    public static final int DEFAULT_COLOR = 0xFFFEF08A;
    public static final double DEFAULT_WIDTH = 180.0;
    public static final double DEFAULT_HEIGHT = 110.0;

    public StickyNoteNode {
        Objects.requireNonNull(id, "id");
        title = title == null ? "" : title;
        text = text == null ? "" : text;
        if (width <= 0) {
            width = DEFAULT_WIDTH;
        }
        if (height <= 0) {
            height = DEFAULT_HEIGHT;
        }
        if (color == 0) {
            color = DEFAULT_COLOR;
        }
    }

    @Override
    public StickyNoteNode withPosition(double x, double y) {
        return new StickyNoteNode(id, x, y, width, height, groupId, title, text, color);
    }

    @Override
    public StickyNoteNode withSize(double width, double height) {
        return new StickyNoteNode(id, x, y, width, height, groupId, title, text, color);
    }

    @Override
    public StickyNoteNode withGroupId(@Nullable String groupId) {
        return new StickyNoteNode(id, x, y, width, height, groupId, title, text, color);
    }

    public StickyNoteNode withContent(String title, String text, int color) {
        return new StickyNoteNode(id, x, y, width, height, groupId, title, text, color);
    }
}
