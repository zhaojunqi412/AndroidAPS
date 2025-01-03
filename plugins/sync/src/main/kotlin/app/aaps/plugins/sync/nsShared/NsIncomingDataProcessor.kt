package app.aaps.plugins.sync.nsShared

import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.FD
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.IDs
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.nsclient.StoreDataForDb
import app.aaps.core.interfaces.objects.Instantiator
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileSource
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.core.interfaces.rx.events.EventNSClientNewLog
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.source.NSClientSource
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.Preferences
import app.aaps.core.nssdk.localmodel.entry.NSSgvV3
import app.aaps.core.nssdk.localmodel.food.NSFood
import app.aaps.core.nssdk.localmodel.treatment.NSBolus
import app.aaps.core.nssdk.localmodel.treatment.NSBolusWizard
import app.aaps.core.nssdk.localmodel.treatment.NSCarbs
import app.aaps.core.nssdk.localmodel.treatment.NSEffectiveProfileSwitch
import app.aaps.core.nssdk.localmodel.treatment.NSExtendedBolus
import app.aaps.core.nssdk.localmodel.treatment.NSOfflineEvent
import app.aaps.core.nssdk.localmodel.treatment.NSProfileSwitch
import app.aaps.core.nssdk.localmodel.treatment.NSTemporaryBasal
import app.aaps.core.nssdk.localmodel.treatment.NSTemporaryTarget
import app.aaps.core.nssdk.localmodel.treatment.NSTherapyEvent
import app.aaps.core.nssdk.localmodel.treatment.NSTreatment
import app.aaps.core.utils.JsonHelper
import app.aaps.plugins.sync.nsclient.extensions.fromJson
import app.aaps.plugins.sync.nsclientV3.extensions.toBolus
import app.aaps.plugins.sync.nsclientV3.extensions.toBolusCalculatorResult
import app.aaps.plugins.sync.nsclientV3.extensions.toCarbs
import app.aaps.plugins.sync.nsclientV3.extensions.toEffectiveProfileSwitch
import app.aaps.plugins.sync.nsclientV3.extensions.toExtendedBolus
import app.aaps.plugins.sync.nsclientV3.extensions.toFood
import app.aaps.plugins.sync.nsclientV3.extensions.toGV
import app.aaps.plugins.sync.nsclientV3.extensions.toOfflineEvent
import app.aaps.plugins.sync.nsclientV3.extensions.toProfileSwitch
import app.aaps.plugins.sync.nsclientV3.extensions.toTemporaryBasal
import app.aaps.plugins.sync.nsclientV3.extensions.toTemporaryTarget
import app.aaps.plugins.sync.nsclientV3.extensions.toTherapyEvent
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NsIncomingDataProcessor @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val nsClientSource: NSClientSource,
    private val sp: SP,
    private val preferences: Preferences,
    private val rxBus: RxBus,
    private val dateUtil: DateUtil,
    private val activePlugin: ActivePlugin,
    private val storeDataForDb: StoreDataForDb,
    private val config: Config,
    private val instantiator: Instantiator,
    private val profileSource: ProfileSource
) {

    private fun toGv(jsonObject: JSONObject): GV? {
        val sgv = NSSgvObject(jsonObject)
        return GV(
            timestamp = sgv.mills ?: return null,
            value = sgv.mgdl?.toDouble() ?: return null,
            noise = null,
            raw = sgv.filtered?.toDouble(),
            trendArrow = TrendArrow.fromString(sgv.direction),
            ids = IDs(nightscoutId = sgv.id),
            sourceSensor = SourceSensor.fromString(sgv.device)
        )
    }

    /**
     * Preprocess list of SGVs
     *
     * @return true if there was an accepted SGV
     */
    @Suppress("SpellCheckingInspection")
    fun processSgvs(sgvs: Any): Boolean {
        // Objective0
        sp.putBoolean(app.aaps.core.utils.R.string.key_objectives_bg_is_available_in_ns, true)

        if (!nsClientSource.isEnabled() && !preferences.get(BooleanKey.NsClientAcceptCgmData)) return false

        var latestDateInReceivedData: Long = 0
        aapsLogger.debug(LTag.NSCLIENT, "Received NS Data: $sgvs")
        val glucoseValues = mutableListOf<GV>()

        if (sgvs is JSONArray) { // V1 client
            for (i in 0 until sgvs.length()) {
                val sgv = toGv(sgvs.getJSONObject(i)) ?: continue
                // allow 1 min in the future
                if (sgv.timestamp < dateUtil.now() + T.mins(1).msecs() && sgv.timestamp > latestDateInReceivedData) {
                    latestDateInReceivedData = sgv.timestamp
                    glucoseValues += sgv
                } else
                    aapsLogger.debug(LTag.NSCLIENT, "Ignoring record with wrong timestamp: $sgv")
            }
        } else if (sgvs is List<*>) { // V3 client

            for (i in 0 until sgvs.size) {
                val sgv = (sgvs[i] as NSSgvV3).toGV()
                if (sgv.timestamp < dateUtil.now() && sgv.timestamp > latestDateInReceivedData) {
                    latestDateInReceivedData = sgv.timestamp
                    glucoseValues += sgv
                } else
                    aapsLogger.debug(LTag.NSCLIENT, "Ignoring record with wrong timestamp: $sgv")
            }
        }
        if (glucoseValues.isNotEmpty()) {
            activePlugin.activeNsClient?.updateLatestBgReceivedIfNewer(latestDateInReceivedData)
            // Was that sgv more less 5 mins ago ?
            if (T.msecs(dateUtil.now() - latestDateInReceivedData).mins() < 5L) {
                rxBus.send(EventDismissNotification(Notification.NS_ALARM))
                rxBus.send(EventDismissNotification(Notification.NS_URGENT_ALARM))
            }
            storeDataForDb.addToGlucoseValues(glucoseValues)
        }
        return glucoseValues.isNotEmpty()
    }

    /**
     * Preprocess list of treatments
     *
     * @return true if there was an accepted treatment
     */
    fun processTreatments(treatments: List<NSTreatment>): Boolean {
        try {
            var latestDateInReceivedData: Long = 0
            for (treatment in treatments) {
                aapsLogger.debug(LTag.NSCLIENT, "Received NS treatment: $treatment")
                val date = treatment.date ?: continue
                if (date > latestDateInReceivedData) latestDateInReceivedData = date

                when (treatment) {
                    is NSBolus                  ->
                        if (preferences.get(BooleanKey.NsClientAcceptInsulin) || config.NSCLIENT)
                            storeDataForDb.addToBoluses(treatment.toBolus())

                    is NSCarbs                  ->
                        if (preferences.get(BooleanKey.NsClientAcceptCarbs) || config.NSCLIENT)
                            storeDataForDb.addToCarbs(treatment.toCarbs())

                    is NSTemporaryTarget        ->
                        if (preferences.get(BooleanKey.NsClientAcceptTempTarget) || config.NSCLIENT) {
                            if (treatment.duration > 0L) {
                                // not ending event
                                if (treatment.targetBottomAsMgdl() < Constants.MIN_TT_MGDL
                                    || treatment.targetBottomAsMgdl() > Constants.MAX_TT_MGDL
                                    || treatment.targetTopAsMgdl() < Constants.MIN_TT_MGDL
                                    || treatment.targetTopAsMgdl() > Constants.MAX_TT_MGDL
                                    || treatment.targetBottomAsMgdl() > treatment.targetTopAsMgdl()
                                ) {
                                    aapsLogger.debug(LTag.NSCLIENT, "Ignored TemporaryTarget $treatment")
                                    continue
                                }
                            }
                            storeDataForDb.addToTemporaryTargets(treatment.toTemporaryTarget())
                        }

                    is NSTemporaryBasal         ->
                        if (preferences.get(BooleanKey.NsClientAcceptTbrEb) || config.NSCLIENT)
                            storeDataForDb.addToTemporaryBasals(treatment.toTemporaryBasal())

                    is NSEffectiveProfileSwitch ->
                        if (preferences.get(BooleanKey.NsClientAcceptProfileSwitch) || config.NSCLIENT) {
                            treatment.toEffectiveProfileSwitch(dateUtil)?.let { effectiveProfileSwitch ->
                                storeDataForDb.addToEffectiveProfileSwitches(effectiveProfileSwitch)
                            }
                        }

                    is NSProfileSwitch          ->
                        if (preferences.get(BooleanKey.NsClientAcceptProfileSwitch) || config.NSCLIENT) {
                            treatment.toProfileSwitch(activePlugin, dateUtil)?.let { profileSwitch ->
                                storeDataForDb.addToProfileSwitches(profileSwitch)
                            }
                        }

                    is NSBolusWizard            ->
                        treatment.toBolusCalculatorResult()?.let { bolusCalculatorResult ->
                            storeDataForDb.addToBolusCalculatorResults(bolusCalculatorResult)
                        }

                    is NSTherapyEvent           ->
                        if (preferences.get(BooleanKey.NsClientAcceptTherapyEvent) || config.NSCLIENT)
                            treatment.toTherapyEvent().let { therapyEvent ->
                                storeDataForDb.addToTherapyEvents(therapyEvent)
                            }

                    is NSOfflineEvent           ->
                        if (preferences.get(BooleanKey.NsClientAcceptOfflineEvent) && config.isEngineeringMode() || config.NSCLIENT)
                            treatment.toOfflineEvent().let { offlineEvent ->
                                storeDataForDb.addToOfflineEvents(offlineEvent)
                            }

                    is NSExtendedBolus          ->
                        if (preferences.get(BooleanKey.NsClientAcceptTbrEb) || config.NSCLIENT)
                            treatment.toExtendedBolus().let { extendedBolus ->
                                storeDataForDb.addToExtendedBoluses(extendedBolus)
                            }
                }
            }
            if (latestDateInReceivedData > 0)
                activePlugin.activeNsClient?.updateLatestTreatmentReceivedIfNewer(latestDateInReceivedData)
            return latestDateInReceivedData > 0
        } catch (error: Exception) {
            aapsLogger.error("Error: ", error)
            rxBus.send(EventNSClientNewLog("◄ ERROR", error.localizedMessage))
        }
        return false
    }

    fun processFood(data: Any) {
        aapsLogger.debug(LTag.NSCLIENT, "Received Food Data: $data")

        try {
            val foods = mutableListOf<FD>()
            if (data is JSONArray) {
                for (index in 0 until data.length()) {
                    val jsonFood: JSONObject = data.getJSONObject(index)

                    if (JsonHelper.safeGetString(jsonFood, "type") != "food") continue

                    when (JsonHelper.safeGetString(jsonFood, "action")) {
                        "remove" -> {
                            val delFood = FD(
                                name = "",
                                portion = 0.0,
                                carbs = 0,
                                isValid = false
                            ).also { it.ids.nightscoutId = JsonHelper.safeGetString(jsonFood, "_id") }
                            foods += delFood
                        }

                        else     -> {
                            val food = FD.fromJson(jsonFood)
                            if (food != null) foods += food
                            else aapsLogger.error(LTag.NSCLIENT, "Error parsing food", jsonFood.toString())
                        }
                    }
                }
            } else if (data is List<*>) {
                for (i in 0 until data.size)
                    foods += (data[i] as NSFood).toFood()
            }
            storeDataForDb.addToFoods(foods)
        } catch (error: Exception) {
            aapsLogger.error("Error: ", error)
            rxBus.send(EventNSClientNewLog("◄ ERROR", error.localizedMessage))
        }
    }

    fun processProfile(profileJson: JSONObject) {
        if (preferences.get(BooleanKey.NsClientAcceptProfileStore) || config.NSCLIENT) {
            val store = instantiator.provideProfileStore(profileJson)
            val createdAt = store.getStartDate()
            val lastLocalChange = sp.getLong(app.aaps.core.utils.R.string.key_local_profile_last_change, 0)
            aapsLogger.debug(LTag.PROFILE, "Received profileStore: createdAt: $createdAt Local last modification: $lastLocalChange")
            if (createdAt > lastLocalChange || createdAt % 1000 == 0L) { // whole second means edited in NS
                profileSource.loadFromStore(store)
                activePlugin.activeNsClient?.dataSyncSelector?.profileReceived(store.getStartDate())
                aapsLogger.debug(LTag.PROFILE, "Received profileStore: $profileJson")
            }
        }
    }
}