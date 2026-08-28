/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.internal

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepeatedFailureLogGateTest {
    @Test
    fun `repeated draw failure logs once until a successful draw`() {
        val gate = RepeatedFailureLogGate()

        assertTrue(gate.shouldLog(IllegalStateException("software redrawer")))
        assertFalse(gate.shouldLog(IllegalStateException("software redrawer")))

        gate.reset()

        assertTrue(gate.shouldLog(IllegalStateException("software redrawer")))
    }

    @Test
    fun `a changed failure is reported without waiting for reset`() {
        val gate = RepeatedFailureLogGate()

        assertTrue(gate.shouldLog(IllegalStateException("missing GLX context")))
        assertTrue(gate.shouldLog(IllegalStateException("missing GLX drawable")))
    }
}
