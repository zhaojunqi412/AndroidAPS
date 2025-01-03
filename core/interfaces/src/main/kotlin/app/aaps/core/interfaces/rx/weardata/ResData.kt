package app.aaps.core.interfaces.rx.weardata

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(InternalSerializationApi::class)
@Serializable
data class ResData(val value: ByteArray, val format: ResFormat)

typealias CwfResDataMap = MutableMap<String, ResData>

fun CwfResDataMap.isEquals(dataMap: CwfResDataMap) = (this.size == dataMap.size) && this.all { (key, resData) -> dataMap[key]?.value.contentEquals(resData.value) }
