package info.nightscout.pump.medtrum.util

import info.nightscout.pump.medtrum.comm.enums.ModelType
import info.nightscout.pump.medtrum.encryption.Crypt

class MedtrumSnUtil {

    fun getDeviceTypeFromSerial(serial: Long): ModelType {
        if (serial in 106000000..106999999) {
            return ModelType.INVALID
        }

        return when (Crypt().simpleDecrypt(serial)) {
            in 126000000..126999999 -> ModelType.MD0201
            in 127000000..127999999 -> ModelType.MD5201
            in 128000000..128999999 -> ModelType.MD8201
            in 130000000..130999999 -> ModelType.MD0202
            in 131000000..131999999 -> ModelType.MD5202
            in 148000000..148999999 -> ModelType.MD8301
            else                    -> ModelType.INVALID
        }
    }
}
