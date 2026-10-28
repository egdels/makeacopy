/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ocr.paddle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit-Test für {@link PaddleLanguageRouter}: rein deterministisches String→String-Routing. */
public class PaddleLanguageRouterTest {

    @Test
    public void resolve_english() {
        assertEquals("en", PaddleLanguageRouter.resolveRecModel("eng"));
        assertEquals("en", PaddleLanguageRouter.resolveRecModel("EN"));
    }

    @Test
    public void resolve_latin_default_for_german_french_spanish() {
        assertEquals("latin", PaddleLanguageRouter.resolveRecModel("deu"));
        assertEquals("latin", PaddleLanguageRouter.resolveRecModel("fra"));
        assertEquals("latin", PaddleLanguageRouter.resolveRecModel("spa"));
        assertEquals("latin", PaddleLanguageRouter.resolveRecModel("por"));
    }

    @Test
    public void resolve_eslav_default_for_russian() {
        assertEquals("eslav", PaddleLanguageRouter.resolveRecModel("rus"));
        assertEquals("eslav", PaddleLanguageRouter.resolveRecModel("ukr"));
    }

    @Test
    public void resolve_cyrillic_general() {
        assertEquals("cyrillic", PaddleLanguageRouter.resolveRecModel("bul"));
        assertEquals("cyrillic", PaddleLanguageRouter.resolveRecModel("kaz"));
    }

    @Test
    public void resolve_arabic_family() {
        assertEquals("arabic", PaddleLanguageRouter.resolveRecModel("ara"));
        assertEquals("arabic", PaddleLanguageRouter.resolveRecModel("fas"));
    }

    @Test
    public void resolve_devanagari_family() {
        assertEquals("devanagari", PaddleLanguageRouter.resolveRecModel("hin"));
        assertEquals("devanagari", PaddleLanguageRouter.resolveRecModel("mar"));
    }

    @Test
    public void resolve_thai() {
        assertEquals("th", PaddleLanguageRouter.resolveRecModel("tha"));
    }

    @Test
    public void resolve_greek() {
        assertEquals("el", PaddleLanguageRouter.resolveRecModel("ell"));
        assertEquals("el", PaddleLanguageRouter.resolveRecModel("gre"));
        assertEquals("el", PaddleLanguageRouter.resolveRecModel("el"));
    }

    @Test
    public void resolve_chinese_japanese_korean_to_zh() {
        assertEquals("zh", PaddleLanguageRouter.resolveRecModel("chi_sim"));
        assertEquals("zh", PaddleLanguageRouter.resolveRecModel("chi_tra"));
        assertEquals("zh", PaddleLanguageRouter.resolveRecModel("jpn"));
        assertEquals("zh", PaddleLanguageRouter.resolveRecModel("kor"));
    }

    @Test
    public void resolve_direct_model_group_keys() {
        for (String mk : PaddleLanguageRouter.MODEL_KEYS) {
            assertEquals(mk, PaddleLanguageRouter.resolveRecModel(mk));
        }
    }

    @Test
    public void resolve_combined_priority_en_over_latin() {
        // en hat Vorrang vor latin
        assertEquals("en", PaddleLanguageRouter.resolveRecModel("eng+deu"));
        assertEquals("en", PaddleLanguageRouter.resolveRecModel("deu+eng"));
    }

    @Test
    public void resolve_combined_priority_latin_over_eslav() {
        // latin > eslav
        assertEquals("latin", PaddleLanguageRouter.resolveRecModel("deu+rus"));
    }

    @Test
    public void resolve_combined_priority_full_chain() {
        // alle gemischt: en gewinnt
        assertEquals("en", PaddleLanguageRouter.resolveRecModel("tha+ara+rus+deu+eng"));
        // ohne en: latin
        assertEquals("latin", PaddleLanguageRouter.resolveRecModel("tha+ara+rus+deu"));
        // ohne latin: eslav
        assertEquals("eslav", PaddleLanguageRouter.resolveRecModel("tha+ara+rus"));
        // ohne eslav: arabic gewinnt vor th
        assertEquals("arabic", PaddleLanguageRouter.resolveRecModel("tha+ara"));
        // ohne arabic: th gewinnt vor el
        assertEquals("th", PaddleLanguageRouter.resolveRecModel("ell+tha"));
    }

    @Test
    public void resolve_unknown_token_ignored() {
        // unbekanntes Token + bekanntes → bekanntes wird genutzt
        assertEquals("latin", PaddleLanguageRouter.resolveRecModel("xyz+deu"));
    }

    @Test
    public void resolve_only_unknown_returns_null() {
        assertNull(PaddleLanguageRouter.resolveRecModel("xyz"));
        assertNull(PaddleLanguageRouter.resolveRecModel("xyz+abc"));
    }

    @Test
    public void resolve_null_or_empty_returns_null() {
        assertNull(PaddleLanguageRouter.resolveRecModel(null));
        assertNull(PaddleLanguageRouter.resolveRecModel(""));
        assertNull(PaddleLanguageRouter.resolveRecModel("   "));
        assertNull(PaddleLanguageRouter.resolveRecModel("+"));
    }

    @Test
    public void assetBaseName_known_keys() {
        assertEquals("en_PP-OCRv5_mobile_rec", PaddleLanguageRouter.assetBaseName("en"));
        assertEquals("latin_PP-OCRv5_mobile_rec", PaddleLanguageRouter.assetBaseName("latin"));
        assertEquals("eslav_PP-OCRv5_mobile_rec", PaddleLanguageRouter.assetBaseName("eslav"));
        assertEquals("cyrillic_PP-OCRv5_mobile_rec", PaddleLanguageRouter.assetBaseName("cyrillic"));
        assertEquals("arabic_PP-OCRv5_mobile_rec", PaddleLanguageRouter.assetBaseName("arabic"));
        assertEquals("devanagari_PP-OCRv5_mobile_rec", PaddleLanguageRouter.assetBaseName("devanagari"));
        assertEquals("th_PP-OCRv5_mobile_rec", PaddleLanguageRouter.assetBaseName("th"));
        assertEquals("el_PP-OCRv5_mobile_rec", PaddleLanguageRouter.assetBaseName("el"));
        assertEquals("PP-OCRv5_mobile_rec", PaddleLanguageRouter.assetBaseName("zh"));
    }

    @Test
    public void assetBaseName_unknown_key_returns_null() {
        assertNull(PaddleLanguageRouter.assetBaseName("xyz"));
        assertNull(PaddleLanguageRouter.assetBaseName(null));
    }

    @Test
    public void modelKeys_match_assetBaseName_table() {
        // Jeder MODEL_KEYS-Eintrag muss einen Asset-Basisnamen haben.
        for (String mk : PaddleLanguageRouter.MODEL_KEYS) {
            String base = PaddleLanguageRouter.assetBaseName(mk);
            assertNotNull("missing asset base for " + mk, base);
            assertTrue("asset base must end with _rec or be PP-OCRv5_mobile_rec for " + mk,
                    base.endsWith("_PP-OCRv5_mobile_rec") || base.equals("PP-OCRv5_mobile_rec"));
        }
    }
}
