package com.xavier.kesu;

import org.junit.Test;
import static org.junit.Assert.*;

public class ThemeMetadataTest {
    @Test public void jsonReadsChineseNameAndDesigner() {
        ThemeMetadata info = ThemeMetadata.parse("{\"titles\":{\"fallback\":\"Default\",\"zh_CN\":\"山间来信\"},"
                + "\"designers\":{\"zh_CN\":\"小林\",\"fallback\":\"Lin\"}}");
        assertEquals("山间来信", info.title);
        assertEquals("小林", info.designer);
    }

    @Test public void jsonFallsBackWhenChineseIsEmpty() {
        ThemeMetadata info = ThemeMetadata.parse("{\"titles\":{\"en_US\":\"English\",\"zh_CN\":\" \",\"fallback\":\"默认名称\"},"
                + "\"designers\":{\"fallback\":\"默认作者\"}}");
        assertEquals("默认名称", info.title);
        assertEquals("默认作者", info.designer);
    }

    @Test public void jsonUsesNativeEscapesAndAllowsClosingBraceInsideName() {
        ThemeMetadata info = ThemeMetadata.parse("{\"titles\":{\"zh_CN\":\"\\u5c71\\u95f4} \\\"A\\\"\"},"
                + "\"designers\":{\"zh_CN\":\"A\\/B\"}}");
        assertEquals("山间} \"A\"", info.title);
        assertEquals("A/B", info.designer);
    }

    @Test public void xmlLocaleTagsPreferChineseAndReadCdata() {
        ThemeMetadata info = ThemeMetadata.parse("<meta><titles><en_US>English</en_US><zh_CN><![CDATA[晴 &amp; 雨]]></zh_CN></titles>"
                + "<designers><fallback>默认作者</fallback><zh_CN>作者甲</zh_CN></designers></meta>");
        assertEquals("晴 & 雨", info.title);
        assertEquals("作者甲", info.designer);
    }

    @Test public void xmlKeepsSupportForLocaleAndLanguageAttributes() {
        ThemeMetadata info = ThemeMetadata.parse("<meta><titles><item locale='fallback'>Default</item>"
                + "<item locale='zh_CN'>主题甲</item></titles><designers><item language='zh_CN'>设计师甲</item></designers></meta>");
        assertEquals("主题甲", info.title);
        assertEquals("设计师甲", info.designer);
    }

    @Test public void xmlFallbackBeatsFirstOtherLanguage() {
        ThemeMetadata info = ThemeMetadata.parse("<meta><titles><en_US>English</en_US><fallback>默认名称</fallback></titles></meta>");
        assertEquals("默认名称", info.title);
        assertEquals("", info.designer);
    }

    @Test public void missingChineseAndFallbackUseAvailableTranslation() {
        ThemeMetadata info = ThemeMetadata.parse("{\"titles\":{\"en_US\":\"Mountain\"},\"designers\":{\"en_US\":\"Lin\"}}");
        assertEquals("Mountain", info.title);
        assertEquals("Lin", info.designer);
    }

    @Test public void emptyOrMalformedMetadataDoesNotInventNames() {
        for (String text : new String[]{null, "", "not metadata", "{}", "{\"titles\":{\"zh_CN\":"}) {
            ThemeMetadata info = ThemeMetadata.parse(text);
            assertEquals("", info.title);
            assertEquals("", info.designer);
        }
    }

    @Test public void jsonDoesNotDisplayObjectsOrNullAsNames() {
        ThemeMetadata info = ThemeMetadata.parse("{\"titles\":{\"zh_CN\":null,\"fallback\":\"正确名称\"},"
                + "\"designers\":{\"zh_CN\":{},\"fallback\":\"正确作者\"}}");
        assertEquals("正确名称", info.title);
        assertEquals("正确作者", info.designer);
    }

    @Test public void groupsCanBeNestedAndJsonStringsCanContainCdata() {
        ThemeMetadata info = ThemeMetadata.parse("{\"resource\":{\"titles\":{\"zh_CN\":\"<![CDATA[主题甲]]>\"},"
                + "\"designers\":{\"zh_CN\":\"A &amp; B\"}}}");
        assertEquals("主题甲", info.title);
        assertEquals("A & B", info.designer);
    }
}
