package stravahooks.strava

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class StravaActivity(
    @field:JsonProperty("id")
    val id: Long? = null,
    @field:JsonProperty("name")
    val name: String? = null,
    @field:JsonProperty("type")
    val type: String? = null,
    @field:JsonProperty("start_date")
    val startDate: String? = null,
    @field:JsonProperty("distance")
    val distance: Double? = null,
    @field:JsonProperty("moving_time")
    val movingTime: Int? = null,
    @field:JsonProperty("elapsed_time")
    val elapsedTime: Int? = null,
    @field:JsonProperty("description")
    val description: String? = null,
    @field:JsonProperty("commute")
    val commute: Boolean? = null,
    @field:JsonProperty("trainer")
    val trainer: Boolean? = null,
    @field:JsonProperty("mute")
    val mute: Boolean? = null,
    @field:JsonProperty("visibility")
    val visibility: String? = null,
    @field:JsonProperty("gear_id")
    val gearId: String? = null,
    @field:JsonProperty("gear")
    val gear: StravaGear? = null,
    @field:JsonProperty("total_elevation_gain")
    val totalElevationGain: Double? = null,
    @field:JsonProperty("average_speed")
    val averageSpeed: Double? = null,
    @field:JsonProperty("max_speed")
    val maxSpeed: Double? = null,
    @field:JsonProperty("average_heartrate")
    val averageHeartrate: Double? = null,
    @field:JsonProperty("max_heartrate")
    val maxHeartrate: Double? = null,
    @field:JsonProperty("average_cadence")
    val averageCadence: Double? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StravaGear(
    @field:JsonProperty("id")
    val id: String? = null,
    @field:JsonProperty("name")
    val name: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StravaSummaryActivity(
    @field:JsonProperty("id")
    val id: Long? = null,
    @field:JsonProperty("name")
    val name: String? = null,
    @field:JsonProperty("distance")
    val distance: Double? = null,
    @field:JsonProperty("moving_time")
    val movingTime: Int? = null,
    @field:JsonProperty("start_date")
    val startDate: String? = null
)
