/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ocr;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import de.schliweb.makeacopy.BuildConfig;
import org.junit.Test;

/**
 * Paddle-Flavor JVM-Tests für die Routing-Bedingungen aus {@code OCRHelper.selectEngine()}.
 *
 * <p>Echte Engine-Erstellung in JVM-Tests ist nicht möglich (ORT lädt native Libraries beim
 * Init); diese Tests prüfen daher die deterministischen Vorbedingungen — Feature-Flag,
 * Sprach-Whitelist und Diagnostics-Reasons.
 */
public class PaddleEngineProviderTest {

    @Test
    public void paddleFlavor_featureFlagEnabled() {
        assertTrue(
                "FEATURE_PADDLE_OCR muss im paddle-Flavor true sein",
                BuildConfig.FEATURE_PADDLE_OCR);
    }

    // Hinweis: Die statische Sprach-Whitelist {eng, deu, latin} und das ABI-Gate
    // wurden mit der Multi-Model-Migration (Konzept §C.2) aus OCRHelper entfernt.
    // Sprachrouting übernimmt jetzt PaddleLanguageRouter; siehe PaddleLanguageRouterTest.

    @Test
    public void diagnostics_reasonsAreStableConstants() {
        assertNotNull(OcrBackendDiagnostics.Reason.DISABLED_BY_FLAG);
        assertNotNull(OcrBackendDiagnostics.Reason.UNSUPPORTED_ABI);
        assertNotNull(OcrBackendDiagnostics.Reason.UNSUPPORTED_LANG);
        assertNotNull(OcrBackendDiagnostics.Reason.TOGGLE_OFF);
        assertNotNull(OcrBackendDiagnostics.Reason.PADDLE_INIT_FAILED);
        assertNotNull(OcrBackendDiagnostics.Reason.PADDLE_OK);
    }

    @Test
    public void paddleFlavorProvider_isPresent() {
        // Im paddle-Flavor existiert die echte Provider-Klasse mit der create()-Methode.
        try {
            java.lang.reflect.Method m =
                    PaddleEngineProvider.class.getDeclaredMethod(
                            "create", android.content.Context.class, String.class);
            m.setAccessible(true);
            // Nicht aufrufen — null-Context würde sofort null liefern; wir prüfen nur
            // die Erreichbarkeit der Bridge im paddle-Flavor.
            assertNotNull(m);
        } catch (ReflectiveOperationException e) {
            throw new LinkageError("Provider.create nicht gefunden", e);
        }
    }

    @Test
    public void paddleFlavorProvider_releaseAllIsNoOpWhenIdle() {
        // Ohne initialisierte Runner darf releaseAll niemals werfen.
        PaddleEngineProvider.releaseAll(null);
    }
}
