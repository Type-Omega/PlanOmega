package com.typeomega.planomega.client.ui;

/**
 * Infinite 2D camera viewport supporting pan, cursor-anchored zoom, snapping, and bounds fitting.
 */
public final class CanvasViewport {

    public static final double MIN_ZOOM = 0.15;
    public static final double MAX_ZOOM = 3.5;
    public static final double GRID_STEP = 20.0;

    private double camX = 0.0;
    private double camY = 0.0;
    private double zoom = 1.0;

    private int viewX = 0;
    private int viewY = 0;
    private int viewWidth = 800;
    private int viewHeight = 600;

    public void setBounds(int x, int y, int width, int height) {
        this.viewX = x;
        this.viewY = y;
        this.viewWidth = Math.max(1, width);
        this.viewHeight = Math.max(1, height);
    }

    public int viewX() {
        return viewX;
    }

    public int viewY() {
        return viewY;
    }

    public int viewWidth() {
        return viewWidth;
    }

    public int viewHeight() {
        return viewHeight;
    }

    public double camX() {
        return camX;
    }

    public double camY() {
        return camY;
    }

    public double zoom() {
        return zoom;
    }

    public double centerScreenX() {
        return viewX + viewWidth * 0.5;
    }

    public double centerScreenY() {
        return viewY + viewHeight * 0.5;
    }

    public double worldToScreenX(double wx) {
        return centerScreenX() + (wx - camX) * zoom;
    }

    public double worldToScreenY(double wy) {
        return centerScreenY() + (wy - camY) * zoom;
    }

    public double screenToWorldX(double sx) {
        return camX + (sx - centerScreenX()) / zoom;
    }

    public double screenToWorldY(double sy) {
        return camY + (sy - centerScreenY()) / zoom;
    }

    public void panByScreenDelta(double screenDx, double screenDy) {
        camX -= screenDx / zoom;
        camY -= screenDy / zoom;
    }

    public void zoomAtScreen(double screenX, double screenY, double multiplier) {
        double anchorX = screenToWorldX(screenX);
        double anchorY = screenToWorldY(screenY);
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * multiplier));
        double newWorldX = screenToWorldX(screenX);
        double newWorldY = screenToWorldY(screenY);
        camX += (anchorX - newWorldX);
        camY += (anchorY - newWorldY);
    }

    public void setCamera(double camX, double camY) {
        this.camX = camX;
        this.camY = camY;
    }

    public void resetView() {
        camX = 0.0;
        camY = 0.0;
        zoom = 1.0;
    }

    public void fitToBounds(double minX, double minY, double maxX, double maxY) {
        if (!Double.isFinite(minX) || !Double.isFinite(maxX)) {
            resetView();
            return;
        }
        double bw = Math.max(120.0, maxX - minX);
        double bh = Math.max(120.0, maxY - minY);
        camX = (minX + maxX) * 0.5;
        camY = (minY + maxY) * 0.5;
        double padW = viewWidth * 0.82;
        double padH = viewHeight * 0.82;
        zoom = Math.max(MIN_ZOOM, Math.min(1.5, Math.min(padW / bw, padH / bh)));
    }

    public boolean isPointInside(double sx, double sy) {
        return sx >= viewX && sx <= viewX + viewWidth && sy >= viewY && sy <= viewY + viewHeight;
    }

    public static double snap(double value, boolean enabled) {
        if (!enabled) {
            return value;
        }
        return Math.round(value / GRID_STEP) * GRID_STEP;
    }
}
