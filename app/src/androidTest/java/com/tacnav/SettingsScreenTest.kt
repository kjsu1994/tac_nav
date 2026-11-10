package com.tacnav

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun mapPanelCanBeHiddenAndLicensesOpenOnDemand() {
        compose.onNodeWithText("지도 중심").assertDoesNotExist()
        compose.onNodeWithContentDescription("지도 중심 좌표 패널").performClick()
        compose.onNodeWithText("지도 중심").assertIsDisplayed()
        compose.onNodeWithText("좌표 자릿수는 위치 정확도를 의미하지 않습니다.").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("지도 중심").assertIsDisplayed()
        compose.onNodeWithContentDescription("지도 중심 좌표 패널").performClick()
        compose.onNodeWithText("지도 중심").assertDoesNotExist()
        compose.onNodeWithContentDescription("지도 출처 및 라이선스").performClick()
        compose.onNodeWithText("지도 출처·라이선스").assertIsDisplayed()
        compose.onNodeWithText("닫기").performClick()
        compose.onNodeWithText("지도 출처·라이선스").assertDoesNotExist()
    }
    @Test fun coordinatePreferenceIsAppliedAndSurvivesRecreation() {
        val store=(compose.activity.application as TacApplication).settings
        val previous=runBlocking { store.flow.first() }
        try {
            compose.onNodeWithText("설정").performClick()
            compose.onNodeWithText("좌표 표시").assertExists()
            compose.onNodeWithText("도·분·초").performClick()
            compose.waitUntil(5000) { runBlocking { store.flow.first().coordinateFormat==CoordinateFormat.DMS } }
            compose.onNodeWithText("도·분·초").assertIsSelected()
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            compose.onNodeWithText("도·분·초").assertIsSelected()
            assertEquals(CoordinateFormat.DMS,runBlocking { store.flow.first().coordinateFormat })
        } finally { runBlocking { store.save(previous) } }
    }
}
