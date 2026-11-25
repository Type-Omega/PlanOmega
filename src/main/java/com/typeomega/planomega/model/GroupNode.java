package com.typeomega.planomega.model;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Collapsible visual container grouping multiple child nodes on the canvas and in Mermaid exports.
 */
public record GroupNode(
        String id,
        double x,
        double y,
        double width,
        double height,
        @Nullable String groupId,
        String title,
        int color,
        boolean collapsed,
        List<String> childNodeIds
) implements PlanNode {

    public static final int DEFAULT_COLOR = 0xFF1E3A5F;
    public static final double COLLAPSED_WIDTH = 200.0;
    public static final double COLLAPSED_HEIGHT = 48.0;
    public static final double HEADER_HEIGHT = 24.0;

    public GroupNode {
        Objects.requireNonNull(id, "id");
        title = title == null || title.isBlank() ? "Group" : title;
        childNodeIds = childNodeIds == null ? List.of() : List.copyOf(childNodeIds);
        if (width <= 0) {
            width = 260.0;
        }
        if (height <= 0) {
            height = 180.0;
        }
        if (color == 0) {
            color = DEFAULT_COLOR;
        }
    }

    @Override
    public GroupNode withPosition(double x, double y) {
        return new GroupNode(id, x, y, width, height, groupId, title, color, collapsed, childNodeIds);
    }

    @Override
    public GroupNode withSize(double width, double height) {
        return new GroupNode(id, x, y, width, height, groupId, title, color, collapsed, childNodeIds);
    }

    @Override
    public GroupNode withGroupId(@Nullable String groupId) {
        return new GroupNode(id, x, y, width, height, groupId, title, color, collapsed, childNodeIds);
    }

    public GroupNode withCollapsed(boolean collapsed) {
        return new GroupNode(id, x, y, width, height, groupId, title, color, collapsed, childNodeIds);
    }

    public GroupNode withChildNodeIds(List<String> childNodeIds) {
        return new GroupNode(id, x, y, width, height, groupId, title, color, collapsed, childNodeIds);
    }

    public GroupNode withTitle(String title) {
        return new GroupNode(id, x, y, width, height, groupId, title, color, collapsed, childNodeIds);
    }
}
