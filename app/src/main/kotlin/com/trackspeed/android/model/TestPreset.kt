package com.trackspeed.android.model

import com.trackspeed.android.data.model.SportCategory

data class TestPreset(
    val id: String,
    val name: String,
    val shortName: String,
    val distance: Double,
    val iconKey: String,
    val category: TestPresetCategory,
    val availableStartTypes: List<StartType>,
    val defaultStartType: StartType,
    val minPhones: Int,
    val maxPhones: Int,
    val gatePositions: List<GatePosition>,
    val tips: List<String>,
    val selectableDistances: List<Double>? = null
) {
    val hasSelectableDistance: Boolean
        get() = !selectableDistances.isNullOrEmpty()

    val isFlying: Boolean
        get() = defaultStartType == StartType.FLYING && availableStartTypes == listOf(StartType.FLYING)

    val isSinglePhone: Boolean
        get() = minPhones == 1 && maxPhones == 1

    val distanceDisplay: String
        get() = when (id) {
            "40yd" -> "40 yards (36.58m)"
            else -> if (distance == distance.toLong().toDouble()) "${distance.toLong()}m" else "${"%.1f".format(distance)}m"
        }

    val shortDistance: String
        get() = when (id) {
            "40yd" -> "40yd"
            else -> if (distance > 0) "${distance.toInt()}m" else "Laps"
        }

    fun gatePositionsForDistance(selectedDistance: Double): List<GatePosition> {
        if (hasSelectableDistance) {
            return listOf(
                GatePosition(distance = 0.0, label = "Start (Phone 1)"),
                GatePosition(distance = selectedDistance, label = "Finish (${selectedDistance.toInt()}m)")
            )
        }
        return gatePositions
    }

    companion object {
        val fortyYardDash = TestPreset(
            id = "40yd",
            name = "40 Yard Dash",
            shortName = "40yd",
            distance = 36.576,
            iconKey = "sportscourt",
            category = TestPresetCategory.COMBINE,
            availableStartTypes = listOf(StartType.TOUCH_RELEASE, StartType.VOICE_COMMAND, StartType.COUNTDOWN),
            defaultStartType = StartType.TOUCH_RELEASE,
            minPhones = 2,
            maxPhones = 2,
            gatePositions = listOf(
                GatePosition(distance = 0.0, label = "Start"),
                GatePosition(distance = 36.576, label = "Finish (40yd)")
            ),
            tips = listOf(
                "Place Phone 1 at the start line",
                "Place Phone 2 at 40 yards (36.6m)"
            )
        )

        val sixtyMeterSprint = TestPreset(
            id = "60m",
            name = "60m Sprint",
            shortName = "60m",
            distance = 60.0,
            iconKey = "figure.run",
            category = TestPresetCategory.ACCELERATION,
            availableStartTypes = listOf(StartType.TOUCH_RELEASE, StartType.VOICE_COMMAND, StartType.COUNTDOWN),
            defaultStartType = StartType.VOICE_COMMAND,
            minPhones = 2,
            maxPhones = 3,
            gatePositions = listOf(
                GatePosition(distance = 0.0, label = "Start"),
                GatePosition(distance = 30.0, label = "30m Split", isOptional = true),
                GatePosition(distance = 60.0, label = "Finish (60m)")
            ),
            tips = listOf(
                "Phone 1 at start, Phone 2 (optional) at 30m, Phone 3 at 60m",
                "Add a phone at 30m for split times (optional)"
            )
        )

        val flyingSprint = TestPreset(
            id = "flying",
            name = "Flying Sprint",
            shortName = "Flying",
            distance = 30.0,
            iconKey = "bolt",
            category = TestPresetCategory.MAX_SPEED,
            availableStartTypes = listOf(StartType.FLYING),
            defaultStartType = StartType.FLYING,
            minPhones = 2,
            maxPhones = 6,
            gatePositions = listOf(
                GatePosition(distance = 0.0, label = "Start (Phone 1)"),
                GatePosition(distance = 30.0, label = "Finish (30m)")
            ),
            tips = listOf(
                "Recommended: 30m+ runup before Phone 1 to reach top speed",
                "Timer starts when you run past Phone 1",
                "This measures maximum velocity, not acceleration"
            ),
            selectableDistances = listOf(10.0, 20.0, 30.0)
        )

        val takeOffVelocity = TestPreset(
            id = "takeoff-velocity",
            name = "Take Off Velocity",
            shortName = "TOV",
            distance = 5.0,
            iconKey = "pole-vault",
            category = TestPresetCategory.MAX_SPEED,
            availableStartTypes = listOf(StartType.FLYING),
            defaultStartType = StartType.FLYING,
            minPhones = 2,
            maxPhones = 6,
            gatePositions = listOf(
                GatePosition(distance = 0.0, label = "Start (5m back)"),
                GatePosition(distance = 5.0, label = "Takeoff")
            ),
            tips = listOf(
                "Pole Vault: Place finish phone at your takeoff mark, start phone 5m behind on the runway",
                "Long Jump: Place finish phone at the board, start phone 5m behind on the runway",
                "Timer starts when you pass the first phone - measures your approach velocity"
            )
        )

        val flying10m = TestPreset(
            id = "flying-10m",
            name = "Flying 10m",
            shortName = "F10",
            distance = 10.0,
            iconKey = "bolt",
            category = TestPresetCategory.MAX_SPEED,
            availableStartTypes = listOf(StartType.FLYING),
            defaultStartType = StartType.FLYING,
            minPhones = 2,
            maxPhones = 6,
            gatePositions = listOf(
                GatePosition(distance = 0.0, label = "Start (Phone 1)"),
                GatePosition(distance = 10.0, label = "Finish (10m)")
            ),
            tips = listOf(
                "30m+ runup before Phone 1 to reach top speed",
                "Timer starts when you run past Phone 1",
                "Measures maximum velocity over 10m"
            )
        )

        val flying30m = TestPreset(
            id = "flying-30m",
            name = "Flying 30m",
            shortName = "F30",
            distance = 30.0,
            iconKey = "bolt",
            category = TestPresetCategory.MAX_SPEED,
            availableStartTypes = listOf(StartType.FLYING),
            defaultStartType = StartType.FLYING,
            minPhones = 2,
            maxPhones = 6,
            gatePositions = listOf(
                GatePosition(distance = 0.0, label = "Start (Phone 1)"),
                GatePosition(distance = 30.0, label = "Finish (30m)")
            ),
            tips = listOf(
                "30m+ runup before Phone 1 to reach top speed",
                "Timer starts when you run past Phone 1",
                "Measures maximum velocity over 30m"
            )
        )

        val thirtyMeterSprint = TestPreset(
            id = "30m",
            name = "30m Sprint",
            shortName = "30m",
            distance = 30.0,
            iconKey = "figure.run",
            category = TestPresetCategory.ACCELERATION,
            availableStartTypes = listOf(StartType.TOUCH_RELEASE, StartType.VOICE_COMMAND, StartType.COUNTDOWN),
            defaultStartType = StartType.TOUCH_RELEASE,
            minPhones = 2,
            maxPhones = 2,
            gatePositions = listOf(
                GatePosition(distance = 0.0, label = "Start"),
                GatePosition(distance = 30.0, label = "Finish (30m)")
            ),
            tips = listOf(
                "Short sprint for acceleration testing",
                "Place phones at start and 30m"
            )
        )

        val practice = TestPreset(
            id = "practice",
            name = "Solo Mode",
            shortName = "Laps",
            distance = 0.0,
            iconKey = "repeat",
            category = TestPresetCategory.ACCELERATION,
            availableStartTypes = listOf(StartType.FLYING),
            defaultStartType = StartType.FLYING,
            minPhones = 1,
            maxPhones = 1,
            gatePositions = listOf(
                GatePosition(distance = 0.0, label = "Phone")
            ),
            tips = listOf(
                "Place phone at your timing point",
                "Each crossing is recorded with a photo and lap time",
                "Great for repetitions and interval training"
            )
        )

        val proAgility = TestPreset(
            id = "5-10-5",
            name = "Pro Agility (5-10-5)",
            shortName = "5-10-5",
            distance = 18.288,
            iconKey = "swap",
            category = TestPresetCategory.AGILITY,
            availableStartTypes = listOf(StartType.IN_FRAME),
            defaultStartType = StartType.IN_FRAME,
            minPhones = 1,
            maxPhones = 1,
            gatePositions = listOf(
                GatePosition(distance = 0.0, label = "Phone")
            ),
            tips = listOf(
                "Set phone on tripod at CENTER cone with front camera pointing toward you",
                "Stand in frame at start position - screen shows 'Ready'",
                "Timer starts when you leave frame, stops when you return",
                "Sprint 5yd right, touch, 10yd left, touch, 5yd back"
            )
        )

        val hundredMeterSprint = TestPreset(
            id = "100m",
            name = "100m Sprint",
            shortName = "100m",
            distance = 100.0,
            iconKey = "figure.run",
            category = TestPresetCategory.ACCELERATION,
            availableStartTypes = listOf(StartType.TOUCH_RELEASE, StartType.VOICE_COMMAND, StartType.COUNTDOWN),
            defaultStartType = StartType.VOICE_COMMAND,
            minPhones = 2,
            maxPhones = 4,
            gatePositions = listOf(
                GatePosition(distance = 0.0, label = "Start"),
                GatePosition(distance = 30.0, label = "30m Split", isOptional = true),
                GatePosition(distance = 60.0, label = "60m Split", isOptional = true),
                GatePosition(distance = 100.0, label = "Finish (100m)")
            ),
            tips = listOf(
                "Phone 1 at start, Phone 2 at 100m finish",
                "Optional split gates at 30m and 60m",
                "Standard outdoor track sprint distance"
            )
        )

        val tenMeterAcceleration = TestPreset(
            id = "10m",
            name = "10m Acceleration",
            shortName = "10m",
            distance = 10.0,
            iconKey = "flame",
            category = TestPresetCategory.ACCELERATION,
            availableStartTypes = listOf(StartType.TOUCH_RELEASE, StartType.VOICE_COMMAND, StartType.COUNTDOWN),
            defaultStartType = StartType.TOUCH_RELEASE,
            minPhones = 2,
            maxPhones = 2,
            gatePositions = listOf(
                GatePosition(distance = 0.0, label = "Start"),
                GatePosition(distance = 10.0, label = "Finish (10m)")
            ),
            tips = listOf(
                "Measures explosive first-step acceleration",
                "Great for team sports athletes",
                "Place phones 10m apart"
            )
        )

        val twentyMeterAcceleration = TestPreset(
            id = "20m",
            name = "20m Sprint",
            shortName = "20m",
            distance = 20.0,
            iconKey = "figure.run",
            category = TestPresetCategory.ACCELERATION,
            availableStartTypes = listOf(StartType.TOUCH_RELEASE, StartType.VOICE_COMMAND, StartType.COUNTDOWN),
            defaultStartType = StartType.TOUCH_RELEASE,
            minPhones = 2,
            maxPhones = 3,
            gatePositions = listOf(
                GatePosition(distance = 0.0, label = "Start"),
                GatePosition(distance = 10.0, label = "10m Split", isOptional = true),
                GatePosition(distance = 20.0, label = "Finish (20m)")
            ),
            tips = listOf(
                "Common soccer/football sprint test distance",
                "Optional 10m split for acceleration analysis",
                "Place phones at start and 20m"
            )
        )

        val lDrill = TestPreset(
            id = "l-drill",
            name = "L-Drill (3-Cone)",
            shortName = "3-Cone",
            distance = 27.432,
            iconKey = "triangle",
            category = TestPresetCategory.AGILITY,
            availableStartTypes = listOf(StartType.IN_FRAME),
            defaultStartType = StartType.IN_FRAME,
            minPhones = 1,
            maxPhones = 1,
            gatePositions = listOf(
                GatePosition(distance = 0.0, label = "Phone")
            ),
            tips = listOf(
                "Set phone on tripod at Cone 1 with front camera pointing toward you",
                "Set up 3 cones in L-shape, 5 yards apart",
                "Stand in frame - timer starts when you leave",
                "Complete drill and return to Cone 1 to stop timer"
            )
        )

        val all: List<TestPreset> = listOf(
            tenMeterAcceleration,
            twentyMeterAcceleration,
            thirtyMeterSprint,
            sixtyMeterSprint,
            hundredMeterSprint,
            practice,
            flying10m,
            flying30m,
            flyingSprint,
            takeOffVelocity,
            proAgility,
            lDrill,
            fortyYardDash
        )

        val featured: List<TestPreset> = listOf(
            fortyYardDash,
            sixtyMeterSprint,
            flyingSprint,
            practice
        )

        fun defaultPresets(category: SportCategory): List<TestPreset> = when (category) {
            SportCategory.SPRINTS -> listOf(flying10m, flying30m, thirtyMeterSprint, practice)
            SportCategory.HURDLES -> listOf(flying10m, thirtyMeterSprint, sixtyMeterSprint, practice)
            SportCategory.MIDDLE_DISTANCE, SportCategory.LONG_DISTANCE -> listOf(flying30m, sixtyMeterSprint, hundredMeterSprint, practice)
            SportCategory.FIELD_EVENTS -> listOf(flying10m, takeOffVelocity, flying30m, practice)
            SportCategory.TEAM_SPORTS -> listOf(flying10m, fortyYardDash, proAgility, practice)
            SportCategory.STRENGTH -> listOf(tenMeterAcceleration, flying10m, proAgility, practice)
        }

        fun preset(id: String): TestPreset? = all.firstOrNull { it.id == id }
    }
}

data class GatePosition(
    val distance: Double,
    val label: String,
    val isOptional: Boolean = false
) {
    val id: String = "${distance}m"
}

enum class TestPresetCategory(val displayName: String) {
    ACCELERATION("Acceleration"),
    MAX_SPEED("Max Speed"),
    AGILITY("Agility"),
    COMBINE("Combine")
}
