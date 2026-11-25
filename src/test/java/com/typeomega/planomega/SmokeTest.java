package com.typeomega.planomega;

import org.junit.jupiter.api.Test;
import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.Optimisation;
import org.ojalgo.optimisation.Variable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeTest {

    @Test
    void ojAlgoSolverSanityCheck() {
        ExpressionsBasedModel model = new ExpressionsBasedModel();
        Variable x = model.addVariable("x").lower(0).weight(1);
        model.addExpression("demand").set(x, 2).lower(10);

        Optimisation.Result result = model.minimise();
        assertTrue(result.getState().isFeasible());
        assertEquals(5.0, result.getValue(), 1e-6);
    }
}
