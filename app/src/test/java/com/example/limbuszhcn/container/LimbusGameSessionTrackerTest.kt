package com.example.limbuszhcn.container

import com.lody.virtual.client.hook.delegate.LimbusGameSessionTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 LimbusGameSessionTracker 会话状态结构和校验逻辑。
 */
class LimbusGameSessionTrackerTest {

    @Test
    fun `valid session info returns true`() {
        val session = LimbusGameSessionTracker.SessionInfo(12345, 0, 789, System.currentTimeMillis())
        assertTrue(session.isValid)
        assertEquals(12345, session.pid)
        assertEquals(0, session.vpid)
        assertEquals(789, session.taskId)
    }

    @Test
    fun `invalid session info returns false`() {
        val zeroPid = LimbusGameSessionTracker.SessionInfo(0, 0, 789, System.currentTimeMillis())
        assertFalse(zeroPid.isValid)

        val negativeVpid = LimbusGameSessionTracker.SessionInfo(12345, -1, 789, System.currentTimeMillis())
        assertFalse(negativeVpid.isValid)

        val invalidTaskId = LimbusGameSessionTracker.SessionInfo(12345, 0, 0, System.currentTimeMillis())
        assertFalse(invalidTaskId.isValid)
    }
}
