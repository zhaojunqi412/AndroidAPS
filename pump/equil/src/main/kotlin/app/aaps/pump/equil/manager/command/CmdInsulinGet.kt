package app.aaps.pump.equil.manager.command

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.pump.equil.database.EquilHistoryRecord
import app.aaps.pump.equil.manager.EquilManager
import app.aaps.pump.equil.manager.Utils

class CmdInsulinGet(
    aapsLogger: AAPSLogger,
    sp: SP,
    equilManager: EquilManager
) : BaseSetting(System.currentTimeMillis(), aapsLogger, sp, equilManager) {

    init {
        this.port = "0505"
    }

    override fun getFirstData(): ByteArray {
        val indexByte = Utils.intToBytes(pumpReqIndex)
        val data2 = byteArrayOf(0x02, 0x07)
        val data = Utils.concat(indexByte, data2)
        pumpReqIndex++
        return data
    }

    override fun getNextData(): ByteArray {
        val indexByte = Utils.intToBytes(pumpReqIndex)
        val data2 = byteArrayOf(0x00, 0x07, 0x01)
        val data3 = Utils.intToBytes(0)
        val data = Utils.concat(indexByte, data2, data3)
        pumpReqIndex++
        return data
    }

    override fun decodeConfirmData(data: ByteArray) {
        val insulin = data[6].toInt() and 0xff
        equilManager.setStartInsulin(insulin)
        equilManager.setCurrentInsulin(insulin)
        synchronized(this) {
            cmdStatus = true
            (this as Object).notify()
        }
    }

    override fun getEventType(): EquilHistoryRecord.EventType? = null
}
