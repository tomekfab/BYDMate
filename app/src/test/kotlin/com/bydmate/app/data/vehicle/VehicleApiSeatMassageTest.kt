package com.bydmate.app.data.vehicle

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.local.dao.VehicleWriteLogDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.nativestack.ParsReader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Driver seat massage (local voice only): one write on the mode fid, 2 = pulse, 1 = off.
 * Built on [WriteAllowlist.loadProduction] so the dev=1023 carve-out filter is exercised.
 */
// The channel stores are inert fixtures: the massage path never reads or sets a seat or window winner.
@Suppress("EmptyFunctionBlock")
class VehicleApiSeatMassageTest {

    private val helper: HelperClient = mockk()
    private val allowlist = WriteAllowlist.loadProduction { "{}" }

    private val seatStore = object : SeatChannelStore {
        override fun winner() = SeatChannel.UNKNOWN
        override fun setWinner(channel: SeatChannel) {}
        override fun reprobeExhausted() = false
        override fun claimReprobe() = true
    }

    private val windowStore = object : WindowChannelStore {
        override fun winner() = WindowChannel.UNKNOWN
        override fun setWinner(channel: WindowChannel) {}
        override fun ctrlCandidateAtMs() = 0L
        override fun setCtrlCandidateAtMs(ts: Long) {}
    }

    private val api: VehicleApi = VehicleApiImpl(
        mockk<ParsReader>(relaxed = true), mockk<AutoserviceClient>(relaxed = true), helper, allowlist,
        mockk<VehicleWriteLogDao>(relaxed = true), seatStore, windowStore,
    )

    private val on = "主驾座椅按摩打开"
    private val off = "主驾座椅按摩关闭"

    private fun verifyNoOtherMassageFid() {
        for (fid in listOf(1276190736, 1276190739, 1276190756)) {
            coVerify(exactly = 0) { helper.write(any(), fid, any()) }
        }
    }

    @Test fun `dispatch on writes pulse once to the driver mode fid`() = runTest {
        coEvery { helper.write(1023, 1276190752, 2) } returns true
        assertTrue(api.dispatch(on).isSuccess)
        coVerify(exactly = 1) { helper.write(1023, 1276190752, 2) }
        coVerify(exactly = 1) { helper.write(any(), any(), any()) }
        verifyNoOtherMassageFid()
    }

    @Test fun `dispatch off writes off once to the driver mode fid`() = runTest {
        coEvery { helper.write(1023, 1276190752, 1) } returns true
        assertTrue(api.dispatch(off).isSuccess)
        coVerify(exactly = 1) { helper.write(1023, 1276190752, 1) }
        coVerify(exactly = 1) { helper.write(any(), any(), any()) }
        verifyNoOtherMassageFid()
    }

    @Test fun `a refused write is Unsupported`() = runTest {
        coEvery { helper.write(any(), any(), any()) } returns false
        for (cmd in listOf(on, off)) {
            val result = api.dispatch(cmd)
            assertTrue(result.isFailure)
            assertTrue(cmd, result.exceptionOrNull() is VehicleWriteError.Unsupported)
        }
        verifyNoOtherMassageFid()
    }

    @Test fun `massage commands pass every dispatcher gate untouched`() {
        for (cmd in listOf(on, off)) {
            assertNull(cmd, ActionDispatcher.safetyBlockReason(cmd, null))
            assertFalse(cmd, ActionDispatcher.isWindowOpenCommand(cmd))
            assertFalse(cmd, ActionDispatcher.isSunroofOpenCommand(cmd))
            assertFalse(cmd, ActionDispatcher.isSpeedGated(cmd))
            assertFalse(cmd, ActionDispatcher.isDangerousAction(ActionDef(cmd, "", "param")))
        }
    }
}
