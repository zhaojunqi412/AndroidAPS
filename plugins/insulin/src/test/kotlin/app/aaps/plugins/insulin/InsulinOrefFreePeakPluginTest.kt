package app.aaps.plugins.insulin

import android.content.SharedPreferences
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.IntKey
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.`when`

/**
 * Created by adrian on 2019-12-25.
 */

class InsulinOrefFreePeakPluginTest : TestBaseWithProfile() {

    private lateinit var sut: InsulinOrefFreePeakPlugin

    @Mock lateinit var uiInteraction: UiInteraction
    @Mock lateinit var sharedPrefs: SharedPreferences

    init {
        addInjector {
            if (it is AdaptiveIntPreference) {
                it.profileUtil = profileUtil
                it.preferences = preferences
                it.sharedPrefs = sharedPrefs
                it.config = config
            }
        }
    }

    @BeforeEach
    fun setup() {
        sut = InsulinOrefFreePeakPlugin(preferences, rh, profileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction)
    }

    @Test
    fun `simple peak test`() {
        `when`(preferences.get(IntKey.InsulinOrefPeak)).thenReturn(90)
        assertThat(sut.peak).isEqualTo(90)
    }

    @Test
    fun getIdTest() {
        assertThat(sut.id).isEqualTo(Insulin.InsulinType.OREF_FREE_PEAK)
    }

    @Test
    fun commentStandardTextTest() {
        `when`(preferences.get(IntKey.InsulinOrefPeak)).thenReturn(90)
        `when`(rh.gs(eq(R.string.insulin_peak_time))).thenReturn("Peak Time [min]")
        assertThat(sut.commentStandardText()).isEqualTo("Peak Time [min]: 90")
    }

    @Test
    fun getFriendlyNameTest() {
        `when`(rh.gs(eq(R.string.free_peak_oref))).thenReturn("Free-Peak Oref")
        assertThat(sut.friendlyName).isEqualTo("Free-Peak Oref")
    }

    @Test
    fun preferenceScreenTest() {
        val screen = preferenceManager.createPreferenceScreen(context)
        sut.addPreferenceScreen(preferenceManager, screen, context, null)
        assertThat(screen.preferenceCount).isGreaterThan(0)
    }
}
