package app.aaps.pump.danars

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.text.format.DateFormat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.model.BS
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.objects.Instantiator
import app.aaps.core.interfaces.plugin.OwnDatabasePlugin
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.Dana
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.DetailedBolusInfoStorage
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpPluginBase
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.TemporaryBasalStorage
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAppExit
import app.aaps.core.interfaces.rx.events.EventConfigBuilderChange
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.core.interfaces.rx.events.EventOverviewBolusProgress
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.validators.DefaultEditTextValidator
import app.aaps.core.validators.EditTextValidator
import app.aaps.core.validators.preferences.AdaptiveIntentPreference
import app.aaps.core.validators.preferences.AdaptiveListIntPreference
import app.aaps.core.validators.preferences.AdaptiveStringPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.pump.dana.DanaFragment
import app.aaps.pump.dana.DanaPump
import app.aaps.pump.dana.comm.RecordTypes
import app.aaps.pump.dana.database.DanaHistoryDatabase
import app.aaps.pump.dana.keys.DanaBooleanKey
import app.aaps.pump.dana.keys.DanaIntKey
import app.aaps.pump.dana.keys.DanaIntentKey
import app.aaps.pump.dana.keys.DanaLongKey
import app.aaps.pump.dana.keys.DanaString2Key
import app.aaps.pump.dana.keys.DanaStringKey
import app.aaps.pump.danars.activities.BLEScanActivity
import app.aaps.pump.danars.events.EventDanaRSDeviceChange
import app.aaps.pump.danars.services.DanaRSService
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max

@Singleton
class DanaRSPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    private val aapsSchedulers: AapsSchedulers,
    private val rxBus: RxBus,
    private val context: Context,
    rh: ResourceHelper,
    private val constraintChecker: ConstraintsChecker,
    private val profileFunction: ProfileFunction,
    commandQueue: CommandQueue,
    private val danaPump: DanaPump,
    private val pumpSync: PumpSync,
    private val preferences: Preferences,
    private val detailedBolusInfoStorage: DetailedBolusInfoStorage,
    private val temporaryBasalStorage: TemporaryBasalStorage,
    private val fabricPrivacy: FabricPrivacy,
    private val dateUtil: DateUtil,
    private val uiInteraction: UiInteraction,
    private val danaHistoryDatabase: DanaHistoryDatabase,
    private val decimalFormatter: DecimalFormatter,
    private val instantiator: Instantiator
) : PumpPluginBase(
    PluginDescription()
        .mainType(PluginType.PUMP)
        .fragmentClass(DanaFragment::class.java.name)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_danai_128)
        .pluginIcon2(app.aaps.core.ui.R.drawable.ic_danars_128)
        .pluginName(app.aaps.pump.dana.R.string.danarspump)
        .shortName(app.aaps.pump.dana.R.string.danarspump_shortname)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .description(app.aaps.pump.dana.R.string.description_pump_dana_rs),
    aapsLogger, rh, commandQueue
), Pump, Dana, PluginConstraints, OwnDatabasePlugin {

    private val disposable = CompositeDisposable()
    private var danaRSService: DanaRSService? = null
    private var mDeviceAddress = ""
    var mDeviceName = ""

    // Make plugin preferences available to AAPS
    init {
        preferences.registerPreferences(DanaStringKey::class.java)
        preferences.registerPreferences(DanaIntKey::class.java)
        preferences.registerPreferences(DanaBooleanKey::class.java)
        preferences.registerPreferences(DanaIntentKey::class.java)
        preferences.registerPreferences(DanaString2Key::class.java)
        preferences.registerPreferences(DanaLongKey::class.java)
    }

    override val pumpDescription
        get() = PumpDescription().fillFor(danaPump.pumpType())

    override fun updatePreferenceSummary(pref: Preference) {
        super.updatePreferenceSummary(pref)

        if (pref.key == DanaStringKey.DanaRsName.key) {
            val value = preferences.getIfExists(DanaStringKey.DanaRsName)
            pref.summary = value ?: rh.gs(app.aaps.core.ui.R.string.not_set_short)
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(context, DanaRSService::class.java)
        context.bindService(intent, mConnection, Context.BIND_AUTO_CREATE)
        disposable += rxBus
            .toObservable(EventAppExit::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ context.unbindService(mConnection) }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventConfigBuilderChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe { danaPump.reset() }
        disposable += rxBus
            .toObservable(EventDanaRSDeviceChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           pumpSync.connectNewPump()
                           changePump()
                       }, fabricPrivacy::logException)
        changePump() // load device name
    }

    override fun onStop() {
        context.unbindService(mConnection)
        disposable.clear()
        super.onStop()
    }

    private val mConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName) {
            aapsLogger.debug(LTag.PUMP, "Service is disconnected")
            danaRSService = null
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            aapsLogger.debug(LTag.PUMP, "Service is connected")
            val mLocalBinder = service as DanaRSService.LocalBinder
            danaRSService = mLocalBinder.serviceInstance
        }
    }

    fun changePump() {
        mDeviceAddress = preferences.get(DanaStringKey.DanaMacAddress)
        mDeviceName = preferences.get(DanaStringKey.DanaRsName)
        danaPump.serialNumber = preferences.get(DanaStringKey.DanaRsName)
        danaPump.reset()
        commandQueue.readStatus(rh.gs(app.aaps.core.ui.R.string.device_changed), null)
    }

    override fun connect(reason: String) {
        aapsLogger.debug(LTag.PUMP, "RS connect from: $reason")
        if (danaRSService != null && mDeviceAddress != "" && mDeviceName != "") {
            val success = danaRSService?.connect(reason, mDeviceAddress) == true
            if (!success) ToastUtils.errorToast(context, app.aaps.core.ui.R.string.ble_not_supported_or_not_paired)
        }
    }

    override fun isConnected(): Boolean = danaRSService?.isConnected == true
    override fun isConnecting(): Boolean = danaRSService?.isConnecting == true
    override fun isHandshakeInProgress(): Boolean = false

    override fun disconnect(reason: String) {
        aapsLogger.debug(LTag.PUMP, "RS disconnect from: $reason")
        danaRSService?.disconnect(reason)
    }

    override fun stopConnecting() {
        danaRSService?.stopConnecting()
    }

    override fun getPumpStatus(reason: String) {
        danaRSService?.readPumpStatus()
        pumpDescription.basalStep = danaPump.basalStep
        pumpDescription.bolusStep = danaPump.bolusStep
    }

    // DanaR interface
    override fun loadHistory(type: Byte): PumpEnactResult {
        return danaRSService?.loadHistory(type) ?: instantiator.providePumpEnactResult().success(false)
    }

    override fun loadEvents(): PumpEnactResult {
        return danaRSService?.loadEvents() ?: instantiator.providePumpEnactResult().success(false)
    }

    override fun setUserOptions(): PumpEnactResult {
        return danaRSService?.setUserSettings() ?: instantiator.providePumpEnactResult().success(false)
    }

    // Constraints interface
    override fun applyBasalConstraints(absoluteRate: Constraint<Double>, profile: Profile): Constraint<Double> {
        absoluteRate.setIfSmaller(danaPump.maxBasal, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, danaPump.maxBasal, rh.gs(app.aaps.core.ui.R.string.pumplimit)), this)
        return absoluteRate
    }

    override fun applyBasalPercentConstraints(percentRate: Constraint<Int>, profile: Profile): Constraint<Int> {
        percentRate.setIfGreater(0, rh.gs(app.aaps.core.ui.R.string.limitingpercentrate, 0, rh.gs(app.aaps.core.ui.R.string.itmustbepositivevalue)), this)
        percentRate.setIfSmaller(
            pumpDescription.maxTempPercent,
            rh.gs(app.aaps.core.ui.R.string.limitingpercentrate, pumpDescription.maxTempPercent, rh.gs(app.aaps.core.ui.R.string.pumplimit)),
            this
        )
        return percentRate
    }

    override fun applyBolusConstraints(insulin: Constraint<Double>): Constraint<Double> {
        insulin.setIfSmaller(danaPump.maxBolus, rh.gs(app.aaps.core.ui.R.string.limitingbolus, danaPump.maxBolus, rh.gs(app.aaps.core.ui.R.string.pumplimit)), this)
        return insulin
    }

    override fun applyExtendedBolusConstraints(insulin: Constraint<Double>): Constraint<Double> {
        return applyBolusConstraints(insulin)
    }

    // Pump interface
    override fun isInitialized(): Boolean =
        danaPump.lastConnection > 0 && danaPump.maxBasal > 0 && danaPump.isRSPasswordOK

    override fun isSuspended(): Boolean =
        danaPump.pumpSuspended || danaPump.errorState != DanaPump.ErrorState.NONE

    override fun isBusy(): Boolean =
        danaRSService?.isConnected == true || danaRSService?.isConnecting == true

    override fun setNewBasalProfile(profile: Profile): PumpEnactResult {
        val result = instantiator.providePumpEnactResult()
        if (!isInitialized()) {
            aapsLogger.error("setNewBasalProfile not initialized")
            uiInteraction.addNotification(Notification.PROFILE_NOT_SET_NOT_INITIALIZED, rh.gs(app.aaps.core.ui.R.string.pump_not_initialized_profile_not_set), Notification.URGENT)
            result.comment = rh.gs(app.aaps.core.ui.R.string.pump_not_initialized_profile_not_set)
            return result
        } else {
            rxBus.send(EventDismissNotification(Notification.PROFILE_NOT_SET_NOT_INITIALIZED))
        }
        return if (danaRSService?.updateBasalsInPump(profile) != true) {
            uiInteraction.addNotification(Notification.FAILED_UPDATE_PROFILE, rh.gs(app.aaps.core.ui.R.string.failed_update_basal_profile), Notification.URGENT)
            result.comment = rh.gs(app.aaps.core.ui.R.string.failed_update_basal_profile)
            result
        } else {
            rxBus.send(EventDismissNotification(Notification.PROFILE_NOT_SET_NOT_INITIALIZED))
            rxBus.send(EventDismissNotification(Notification.FAILED_UPDATE_PROFILE))
            uiInteraction.addNotificationValidFor(Notification.PROFILE_SET_OK, rh.gs(app.aaps.core.ui.R.string.profile_set_ok), Notification.INFO, 60)
            result.success = true
            result.enacted = true
            result.comment = "OK"
            result
        }
    }

    override fun isThisProfileSet(profile: Profile): Boolean {
        if (!isInitialized()) return true
        if (danaPump.pumpProfiles == null) return true
        val basalValues = if (danaPump.basal48Enable) 48 else 24
        val basalIncrement = if (danaPump.basal48Enable) 30 * 60 else 60 * 60
        for (h in 0 until basalValues) {
            val pumpValue = danaPump.pumpProfiles!![danaPump.activeProfile][h]
            val profileValue = profile.getBasalTimeFromMidnight(h * basalIncrement)
            if (abs(pumpValue - profileValue) > pumpDescription.basalStep) {
                aapsLogger.debug(LTag.PUMP, "Diff found. Hour: $h Pump: $pumpValue Profile: $profileValue")
                return false
            }
        }
        return true
    }

    override fun lastDataTime(): Long = danaPump.lastConnection
    override val baseBasalRate: Double
        get() = danaPump.currentBasal
    override val reservoirLevel: Double
        get() = danaPump.reservoirRemainingUnits
    override val batteryLevel: Int
        get() = danaPump.batteryRemaining

    @Synchronized
    override fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        // Insulin value must be greater than 0
        require(detailedBolusInfo.carbs == 0.0) { detailedBolusInfo.toString() }
        require(detailedBolusInfo.insulin > 0) { detailedBolusInfo.toString() }

        detailedBolusInfo.insulin = constraintChecker.applyBolusConstraints(ConstraintObject(detailedBolusInfo.insulin, aapsLogger)).value()
        val preferencesSpeed = preferences.get(DanaIntKey.DanaBolusSpeed)
        var speed = 12
        when (preferencesSpeed) {
            0 -> speed = 12
            1 -> speed = 30
            2 -> speed = 60
        }
        // RS stores end time for bolus, we need to adjust time
        // default delivery speed is 12 sec/U
        detailedBolusInfo.timestamp = dateUtil.now() + (speed * detailedBolusInfo.insulin * 1000).toLong()
        // clean carbs to prevent counting them as twice because they will picked up as another record
        // I don't think it's necessary to copy DetailedBolusInfo right now for carbs records
        val carbs = detailedBolusInfo.carbs
        detailedBolusInfo.carbs = 0.0
        var carbTimeStamp = detailedBolusInfo.carbsTimestamp ?: detailedBolusInfo.timestamp
        if (carbTimeStamp == detailedBolusInfo.timestamp) carbTimeStamp -= T.mins(1).msecs() // better set 1 min back to prevents clash with insulin
        detailedBolusInfoStorage.add(detailedBolusInfo) // will be picked up on reading history
        val t = EventOverviewBolusProgress.Treatment(0.0, 0, detailedBolusInfo.bolusType == BS.Type.SMB, detailedBolusInfo.id)
        var connectionOK = false
        if (detailedBolusInfo.insulin > 0 || carbs > 0) connectionOK = danaRSService?.bolus(detailedBolusInfo.insulin, carbs.toInt(), carbTimeStamp, t) == true
        val result = instantiator.providePumpEnactResult()
        result.success = connectionOK && abs(detailedBolusInfo.insulin - t.insulin) < pumpDescription.bolusStep
        result.bolusDelivered = t.insulin
        if (!result.success) {
            var error = "" + danaPump.bolusStartErrorCode
            when (danaPump.bolusStartErrorCode) {
                0x10 -> error = rh.gs(app.aaps.pump.dana.R.string.maxbolusviolation)
                0x20 -> error = rh.gs(app.aaps.pump.dana.R.string.commanderror)
                0x40 -> error = rh.gs(app.aaps.pump.dana.R.string.speederror)
                0x80 -> error = rh.gs(app.aaps.pump.dana.R.string.insulinlimitviolation)
            }
            result.comment = rh.gs(app.aaps.pump.dana.R.string.boluserrorcode, detailedBolusInfo.insulin, t.insulin, error)
        } else result.comment = rh.gs(app.aaps.core.ui.R.string.ok)
        aapsLogger.debug(LTag.PUMP, "deliverTreatment: OK. Asked: " + detailedBolusInfo.insulin + " Delivered: " + result.bolusDelivered)
        return result
    }

    override fun stopBolusDelivering() {
        danaRSService?.bolusStop()
    }

    // This is called from APS
    @Synchronized
    override fun setTempBasalAbsolute(absoluteRate: Double, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult {
        val absoluteAfterConstrain = constraintChecker.applyBasalConstraints(ConstraintObject(absoluteRate, aapsLogger), profile).value()
        var doTempOff = baseBasalRate - absoluteAfterConstrain == 0.0
        val doLowTemp = absoluteAfterConstrain < baseBasalRate
        val doHighTemp = absoluteAfterConstrain > baseBasalRate

        var percentRate = 0
        // Any basal less than 0.10u/h will be dumped once per hour, not every 4 minutes. So if it's less than .10u/h, set a zero temp.
        if (absoluteAfterConstrain >= 0.10) {
            percentRate = java.lang.Double.valueOf(absoluteAfterConstrain / baseBasalRate * 100).toInt()
        } else {
            aapsLogger.debug(LTag.PUMP, "setTempBasalAbsolute: Requested basal < 0.10u/h. Setting 0u/h (doLowTemp || doHighTemp)")
        }
        percentRate = if (percentRate < 100) Round.ceilTo(percentRate.toDouble(), 10.0).toInt() else Round.floorTo(percentRate.toDouble(), 10.0).toInt()
        if (percentRate > 500) // Special high temp 500/15min
            percentRate = 500

        if (percentRate == 100) doTempOff = true

        if (doTempOff) {
            // If temp in progress
            if (danaPump.isTempBasalInProgress) {
                aapsLogger.debug(LTag.PUMP, "setTempBasalAbsolute: Stopping temp basal (doTempOff)")
                return cancelTempBasal(false)
            }
            aapsLogger.debug(LTag.PUMP, "setTempBasalAbsolute: doTempOff OK")
            return instantiator.providePumpEnactResult()
                .success(true)
                .enacted(false)
                .percent(100)
                .isPercent(true)
                .isTempCancel(true)
        }
        if (doLowTemp || doHighTemp) {
            // Check if some temp is already in progress
            if (danaPump.isTempBasalInProgress) {
                aapsLogger.debug(LTag.PUMP, "setTempBasalAbsolute: currently running")
                // Correct basal already set ?
                if (danaPump.tempBasalPercent == percentRate && danaPump.tempBasalRemainingMin > 4) {
                    if (!enforceNew) {
                        aapsLogger.debug(LTag.PUMP, "setTempBasalAbsolute: Correct temp basal already set (doLowTemp || doHighTemp)")
                        return instantiator.providePumpEnactResult()
                            .success(true)
                            .percent(percentRate)
                            .enacted(false)
                            .duration(danaPump.tempBasalRemainingMin)
                            .isPercent(true)
                            .isTempCancel(false)
                    }
                }
            }
            temporaryBasalStorage.add(PumpSync.PumpState.TemporaryBasal(dateUtil.now(), T.mins(durationInMinutes.toLong()).msecs(), percentRate.toDouble(), false, tbrType, 0L, 0L))
            // Convert duration from minutes to hours
            aapsLogger.debug(LTag.PUMP, "setTempBasalAbsolute: Setting temp basal $percentRate% for $durationInMinutes minutes (doLowTemp || doHighTemp)")
            val result = if (percentRate == 0 && durationInMinutes > 30) {
                setTempBasalPercent(percentRate, durationInMinutes, profile, enforceNew, tbrType)
            } else {
                // use special APS temp basal call ... 100+/15min .... 100-/30min
                setHighTempBasalPercent(percentRate)
            }
            if (!result.success) {
                aapsLogger.error("setTempBasalAbsolute: Failed to set high temp basal")
                return result
            }
            aapsLogger.debug(LTag.PUMP, "setTempBasalAbsolute: high temp basal set ok")
            return result
        }
        // We should never end here
        aapsLogger.error("setTempBasalAbsolute: Internal error")
        return instantiator.providePumpEnactResult()
            .success(false)
            .comment("Internal error")
    }

    @Synchronized
    override fun setTempBasalPercent(percent: Int, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult {
        val result = instantiator.providePumpEnactResult()
        var percentAfterConstraint = constraintChecker.applyBasalPercentConstraints(ConstraintObject(percent, aapsLogger), profile).value()
        if (percentAfterConstraint < 0) {
            result.isTempCancel = false
            result.enacted = false
            result.success = false
            result.comment = rh.gs(app.aaps.core.ui.R.string.invalid_input)
            aapsLogger.error("setTempBasalPercent: Invalid input")
            return result
        }
        if (percentAfterConstraint > pumpDescription.maxTempPercent) percentAfterConstraint = pumpDescription.maxTempPercent
        if (danaPump.isTempBasalInProgress && danaPump.tempBasalPercent == percentAfterConstraint && danaPump.tempBasalRemainingMin > 4 && !enforceNew) {
            result.enacted = false
            result.success = true
            result.isTempCancel = false
            result.comment = rh.gs(app.aaps.core.ui.R.string.ok)
            result.duration = danaPump.tempBasalRemainingMin
            result.percent = danaPump.tempBasalPercent
            result.isPercent = true
            aapsLogger.debug(LTag.PUMP, "setTempBasalPercent: Correct value already set")
            return result
        }
        temporaryBasalStorage.add(PumpSync.PumpState.TemporaryBasal(dateUtil.now(), T.mins(durationInMinutes.toLong()).msecs(), percent.toDouble(), false, tbrType, 0L, 0L))
        val connectionOK: Boolean = if (durationInMinutes == 15 || durationInMinutes == 30) {
            danaRSService?.tempBasalShortDuration(percentAfterConstraint, durationInMinutes) == true
        } else {
            val durationInHours = max(durationInMinutes / 60, 1)
            danaRSService?.tempBasal(percentAfterConstraint, durationInHours) == true
        }
        if (connectionOK && danaPump.isTempBasalInProgress && danaPump.tempBasalPercent == percentAfterConstraint) {
            result.enacted = true
            result.success = true
            result.comment = rh.gs(app.aaps.core.ui.R.string.ok)
            result.isTempCancel = false
            result.duration = danaPump.tempBasalRemainingMin
            result.percent = danaPump.tempBasalPercent
            result.isPercent = true
            aapsLogger.debug(LTag.PUMP, "setTempBasalPercent: OK")
            return result
        }
        result.enacted = false
        result.success = false
        result.comment = rh.gs(app.aaps.core.ui.R.string.temp_basal_delivery_error)
        aapsLogger.error("setTempBasalPercent: Failed to set temp basal. connectionOK: $connectionOK isTempBasalInProgress: ${danaPump.isTempBasalInProgress} tempBasalPercent: ${danaPump.tempBasalPercent}")
        return result
    }

    @Synchronized private fun setHighTempBasalPercent(percent: Int): PumpEnactResult {
        val result = instantiator.providePumpEnactResult()
        val connectionOK = danaRSService?.highTempBasal(percent) == true
        if (connectionOK && danaPump.isTempBasalInProgress && danaPump.tempBasalPercent == percent) {
            result.enacted = true
            result.success = true
            result.comment = rh.gs(app.aaps.core.ui.R.string.ok)
            result.isTempCancel = false
            result.duration = danaPump.tempBasalRemainingMin
            result.percent = danaPump.tempBasalPercent
            result.isPercent = true
            aapsLogger.debug(LTag.PUMP, "setHighTempBasalPercent: OK")
            return result
        }
        result.enacted = false
        result.success = false
        result.comment = rh.gs(app.aaps.pump.dana.R.string.danar_valuenotsetproperly)
        aapsLogger.error("setHighTempBasalPercent: Failed to set temp basal. connectionOK: $connectionOK isTempBasalInProgress: ${danaPump.isTempBasalInProgress} tempBasalPercent: ${danaPump.tempBasalPercent}")
        return result
    }

    @Synchronized
    override fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult {
        var insulinAfterConstraint = constraintChecker.applyExtendedBolusConstraints(ConstraintObject(insulin, aapsLogger)).value()
        // needs to be rounded
        val durationInHalfHours = max(durationInMinutes / 30, 1)
        insulinAfterConstraint = Round.roundTo(insulinAfterConstraint, pumpDescription.extendedBolusStep)
        val result = instantiator.providePumpEnactResult()
        if (danaPump.isExtendedInProgress && abs(danaPump.extendedBolusAmount - insulinAfterConstraint) < pumpDescription.extendedBolusStep) {
            result.enacted = false
            result.success = true
            result.comment = rh.gs(app.aaps.core.ui.R.string.ok)
            result.duration = danaPump.extendedBolusRemainingMinutes
            result.absolute = danaPump.extendedBolusAbsoluteRate
            result.isPercent = false
            result.isTempCancel = false
            aapsLogger.debug(LTag.PUMP, "setExtendedBolus: Correct extended bolus already set. Current: " + danaPump.extendedBolusAmount + " Asked: " + insulinAfterConstraint)
            return result
        }
        val connectionOK = danaRSService?.extendedBolus(insulinAfterConstraint, durationInHalfHours) == true
        if (connectionOK && danaPump.isExtendedInProgress && abs(danaPump.extendedBolusAmount - insulinAfterConstraint) < pumpDescription.extendedBolusStep) {
            result.enacted = true
            result.success = true
            result.comment = rh.gs(app.aaps.core.ui.R.string.ok)
            result.isTempCancel = false
            result.duration = danaPump.extendedBolusRemainingMinutes
            result.absolute = danaPump.extendedBolusAbsoluteRate
            result.bolusDelivered = danaPump.extendedBolusAmount
            result.isPercent = false
            aapsLogger.debug(LTag.PUMP, "setExtendedBolus: OK")
            return result
        }
        result.enacted = false
        result.success = false
        result.comment = rh.gs(app.aaps.pump.dana.R.string.danar_valuenotsetproperly)
        aapsLogger.error("setExtendedBolus: Failed to extended bolus")
        return result
    }

    @Synchronized
    override fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult {
        if (danaPump.isTempBasalInProgress) {
            aapsLogger.debug(LTag.PUMP, "cancelRealTempBasal: Failed")
            danaRSService?.tempBasalStop()
            return instantiator.providePumpEnactResult()
                .success(!danaPump.isTempBasalInProgress)
                .enacted(true)
                .isTempCancel(true)
                .comment(app.aaps.core.ui.R.string.canceling_tbr_failed)
        } else {
            aapsLogger.debug(LTag.PUMP, "cancelRealTempBasal: OK")
            return instantiator.providePumpEnactResult()
                .success(true)
                .enacted(false)
                .isTempCancel(true)
                .comment(app.aaps.core.ui.R.string.ok)
        }
    }

    @Synchronized override fun cancelExtendedBolus(): PumpEnactResult {
        if (danaPump.isExtendedInProgress) {
            danaRSService?.extendedBolusStop()
            aapsLogger.debug(LTag.PUMP, "cancelExtendedBolus: Failed")
            return instantiator.providePumpEnactResult()
                .success(!danaPump.isExtendedInProgress)
                .enacted(true)
                .comment(app.aaps.core.ui.R.string.canceling_eb_failed)
        } else {
            aapsLogger.debug(LTag.PUMP, "cancelExtendedBolus: OK")
            return instantiator.providePumpEnactResult()
                .success(true)
                .enacted(false)
                .isTempCancel(true)
                .comment(app.aaps.core.ui.R.string.ok)
        }
    }

    override fun getJSONStatus(profile: Profile, profileName: String, version: String): JSONObject {
        val now = System.currentTimeMillis()
        if (danaPump.lastConnection + 60 * 60 * 1000L < System.currentTimeMillis()) {
            return JSONObject()
        }
        val pumpJson = JSONObject()
        val battery = JSONObject()
        val status = JSONObject()
        val extended = JSONObject()
        try {
            battery.put("percent", danaPump.batteryRemaining)
            status.put("status", if (danaPump.pumpSuspended) "suspended" else "normal")
            status.put("timestamp", dateUtil.toISOString(danaPump.lastConnection))
            extended.put("Version", version)
            if (danaPump.lastBolusTime != 0L) {
                extended.put("LastBolus", dateUtil.dateAndTimeString(danaPump.lastBolusTime))
                extended.put("LastBolusAmount", danaPump.lastBolusAmount)
            }
            val tb = pumpSync.expectedPumpState().temporaryBasal
            if (tb != null) {
                extended.put("TempBasalAbsoluteRate", tb.convertedToAbsolute(now, profile))
                extended.put("TempBasalStart", dateUtil.dateAndTimeString(tb.timestamp))
                extended.put("TempBasalRemaining", tb.plannedRemainingMinutes)
            }
            val eb = pumpSync.expectedPumpState().extendedBolus
            if (eb != null) {
                extended.put("ExtendedBolusAbsoluteRate", eb.rate)
                extended.put("ExtendedBolusStart", dateUtil.dateAndTimeString(eb.timestamp))
                extended.put("ExtendedBolusRemaining", eb.plannedRemainingMinutes)
            }
            extended.put("BaseBasalRate", baseBasalRate)
            try {
                extended.put("ActiveProfile", profileFunction.getProfileName())
            } catch (e: Exception) {
                aapsLogger.error("Unhandled exception", e)
            }
            pumpJson.put("battery", battery)
            pumpJson.put("status", status)
            pumpJson.put("extended", extended)
            pumpJson.put("reservoir", danaPump.reservoirRemainingUnits.toInt())
            pumpJson.put("clock", dateUtil.toISOString(now))
        } catch (e: JSONException) {
            aapsLogger.error("Unhandled exception", e)
        }
        return pumpJson
    }

    override fun manufacturer(): ManufacturerType = ManufacturerType.Sooil
    override fun model(): PumpType = danaPump.pumpType()
    override fun serialNumber(): String = danaPump.serialNumber

    @Suppress("SpellCheckingInspection")
    override fun shortStatus(veryShort: Boolean): String {
        var ret = ""
        if (danaPump.lastConnection != 0L) {
            val agoMillis = System.currentTimeMillis() - danaPump.lastConnection
            val agoMin = (agoMillis / 60.0 / 1000.0).toInt()
            ret += "LastConn: $agoMin minAgo\n"
        }
        if (danaPump.lastBolusTime != 0L)
            ret += "LastBolus: ${decimalFormatter.to2Decimal(danaPump.lastBolusAmount)}U @${DateFormat.format("HH:mm", danaPump.lastBolusTime)}"

        if (danaPump.isTempBasalInProgress)
            ret += "Temp: ${danaPump.temporaryBasalToString()}"

        if (danaPump.isExtendedInProgress)
            ret += "Extended: ${danaPump.extendedBolusToString()}\n"

        if (!veryShort) {
            ret += "TDD: ${decimalFormatter.to0Decimal(danaPump.dailyTotalUnits)} / ${danaPump.maxDailyTotalUnits} U"
        }
        ret += "Reserv: ${decimalFormatter.to0Decimal(danaPump.reservoirRemainingUnits)} U"
        ret += "Batt: ${danaPump.batteryRemaining}"
        return ret
    }

    override val isFakingTempsByExtendedBoluses: Boolean = false
    override fun loadTDDs(): PumpEnactResult = loadHistory(RecordTypes.RECORD_TYPE_DAILY)
    override fun canHandleDST(): Boolean = false
    override fun clearPairing() {
        aapsLogger.debug(LTag.PUMPCOMM, "Pairing keys cleared")
        preferences.remove(DanaString2Key.DanaRsParingKey, mDeviceName)
        preferences.remove(DanaString2Key.DanaRsV3RandomParingKey, mDeviceName)
        preferences.remove(DanaString2Key.DanaRsV3ParingKey, mDeviceName)
        preferences.remove(DanaString2Key.DanaRsV3RandomSyncKey, mDeviceName)
        preferences.remove(DanaString2Key.DanaRsBle5PairingKey, mDeviceName)
    }

    override fun clearAllTables() = danaHistoryDatabase.clearAllTables()

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null) return

        val speedEntries = arrayOf<CharSequence>("12 s/U", "30 s/U", "60 s/U")
        val speedValues = arrayOf<CharSequence>("0", "1", "2")

        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "danars_settings"
            title = rh.gs(app.aaps.pump.dana.R.string.danarspump)
            initialExpandedChildrenCount = 0
            addPreference(
                AdaptiveIntentPreference(
                    ctx = context, intentKey = DanaIntentKey.DanaRsBtSelector, title = app.aaps.pump.dana.R.string.selectedpump,
                    intent = Intent(context, BLEScanActivity::class.java)
                )
            )
            addPreference(
                AdaptiveStringPreference(
                    ctx = context, stringKey = DanaStringKey.DanaRsPassword, title = app.aaps.pump.dana.R.string.danars_password_title,
                    validatorParams = DefaultEditTextValidator.Parameters(
                        testType = EditTextValidator.TEST_REGEXP,
                        customRegexp = rh.gs(app.aaps.core.validators.R.string.fourhexanumber),
                        testErrorString = rh.gs(app.aaps.core.validators.R.string.error_mustbe4hexadidits)
                    )
                )
            )
            addPreference(
                AdaptiveListIntPreference(
                    ctx = context,
                    intKey = DanaIntKey.DanaBolusSpeed,
                    title = app.aaps.pump.dana.R.string.bolusspeed,
                    dialogTitle = app.aaps.pump.dana.R.string.bolusspeed,
                    entries = speedEntries,
                    entryValues = speedValues
                )
            )
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = DanaBooleanKey.DanaRsLogInsulinChange, title = app.aaps.pump.dana.R.string.rs_loginsulinchange_title, summary = app.aaps.pump.dana.R.string.rs_loginsulinchange_summary))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = DanaBooleanKey.DanaRsLogCannulaChange, title = app.aaps.pump.dana.R.string.rs_logcanulachange_title, summary = app.aaps.pump.dana.R.string.rs_logcanulachange_summary))
        }
    }
}