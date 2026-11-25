package com.typeomega.planomega.model;

import org.jetbrains.annotations.Nullable;

/**
 * Base sealed contract for all immutable nodes stored in a {@link PlanGraph}.
 */
public sealed interface PlanNode permits ProcessNode, StickyNoteNode, GroupNode {

    String id();

    double x();

    double y();

    double width();

    double height();

    @Nullable String groupId();

    PlanNode withPosition(double x, double y);

    PlanNode withSize(double width, double height);

    PlanNode withGroupId(@Nullable String groupId);
}
