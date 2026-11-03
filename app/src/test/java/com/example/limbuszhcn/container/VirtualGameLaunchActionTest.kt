package com.example.limbuszhcn.container

import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证已有游戏任务始终优先尝试恢复。 */
class VirtualGameLaunchActionTest {
    @Test
    fun resumesOnlyWhenGameProcessAndTaskBothExist() {
        assertEquals(
            VirtualGameLaunchAction.RESUME_EXISTING,
            selectVirtualGameLaunchAction(gameRunning = true, taskPresent = true)
        )
    }

    @Test
    fun attemptsResumeBeforeDiscardingTaskAfterServerRestart() {
        assertEquals(
            VirtualGameLaunchAction.RESUME_EXISTING,
            selectVirtualGameLaunchAction(gameRunning = false, taskPresent = true)
        )
    }

    @Test
    fun startsFreshWhenNoTaskExists() {
        assertEquals(
            VirtualGameLaunchAction.START_FRESH,
            selectVirtualGameLaunchAction(gameRunning = false, taskPresent = false)
        )
        assertEquals(
            VirtualGameLaunchAction.START_FRESH,
            selectVirtualGameLaunchAction(gameRunning = true, taskPresent = false)
        )
    }
}
