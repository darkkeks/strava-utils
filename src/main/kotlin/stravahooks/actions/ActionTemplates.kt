package stravahooks.actions

data class ActionTemplate(
    val id: String,
    val name: String,
    val description: String,
    val code: String
)

object ActionTemplates {
    val SHORT_RIDE_MUTE = ActionTemplate(
        id = "short_ride_mute",
        name = "Mute Short Rides",
        description = "Mutes rides under 5km and marks them as commute",
        code = """
function action(activity) {
  if (activity.type === "Ride" && activity.distance_m < 5000) {
    activity.mute = true;
    activity.commute = true;
  }
}
""".trimIndent()
    )

    val CONDITION_DESCRIPTION = ActionTemplate(
        id = "condition_description",
        name = "Add Stats to Description",
        description = "Appends distance and pace/speed to the description",
        code = """
function action(activity) {
  var dist = (activity.distance_m / 1000).toFixed(1);
  var line = dist + " km";
  if (activity.average_speed_kph) {
    line += " @ " + activity.average_speed_kph.toFixed(1) + " km/h";
  }
  var desc = activity.description || "";
  if (desc.indexOf(line) === -1) {
    activity.description = desc ? desc + "\n" + line : line;
  }
}
""".trimIndent()
    )

    val GEAR_SELECTION = ActionTemplate(
        id = "gear_selection",
        name = "Auto-select Gear",
        description = "Sets gear based on activity type (set your gear IDs in the code)",
        code = """
function action(activity) {
  // Replace these with your actual Strava gear IDs
  // Find them at: https://www.strava.com/settings/gear
  var ROAD_BIKE = "b12345678";
  var RUNNING_SHOES = "g12345678";
  if (activity.type === "Ride") {
    activity.gear_id = ROAD_BIKE;
  } else if (activity.type === "Run") {
    activity.gear_id = RUNNING_SHOES;
  }
}
""".trimIndent()
    )

    val ALL = listOf(SHORT_RIDE_MUTE, CONDITION_DESCRIPTION, GEAR_SELECTION)

    fun findById(id: String): ActionTemplate? = ALL.firstOrNull { it.id == id }
}
