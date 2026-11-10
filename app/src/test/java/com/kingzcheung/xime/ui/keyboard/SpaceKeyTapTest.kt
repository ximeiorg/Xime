package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #1262：26 键全键盘上按住空格键横向拖动移光标后，抬手不应再补一个空格。
 *
 * [shouldCommitSpaceTap] 是空格键的点击判定出口：父层光标手势已接管（[LocalCursorGestureActive]）、
 * 已判为滑动或长按已触发时，都不算点击。
 */
class SpaceKeyTapTest {

    @Test
    fun plainTapCommitsSpace() {
        assertTrue(
            shouldCommitSpaceTap(
                longPressTriggered = false,
                swipeDirection = null,
                cursorGestureActive = false,
            )
        )
    }

    @Test
    fun cursorGestureVetoesTap() {
        assertFalse(
            shouldCommitSpaceTap(
                longPressTriggered = false,
                swipeDirection = null,
                cursorGestureActive = true,
            )
        )
    }

    @Test
    fun swipeVetoesTap() {
        listOf("left", "right", "up", "down").forEach { dir ->
            assertFalse(
                "swipe $dir should veto space",
                shouldCommitSpaceTap(
                    longPressTriggered = false,
                    swipeDirection = dir,
                    cursorGestureActive = false,
                )
            )
        }
    }

    @Test
    fun longPressVetoesTap() {
        assertFalse(
            shouldCommitSpaceTap(
                longPressTriggered = true,
                swipeDirection = null,
                cursorGestureActive = false,
            )
        )
    }
}
