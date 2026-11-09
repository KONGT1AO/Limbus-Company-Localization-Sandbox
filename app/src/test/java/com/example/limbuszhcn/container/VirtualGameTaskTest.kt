package com.example.limbuszhcn.container

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 VirtualApp 后台游戏任务的识别规则。
 */
class VirtualGameTaskTest {

    /**
     * 目标 MIME type 和 ShadowActivity 同时命中时应识别为游戏任务。
     */
    @Test
    fun `matches limbus shadow task`() {
        assertTrue(
            isVirtualGameTask(
                packageName = "com.ProjectMoon.LimbusCompany",
                baseIntentType = "com.ProjectMoon.LimbusCompany/com.ProjectMoon.LimbusCompany.LimbusCompanyActivity",
                baseActivityClassName = "com.lody.virtual.client.stub.ShadowActivity\$P0",
                topActivityClassName = "com.lody.virtual.client.stub.ShadowActivity\$P0"
            )
        )
    }

    /**
     * 同宿主的汉化器主页不应被当作游戏任务。
     */
    @Test
    fun `rejects host launcher task`() {
        assertFalse(
            isVirtualGameTask(
                packageName = "com.ProjectMoon.LimbusCompany",
                baseIntentType = null,
                baseActivityClassName = "com.example.limbuszhcn.MainActivity",
                topActivityClassName = "com.example.limbuszhcn.MainActivity"
            )
        )
    }

    /**
     * 其他虚拟包的 ShadowActivity 任务不应被误恢复。
     */
    @Test
    fun `rejects another virtual package`() {
        assertFalse(
            isVirtualGameTask(
                packageName = "com.ProjectMoon.LimbusCompany",
                baseIntentType = "com.google.android.gms/.SomeActivity",
                baseActivityClassName = "com.lody.virtual.client.stub.ShadowActivity\$P0",
                topActivityClassName = "com.lody.virtual.client.stub.ShadowActivity\$P0"
            )
        )
    }

    /**
     * 当 Intent MIME Type 丢失但具有会话 task id 或 UnityPlayerActivity 时仍可精准识别。
     */
    @Test
    fun `matches limbus task by session task id or unity player activity`() {
        // 会话 taskId 匹配
        assertTrue(
            isVirtualGameTask(
                packageName = "com.ProjectMoon.LimbusCompany",
                baseIntentType = null,
                baseActivityClassName = "com.example.limbuszhcn.MainActivity",
                topActivityClassName = "com.example.limbuszhcn.MainActivity",
                taskId = 10086,
                sessionTaskId = 10086
            )
        )
        // UnityPlayerActivity 匹配
        assertTrue(
            isVirtualGameTask(
                packageName = "com.ProjectMoon.LimbusCompany",
                baseIntentType = null,
                baseActivityClassName = "com.ProjectMoon.LimbusCompany.UnityPlayerActivity",
                topActivityClassName = "com.ProjectMoon.LimbusCompany.UnityPlayerActivity"
            )
        )
    }
}
