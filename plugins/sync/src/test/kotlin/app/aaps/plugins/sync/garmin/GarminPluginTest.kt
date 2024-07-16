package app.aaps.plugins.sync.garmin

import android.content.Context
import app.aaps.core.interfaces.db.GlucoseUnit
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.events.EventNewBG
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.database.entities.GlucoseValue
import app.aaps.shared.tests.TestBase
import dagger.android.AndroidInjector
import dagger.android.HasAndroidInjector
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.atMost
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.locks.Condition
import kotlin.ranges.LongProgression.Companion.fromClosedRange

class GarminPluginTest: TestBase() {
    private lateinit var gp: GarminPlugin

    @Mock private lateinit var rh: ResourceHelper
    @Mock private lateinit var sp: SP
    @Mock private lateinit var context: Context
    @Mock private lateinit var loopHub: LoopHub
    private val clock = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneId.of("UTC"))

    private var injector: HasAndroidInjector = HasAndroidInjector {
        AndroidInjector {
        }
    }

    @BeforeEach
    fun setup() {
        gp = GarminPlugin(injector, aapsLogger, rh, context, loopHub, rxBus, sp)
        gp.clock = clock
        `when`(loopHub.currentProfileName).thenReturn("Default")
        `when`(sp.getBoolean(anyString(), anyBoolean())).thenAnswer { i -> i.arguments[1] }
        `when`(sp.getString(anyString(), anyString())).thenAnswer { i -> i.arguments[1] }
        `when`(sp.getInt(anyString(), anyInt())).thenAnswer { i -> i.arguments[1] }
        `when`(sp.getInt(eq("communication_http_port"), anyInt()))
            .thenReturn(28890)
    }

    @AfterEach
    fun verifyNoFurtherInteractions() {
        verify(loopHub, atMost(2)).currentProfileName
        verify(loopHub, atMost(3)).insulinOnboard
        verify(loopHub, atMost(3)).insulinBasalOnboard
        verify(loopHub, atMost(3)).temporaryBasal
        verify(loopHub, atMost(3)).carbsOnboard
        verifyNoMoreInteractions(loopHub)
    }

    private val getGlucoseValuesFrom = clock.instant()
        .minus(2, ChronoUnit.HOURS)
        .minus(9, ChronoUnit.MINUTES)

    private fun createUri(params: Map<String, Any>): URI {
        return URI("http://foo?" + params.entries.joinToString(separator = "&") { (k, v) ->
            "$k=$v"})
    }

    private fun createHeartRate(@Suppress("SameParameterValue") heartRate: Int) = mapOf<String, Any>(
        "hr" to heartRate,
        "hrStart" to 1001L,
        "hrEnd" to 2001L,
        "device" to "Test_Device")

    private fun createGlucoseValue(timestamp: Instant, value: Double = 93.0) = GlucoseValue(
        id = 10 * timestamp.toEpochMilli(),
        timestamp = timestamp.toEpochMilli(), raw = 90.0, value = value,
        trendArrow = GlucoseValue.TrendArrow.FLAT, noise = 4.5,
        sourceSensor = GlucoseValue.SourceSensor.RANDOM
    )

    @Test
    fun testReceiveHeartRateMap() {
        val hr = createHeartRate(80)
        gp.receiveHeartRate(hr, false)
        verify(loopHub).storeHeartRate(
            Instant.ofEpochSecond(hr["hrStart"] as Long),
            Instant.ofEpochSecond(hr["hrEnd"] as Long),
            80,
            hr["device"] as String)
    }

    @Test
    fun testReceiveHeartRateUri() {
        val hr = createHeartRate(99)
        val uri = createUri(hr)
        gp.receiveHeartRate(uri)
        verify(loopHub).storeHeartRate(
            Instant.ofEpochSecond(hr["hrStart"] as Long),
            Instant.ofEpochSecond(hr["hrEnd"] as Long),
            99,
            hr["device"] as String)
    }

    @Test
    fun testReceiveHeartRate_UriTestIsTrue() {
        val params = createHeartRate(99).toMutableMap()
        params["test"] = true
        val uri = createUri(params)
        gp.receiveHeartRate(uri)
    }

    @Test
    fun testGetGlucoseValues_NoLast() {
        val from = getGlucoseValuesFrom
        val prev = createGlucoseValue(clock.instant().minusSeconds(310))
        `when`(loopHub.getGlucoseValues(from, true)).thenReturn(listOf(prev))
        assertArrayEquals(arrayOf(prev), gp.getGlucoseValues().toTypedArray())
        verify(loopHub).getGlucoseValues(from, true)
    }

    @Test
    fun testGetGlucoseValues_NoNewLast() {
        val from = getGlucoseValuesFrom
        val lastTimesteamp = clock.instant()
        val prev = createGlucoseValue(clock.instant())
        gp.newValue = mock(Condition::class.java)
        `when`(loopHub.getGlucoseValues(from, true)).thenReturn(listOf(prev))
        gp.onNewBloodGlucose(EventNewBG(lastTimesteamp.toEpochMilli()))
        assertArrayEquals(arrayOf(prev), gp.getGlucoseValues().toTypedArray())

        verify(gp.newValue).signalAll()
        verify(loopHub).getGlucoseValues(from, true)
    }

    @Test
    fun setupHttpServer_enabled() {
        `when`(sp.getBoolean("communication_http", false)).thenReturn(true)
        `when`(sp.getInt("communication_http_port", 28891)).thenReturn(28892)
        gp.setupHttpServer(Duration.ofSeconds(10))
        val reqUri = URI("http://127.0.0.1:28892/get")
        val resp = reqUri.toURL().openConnection() as HttpURLConnection
        assertEquals(200, resp.responseCode)

        // Change port
        `when`(sp.getInt("communication_http_port", 28891)).thenReturn(28893)
        gp.setupHttpServer(Duration.ofSeconds(10))
        val reqUri2 = URI("http://127.0.0.1:28893/get")
        val resp2 = reqUri2.toURL().openConnection() as HttpURLConnection
        assertEquals(200, resp2.responseCode)

        `when`(sp.getBoolean("communication_http", false)).thenReturn(false)
        gp.setupHttpServer(Duration.ofSeconds(10))
        assertThrows(ConnectException::class.java) {
            (reqUri2.toURL().openConnection() as HttpURLConnection).responseCode
        }
        gp.onStop()

        verify(loopHub, times(2)).getGlucoseValues(anyObject(), eq(true))
        verify(loopHub, times(2)).insulinOnboard
        verify(loopHub, times(2)).temporaryBasal
        verify(loopHub, times(2)).isConnected
        verify(loopHub, times(2)).glucoseUnit
    }

    @Test
    fun setupHttpServer_disabled() {
        gp.setupHttpServer(Duration.ofSeconds(10))
        val reqUri = URI("http://127.0.0.1:28890/get")
        assertThrows(ConnectException::class.java) {
            (reqUri.toURL().openConnection() as HttpURLConnection).responseCode
        }
    }

    @Test
    fun requestHandler_NoKey() {
        val uri = createUri(emptyMap())
        val handler = gp.requestHandler { u: URI -> assertEquals(uri, u); "OK" }
        assertEquals(
            HttpURLConnection.HTTP_OK to "OK",
            handler(mock(SocketAddress::class.java), uri, null))
    }

    @Test
    fun requestHandler_KeyProvided() {
        val uri = createUri(mapOf("key" to "foo"))
        val handler = gp.requestHandler { u: URI -> assertEquals(uri, u); "OK" }
        assertEquals(
            HttpURLConnection.HTTP_OK to "OK",
            handler(mock(SocketAddress::class.java), uri, null))
    }

    @Test
    fun requestHandler_KeyRequiredAndProvided() {
        `when`(sp.getString("garmin_aaps_key", "")).thenReturn("foo")
        val uri = createUri(mapOf("key" to "foo"))
        val handler = gp.requestHandler { u: URI -> assertEquals(uri, u); "OK" }
        assertEquals(
            HttpURLConnection.HTTP_OK to "OK",
            handler(mock(SocketAddress::class.java), uri, null))

    }

    @Test
    fun requestHandler_KeyRequired() {
        gp.garminMessenger = mock(GarminMessenger::class.java)

        `when`(sp.getString("garmin_aaps_key", "")).thenReturn("foo")
        val uri = createUri(emptyMap())
        val handler = gp.requestHandler { u: URI -> assertEquals(uri, u); "OK" }
        assertEquals(
            HttpURLConnection.HTTP_UNAUTHORIZED to "{}",
            handler(mock(SocketAddress::class.java), uri, null))

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(gp.garminMessenger)!!.sendMessage(captor.capture() ?: "")
        @Suppress("UNCHECKED_CAST")
        val r = captor.value as Map<String, Any>
        assertEquals("foo", r["key"])
        assertEquals("glucose", r["command"])
        assertEquals("D", r["profile"])
        assertEquals("", r["encodedGlucose"])
        assertEquals(0.0, r["remainingInsulin"])
        assertEquals("mmoll", r["glucoseUnit"])
        assertEquals(0.0, r["temporaryBasalRate"])
        assertEquals(false, r["connected"])
        assertEquals(clock.instant().epochSecond, r["timestamp"])
        verify(loopHub).getGlucoseValues(getGlucoseValuesFrom, true)
        verify(loopHub).insulinOnboard
        verify(loopHub).temporaryBasal
        verify(loopHub).isConnected
        verify(loopHub).glucoseUnit
    }

    @Test
    fun onConnectDevice() {
        gp.garminMessenger = mock(GarminMessenger::class.java)
        `when`(sp.getString("garmin_aaps_key", "")).thenReturn("foo")
        val device = GarminDevice(mock(),1, "Edge")
        gp.onConnectDevice(device)

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(gp.garminMessenger)!!.sendMessage(eq(device), captor.capture() ?: "")
        @Suppress("UNCHECKED_CAST")
        val r = captor.value as Map<String, Any>
        assertEquals("foo", r["key"])
        assertEquals("glucose", r["command"])
        assertEquals("D", r["profile"])
        assertEquals("", r["encodedGlucose"])
        assertEquals(0.0, r["remainingInsulin"])
        assertEquals("mmoll", r["glucoseUnit"])
        assertEquals(0.0, r["temporaryBasalRate"])
        assertEquals(false, r["connected"])
        assertEquals(clock.instant().epochSecond, r["timestamp"])
        verify(loopHub).getGlucoseValues(getGlucoseValuesFrom, true)
        verify(loopHub).insulinOnboard
        verify(loopHub).temporaryBasal
        verify(loopHub).isConnected
        verify(loopHub).glucoseUnit
    }

    @Test
    fun testOnGetBloodGlucose() {
        `when`(loopHub.isConnected).thenReturn(true)
        `when`(loopHub.insulinOnboard).thenReturn(3.14)
        `when`(loopHub.temporaryBasal).thenReturn(0.8)
        val from = getGlucoseValuesFrom
        `when`(loopHub.getGlucoseValues(from, true)).thenReturn(
            listOf(createGlucoseValue(Instant.ofEpochSecond(1_000))))
        val hr = createHeartRate(99)
        val uri = createUri(hr)
        val result = gp.onGetBloodGlucose(uri)
        assertEquals(
            "{\"encodedGlucose\":\"0A+6AQ==\"," +
                "\"remainingInsulin\":3.14," +
                "\"glucoseUnit\":\"mmoll\",\"temporaryBasalRate\":0.8," +
                "\"profile\":\"D\",\"connected\":true}",
            result.toString())
        verify(loopHub).getGlucoseValues(from, true)
        verify(loopHub).insulinOnboard
        verify(loopHub).temporaryBasal
        verify(loopHub).isConnected
        verify(loopHub).glucoseUnit
        verify(loopHub).storeHeartRate(
            Instant.ofEpochSecond(hr["hrStart"] as Long),
            Instant.ofEpochSecond(hr["hrEnd"] as Long),
            99,
            hr["device"] as String)
    }

    @Test
    fun testOnGetBloodGlucose_Wait() {
        `when`(loopHub.isConnected).thenReturn(true)
        `when`(loopHub.insulinOnboard).thenReturn(3.14)
        `when`(loopHub.temporaryBasal).thenReturn(0.8)
        `when`(loopHub.glucoseUnit).thenReturn(GlucoseUnit.MMOL)
        val from = getGlucoseValuesFrom
        `when`(loopHub.getGlucoseValues(from, true)).thenReturn(
            listOf(createGlucoseValue(clock.instant().minusSeconds(330))))
        val params = createHeartRate(99).toMutableMap()
        params["wait"] = 10
        val uri = createUri(params)
        gp.newValue = mock(Condition::class.java)
        val result = gp.onGetBloodGlucose(uri)
        assertEquals(
            "{\"encodedGlucose\":\"/wS6AQ==\"," +
                "\"remainingInsulin\":3.14," +
                "\"glucoseUnit\":\"mmoll\",\"temporaryBasalRate\":0.8," +
                "\"profile\":\"D\",\"connected\":true}",
            result.toString())
        verify(gp.newValue).awaitNanos(anyLong())
        verify(loopHub, times(2)).getGlucoseValues(from, true)
        verify(loopHub).insulinOnboard
        verify(loopHub).temporaryBasal
        verify(loopHub).isConnected
        verify(loopHub).glucoseUnit
        verify(loopHub).storeHeartRate(
            Instant.ofEpochSecond(params["hrStart"] as Long),
            Instant.ofEpochSecond(params["hrEnd"] as Long),
            99,
            params["device"] as String)
    }

    @Test
    fun testOnPostCarbs() {
        val uri = createUri(mapOf("carbs" to "12"))
        assertEquals("", gp.onPostCarbs(uri))
        verify(loopHub).postCarbs(12)
    }

    @Test
    fun testOnConnectPump_Disconnect() {
        val uri = createUri(mapOf("disconnectMinutes" to "20"))
        `when`(loopHub.isConnected).thenReturn(false)
        assertEquals("{\"connected\":false}", gp.onConnectPump(uri))
        verify(loopHub).disconnectPump(20)
        verify(loopHub).isConnected
    }

    @Test
    fun testOnConnectPump_Connect() {
        val uri = createUri(mapOf("disconnectMinutes" to "0"))
        `when`(loopHub.isConnected).thenReturn(true)
        assertEquals("{\"connected\":true}", gp.onConnectPump(uri))
        verify(loopHub).connectPump()
        verify(loopHub).isConnected
    }

    @Test
    fun onSgv_NoGlucose() {
        whenever(loopHub.glucoseUnit).thenReturn(GlucoseUnit.MMOL)
        whenever(loopHub.getGlucoseValues(any(), eq(false))).thenReturn(emptyList())
        assertEquals("[]", gp.onSgv(createUri(mapOf())))
        verify(loopHub).getGlucoseValues(clock.instant().minusSeconds(25L * 300L), false)
    }

    @Test
fun onSgv_NoDelta() {
        whenever(loopHub.glucoseUnit).thenReturn(GlucoseUnit.MMOL)
        whenever(loopHub.insulinOnboard).thenReturn(2.7)
        whenever(loopHub.insulinBasalOnboard).thenReturn(2.5)
        whenever(loopHub.temporaryBasal).thenReturn(0.8)
        whenever(loopHub.carbsOnboard).thenReturn(10.7)
        whenever(loopHub.getGlucoseValues(any(), eq(false))).thenReturn(
            listOf(createGlucoseValue(
                clock.instant().minusSeconds(100L), 99.3)))
        assertEquals(
            """[{"_id":"-900000","device":"RANDOM","deviceString":"1969-12-31T23:58:30Z","sysTime":"1969-12-31T23:58:30Z","unfiltered":90.0,"date":-90000,"sgv":99,"direction":"Flat","noise":4.5,"units_hint":"mmol","iob":5.2,"tbr":80,"cob":10.7}]""",
            gp.onSgv(createUri(mapOf())))
        verify(loopHub).getGlucoseValues(clock.instant().minusSeconds(25L * 300L), false)
        verify(loopHub).glucoseUnit
    }

    @Test
    fun onSgv() {
        whenever(loopHub.glucoseUnit).thenReturn(GlucoseUnit.MMOL)
        whenever(loopHub.insulinOnboard).thenReturn(2.7)
        whenever(loopHub.insulinBasalOnboard).thenReturn(2.5)
        whenever(loopHub.temporaryBasal).thenReturn(0.8)
        whenever(loopHub.carbsOnboard).thenReturn(10.7)
        whenever(loopHub.getGlucoseValues(any(), eq(false))).thenAnswer { i ->
            val from = i.getArgument<Instant>(0)
            fromClosedRange(from.toEpochMilli(), clock.instant().toEpochMilli(), 300_000L)
                .map(Instant::ofEpochMilli)
                .mapIndexed { idx, ts -> createGlucoseValue(ts, 100.0+(10 * idx)) }.reversed()}
        assertEquals(
            """[{"_id":"100000","device":"RANDOM","deviceString":"1970-01-01T00:00:10Z","sysTime":"1970-01-01T00:00:10Z","unfiltered":90.0,"date":10000,"sgv":120,"delta":10,"direction":"Flat","noise":4.5,"units_hint":"mmol","iob":5.2,"tbr":80,"cob":10.7}]""",
            gp.onSgv(createUri(mapOf("count" to "1"))))
        verify(loopHub).getGlucoseValues(
            clock.instant().minusSeconds(600L), false)


        assertEquals(
            """[{"_id":"100000","device":"RANDOM","deviceString":"1970-01-01T00:00:10Z","sysTime":"1970-01-01T00:00:10Z","unfiltered":90.0,"date":10000,"sgv":130,"delta":10,"direction":"Flat","noise":4.5,"units_hint":"mmol","iob":5.2,"tbr":80,"cob":10.7},""" +
                """{"_id":"-2900000","device":"RANDOM","deviceString":"1969-12-31T23:55:10Z","sysTime":"1969-12-31T23:55:10Z","unfiltered":90.0,"date":-290000,"sgv":120,"delta":10,"direction":"Flat","noise":4.5}]""",
            gp.onSgv(createUri(mapOf("count" to "2"))))
        verify(loopHub).getGlucoseValues(
            clock.instant().minusSeconds(900L), false)

        assertEquals(
            """[{"date":10000,"sgv":130,"delta":10,"direction":"Flat","noise":4.5,"units_hint":"mmol","iob":5.2,"tbr":80,"cob":10.7},""" +
                """{"date":-290000,"sgv":120,"delta":10,"direction":"Flat","noise":4.5}]""",
            gp.onSgv(createUri(mapOf("count" to "2", "brief_mode" to "true"))))
        verify(loopHub, times(2)).getGlucoseValues(
            clock.instant().minusSeconds(900L), false)

        verify(loopHub, atLeastOnce()).glucoseUnit
    }
}
