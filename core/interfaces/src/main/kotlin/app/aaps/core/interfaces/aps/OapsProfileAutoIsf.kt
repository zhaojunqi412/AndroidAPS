package app.aaps.core.interfaces.aps

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(InternalSerializationApi::class)
@Serializable
data class OapsProfileAutoIsf(
    var dia: Double, // AMA only
    var min_5m_carbimpact: Double, // AMA only
    var max_iob: Double,
    var max_daily_basal: Double,
    var max_basal: Double,
    var min_bg: Double,
    var max_bg: Double,
    var target_bg: Double,
    var carb_ratio: Double,
    var sens: Double,
    var autosens_adjust_targets: Boolean, // AMA only
    var max_daily_safety_multiplier: Double,
    var current_basal_safety_multiplier: Double,
    var high_temptarget_raises_sensitivity: Boolean,
    var low_temptarget_lowers_sensitivity: Boolean,
    var sensitivity_raises_target: Boolean,
    var resistance_lowers_target: Boolean,
    var adv_target_adjustments: Boolean,
    var exercise_mode: Boolean,
    var half_basal_exercise_target: Int,
    var maxCOB: Int,
    var skip_neutral_temps: Boolean,
    var remainingCarbsCap: Int,
    var enableUAM: Boolean,
    var A52_risk_enable: Boolean,
    var SMBInterval: Int,
    var enableSMB_with_COB: Boolean,
    var enableSMB_with_temptarget: Boolean,
    var allowSMB_with_high_temptarget: Boolean,
    var enableSMB_always: Boolean,
    var enableSMB_after_carbs: Boolean,
    var maxSMBBasalMinutes: Int,
    var maxUAMSMBBasalMinutes: Int,
    var bolus_increment: Double,
    var carbsReqThreshold: Int,
    var current_basal: Double,
    var temptargetSet: Boolean,
    var autosens_max: Double,
    var out_units: String,
    var lgsThreshold: Int?,
    //AutoISF only
    var variable_sens: Double,
    var autoISF_version: String,
    var enable_autoISF: Boolean,
    var autoISF_max: Double,
    var autoISF_min: Double,
    var bgAccel_ISF_weight: Double,
    var bgBrake_ISF_weight: Double,
    var pp_ISF_weight: Double,
    var lower_ISFrange_weight: Double,
    var higher_ISFrange_weight: Double,
    var dura_ISF_weight: Double,
    var smb_delivery_ratio: Double,
    var smb_delivery_ratio_min: Double,
    var smb_delivery_ratio_max: Double,
    var smb_delivery_ratio_bg_range: Double,
    var smb_max_range_extension: Double,
    var enableSMB_EvenOn_OddOff_always: Boolean,
    var iob_threshold_percent: Int,
    var profile_percentage: Int
)