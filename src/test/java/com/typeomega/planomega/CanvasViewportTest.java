package com.typeomega.planomega;

import com.typeomega.planomega.client.ui.CanvasViewport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasViewportTest {

    @Test
    void cursorAnchoredZoomPreservesWorldPointUnderMouse() {
        CanvasViewport vp = new CanvasViewport();
        vp.setBounds(0, 30, 1280, 700);

        double mouseX = 842.0;
        double mouseY = 265.0;
        double beforeWx = vp.screenToWorldX(mouseX);
        double beforeWy = vp.screenToWorldY(mouseY);

        vp.zoomAtScreen(mouseX, mouseY, 1.35);

        assertEquals(beforeWx, vp.screenToWorldX(mouseX), 1e-6);
        assertEquals(beforeWy, vp.screenToWorldY(mouseY), 1e-6);
    }

    @Test
    void gridSnapRoundsToNearestGridStep() {
        assertEquals(40.0, CanvasViewport.snap(33.0, true), 1e-6);
        assertEquals(33.0, CanvasViewport.snap(33.0, false), 1e-6);
    }

    @Test
    void fitToBoundsPanZoomClampAndHitTestWork() {
        CanvasViewport vp = new CanvasViewport();
        vp.setBounds(10, 20, 1000, 600);
        assertTrue(vp.isPointInside(100, 100));
        assertFalse(vp.isPointInside(5, 100));

        vp.fitToBounds(0, 0, 2000, 1000);
        assertEquals(1000.0, vp.camX(), 1e-6);
        assertEquals(500.0, vp.camY(), 1e-6);
        assertTrue(vp.zoom() >= CanvasViewport.MIN_ZOOM && vp.zoom() <= 1.5);

        vp.fitToBounds(Double.NaN, 0, 100, 100);
        assertEquals(0.0, vp.camX(), 1e-6);
        assertEquals(1.0, vp.zoom(), 1e-6);

        vp.zoomAtScreen(500, 300, 1000.0);
        assertEquals(CanvasViewport.MAX_ZOOM, vp.zoom(), 1e-6);
        vp.zoomAtScreen(500, 300, 0.0001);
        assertEquals(CanvasViewport.MIN_ZOOM, vp.zoom(), 1e-6);

        vp.resetView();
        vp.panByScreenDelta(50, -30);
        assertEquals(-50.0, vp.camX(), 1e-6);
        assertEquals(30.0, vp.camY(), 1e-6);
    }
}
