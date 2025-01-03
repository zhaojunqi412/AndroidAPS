package app.aaps.pump.equil.manager.command

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.pump.equil.database.EquilHistoryRecord
import app.aaps.pump.equil.manager.EquilManager
import app.aaps.pump.equil.manager.Utils
import java.lang.StringBuilder

class CmdBasalGet(
    var profile: Profile,
    aapsLogger: AAPSLogger,
    sp: SP,
    equilManager: EquilManager
) : BaseSetting(System.currentTimeMillis(), aapsLogger, sp, equilManager) {

    override fun getFirstData(): ByteArray {
        val indexByte = Utils.intToBytes(pumpReqIndex)
        val data2 = byteArrayOf(0x02, 0x02)
        val data = Utils.concat(indexByte, data2)
        pumpReqIndex++
        return data
    }

    override fun getNextData(): ByteArray {
        val indexByte = Utils.intToBytes(pumpReqIndex)
        val data2 = byteArrayOf(0x00, 0x02, 0x01)
        val data = Utils.concat(indexByte, data2)
        pumpReqIndex++
        return data
    }

    override fun decodeConfirmData(data: ByteArray) {
        aapsLogger.debug(LTag.PUMPCOMM, "CmdBasalGet==" + Utils.bytesToHex(data))
        val currentBasal = StringBuilder()
        for (i in 0..23) {
            var value = profile.getBasalTimeFromMidnight(i * 60 * 60)
            value = value / 2f
            val bs = Utils.basalToByteArray2(value)
            currentBasal.append(Utils.bytesToHex(bs))
            currentBasal.append(Utils.bytesToHex(bs))
        }
        val rspByte = data.copyOfRange(6, data.size)
        val rspBasal = Utils.bytesToHex(rspByte)
        aapsLogger.debug(LTag.PUMPCOMM, "CmdBasalGet==$currentBasal====\n==$rspBasal")
        synchronized(this) {
            cmdStatus = true
            enacted = currentBasal.toString() == rspBasal
            (this as Object).notify()
        }
    }

    override fun getEventType(): EquilHistoryRecord.EventType? {
        return null
    }
}
