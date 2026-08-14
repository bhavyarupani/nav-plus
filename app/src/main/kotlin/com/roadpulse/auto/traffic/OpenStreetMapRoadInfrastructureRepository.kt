package com.roadpulse.auto.traffic

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

class OpenStreetMapRoadInfrastructureRepository private constructor(
    private val appContext: Context?,
    private val cacheDirectory: File,
) {
    constructor(context: Context) : this(
        context.applicationContext,
        File(context.applicationContext.filesDir, CACHE_DIRECTORY).apply { mkdirs() },
    )

    internal constructor() : this(null, File("."))

    val storedRegionCount: Int
        get() = cacheDirectory.listFiles()?.count { it.isFile && it.extension == "json" } ?: 0

    fun infrastructureInBounds(
        southLatitude: Double,
        westLongitude: Double,
        northLatitude: Double,
        eastLongitude: Double,
    ): RoadInfrastructureResult =
        synchronized(QUERY_LOCK) {
            val expanded =
                ExpandedBounds(
                    south = floor(southLatitude / CACHE_GRID_DEGREES) * CACHE_GRID_DEGREES,
                    west = floor(westLongitude / CACHE_GRID_DEGREES) * CACHE_GRID_DEGREES,
                    north = ceil(northLatitude / CACHE_GRID_DEGREES) * CACHE_GRID_DEGREES,
                    east = ceil(eastLongitude / CACHE_GRID_DEGREES) * CACHE_GRID_DEGREES,
                )
            val query = overpassQuery(expanded)
            val cacheFile = File(cacheDirectory, "${sha256(query)}.json")
            var usedSavedData = false
            val fresh =
                cacheFile.isFile &&
                    System.currentTimeMillis() - cacheFile.lastModified() <= CACHE_MAX_AGE_MILLIS
            val json =
                when {
                    fresh -> {
                        usedSavedData = true
                        cacheFile.readText(Charsets.UTF_8)
                    }
                    !hasValidatedInternet() && cacheFile.isFile -> {
                        usedSavedData = true
                        cacheFile.readText(Charsets.UTF_8)
                    }
                    !hasValidatedInternet() -> error("No saved road-sign data for this area")
                    else ->
                        runCatching { download(query) }.fold(
                            onSuccess = { downloaded ->
                                saveAtomically(cacheFile, downloaded)
                                downloaded
                            },
                            onFailure = { error ->
                                if (!cacheFile.isFile) throw error
                                usedSavedData = true
                                cacheFile.readText(Charsets.UTF_8)
                            },
                        )
                }
            val timestamp = osmDataTimestamp(json).takeIf { it > 0L } ?: cacheFile.lastModified()
            parse(json, timestamp).copy(usedSavedData = usedSavedData).let { result ->
                result.copy(
                    points =
                        result.points.filter { point ->
                            point.coordinate.latitude in southLatitude..northLatitude &&
                                longitudeIsInside(
                                    point.coordinate.longitude,
                                    westLongitude,
                                    eastLongitude,
                                )
                        },
                    facilities =
                        result.facilities.filter { facility ->
                            facility.coordinate.latitude in southLatitude..northLatitude &&
                                longitudeIsInside(
                                    facility.coordinate.longitude,
                                    westLongitude,
                                    eastLongitude,
                                )
                        },
                    speedLimitSections =
                        result.speedLimitSections.filter { section ->
                            section.geometry.any { coordinate ->
                                coordinate.latitude in southLatitude..northLatitude &&
                                    longitudeIsInside(
                                        coordinate.longitude,
                                        westLongitude,
                                        eastLongitude,
                                    )
                            }
                        },
                    laneTopologySections =
                        result.laneTopologySections.filter { section ->
                            section.geometry.any { coordinate ->
                                coordinate.latitude in southLatitude..northLatitude &&
                                    longitudeIsInside(
                                        coordinate.longitude,
                                        westLongitude,
                                        eastLongitude,
                                    )
                            }
                        },
                )
            }
        }

    internal fun parse(
        json: String,
        timestampMillis: Long = 0L,
    ): RoadInfrastructureResult {
        val elements = JSONObject(json).getJSONArray("elements")
        val points = mutableListOf<RoadInfrastructurePoint>()
        val facilities = mutableListOf<RoadFacility>()
        val speedLimitSections = mutableListOf<SpeedLimitRoadSection>()
        val laneTopologySections = mutableListOf<LaneTopologyWaySection>()
        val autobahnRefs = linkedSetOf<String>()
        for (index in 0 until elements.length()) {
            val element = elements.getJSONObject(index)
            val tags = element.optJSONObject("tags") ?: JSONObject()
            if (tags.optString("highway") == "motorway") {
                normaliseAutobahnRefs(tags.optString("ref")).forEach(autobahnRefs::add)
            }
            speedLimitSection(element, tags)?.let(speedLimitSections::add)
            laneTopologySection(element, tags)?.let(laneTopologySections::add)
            val latitude =
                when {
                    element.has("lat") -> element.optDouble("lat")
                    element.optJSONObject("center")?.has("lat") == true ->
                        element.optJSONObject("center")!!.optDouble("lat")
                    else -> continue
                }
            val longitude =
                when {
                    element.has("lon") -> element.optDouble("lon")
                    element.optJSONObject("center")?.has("lon") == true ->
                        element.optJSONObject("center")!!.optDouble("lon")
                    else -> continue
                }
            restroom(tags, element, latitude, longitude)?.let(facilities::add)
            classify(tags)?.let { classification ->
                points +=
                    RoadInfrastructurePoint(
                        id = "${element.optString("type", "object")}/${element.optLong("id")}",
                        coordinate = RoadCoordinate(latitude, longitude),
                        type = classification.type,
                        title = classification.title,
                        detail = infrastructureDetail(tags, classification),
                        direction = tags.optString("direction").ifBlank { null },
                        trafficSignCode = classification.code,
                        exitNumber = classification.exitNumber,
                    )
            }
        }
        return RoadInfrastructureResult(
            points = points.distinctBy(RoadInfrastructurePoint::id),
            autobahnRefs = autobahnRefs,
            timestampMillis = timestampMillis,
            usedSavedData = false,
            facilities = facilities.distinctBy(RoadFacility::id),
            speedLimitSections = speedLimitSections.distinctBy(SpeedLimitRoadSection::id),
            laneTopologySections = laneTopologySections.distinctBy(LaneTopologyWaySection::id),
        )
    }

    private fun laneTopologySection(
        element: JSONObject,
        tags: JSONObject,
    ): LaneTopologyWaySection? {
        if (element.optString("type") != "way") return null
        if (!tags.has("turn:lanes") && !tags.has("destination:lanes")) return null
        val geometryJson = element.optJSONArray("geometry") ?: return null
        val geometry =
            buildList {
                for (index in 0 until geometryJson.length()) {
                    val coordinate = geometryJson.optJSONObject(index) ?: continue
                    if (!coordinate.has("lat") || !coordinate.has("lon")) continue
                    add(RoadCoordinate(coordinate.optDouble("lat"), coordinate.optDouble("lon")))
                }
            }
        if (geometry.size < 2) return null
        return LaneTopologyWaySection(
            id = "way/${element.optLong("id")}",
            geometry = geometry,
            ref = tags.optString("ref").takeIf(String::isNotBlank),
            lanes = tags.optString("lanes").takeIf(String::isNotBlank),
            lanesForward = tags.optString("lanes:forward").takeIf(String::isNotBlank),
            turnLanes = tags.optString("turn:lanes").takeIf(String::isNotBlank),
            destinationLanes = tags.optString("destination:lanes").takeIf(String::isNotBlank),
            destinationRefLanes = tags.optString("destination:ref:lanes").takeIf(String::isNotBlank),
            changeLanes = tags.optString("change:lanes").takeIf(String::isNotBlank),
        )
    }

    private fun speedLimitSection(
        element: org.json.JSONObject,
        tags: JSONObject,
    ): SpeedLimitRoadSection? {
        if (element.optString("type") != "way" || !SPEED_LIMIT_KEYS.any(tags::has)) return null
        val geometryJson = element.optJSONArray("geometry") ?: return null
        val geometry =
            buildList {
                for (index in 0 until geometryJson.length()) {
                    val coordinate = geometryJson.optJSONObject(index) ?: continue
                    if (!coordinate.has("lat") || !coordinate.has("lon")) continue
                    add(RoadCoordinate(coordinate.optDouble("lat"), coordinate.optDouble("lon")))
                }
            }
        if (geometry.size < 2) return null
        val standard = parseSpeedKph(tags.optString("maxspeed"))
        val directional =
            listOfNotNull(
                parseSpeedKph(tags.optString("maxspeed:forward")),
                parseSpeedKph(tags.optString("maxspeed:backward")),
            )
        val implicit =
            when (
                tags
                    .optString("maxspeed:type")
                    .ifBlank {
                        tags.optString("source:maxspeed")
                    }.uppercase(Locale.ROOT)
            ) {
                "DE:URBAN" -> 50
                "DE:RURAL" -> 100
                else -> null
            }
        val rawStandard = tags.optString("maxspeed").lowercase(Locale.ROOT)
        return SpeedLimitRoadSection(
            id = "way/${element.optLong("id")}",
            geometry = geometry,
            speedLimitKph = standard ?: directional.minOrNull() ?: implicit,
            label = speedLimitTitle(tags),
            unlimited =
                rawStandard in setOf("none", "unlimited") ||
                    tags.optString("maxspeed:type").equals("DE:motorway", ignoreCase = true),
            conditionalOrVariable =
                tags.optString("maxspeed:conditional").isNotBlank() ||
                    tags.optString("maxspeed:variable").isNotBlank(),
        )
    }

    private fun parseSpeedKph(raw: String): Int? {
        val numeric = SPEED_NUMBER.find(raw)?.value?.toDoubleOrNull() ?: return null
        val converted = if (raw.contains("mph", ignoreCase = true)) numeric * 1.609344 else numeric
        return converted.toInt().takeIf { it in 1..300 }
    }

    private fun restroom(
        tags: JSONObject,
        element: JSONObject,
        latitude: Double,
        longitude: Double,
    ): RoadFacility? {
        val dedicated = tags.optString("amenity") == "toilets"
        val embedded = tags.optString("toilets") == "yes"
        if (!dedicated && !embedded) return null

        val prefix = if (dedicated) "" else "toilets:"
        val access =
            tags
                .optString("${prefix}access")
                .ifBlank {
                    if (dedicated) tags.optString("access") else ""
                }.lowercase(Locale.ROOT)
        if (access in setOf("private", "no")) return null

        val feeTag =
            tags
                .optString("${prefix}fee")
                .ifBlank {
                    if (dedicated) tags.optString("fee") else ""
                }.lowercase(Locale.ROOT)
        val charge =
            tags.optString("${prefix}charge").ifBlank {
                if (dedicated) tags.optString("charge") else ""
            }
        val feeStatus =
            when {
                access in setOf("customers", "customer") -> RestroomFeeStatus.PAID
                feeTag in setOf("no", "0", "free") -> RestroomFeeStatus.FREE
                feeTag in setOf("yes", "paid") || charge.isNotBlank() -> RestroomFeeStatus.PAID
                else -> RestroomFeeStatus.UNKNOWN
            }
        val feeLabel =
            when (feeStatus) {
                RestroomFeeStatus.FREE -> "Free restroom"
                RestroomFeeStatus.PAID -> "Paid restroom"
                RestroomFeeStatus.UNKNOWN -> "Restroom · fee unknown"
            }
        val name = tags.optString("name").takeIf(String::isNotBlank)
        val detail =
            buildList {
                add("Within 1 km of an Autobahn")
                add(
                    when (feeStatus) {
                        RestroomFeeStatus.FREE -> "No fee mapped"
                        RestroomFeeStatus.PAID ->
                            charge
                                .takeIf(String::isNotBlank)
                                ?.let { "Charge $it" }
                                ?: if (access in setOf("customers", "customer")) {
                                    "Customers only / purchase may be required"
                                } else {
                                    "Fee required"
                                }
                        RestroomFeeStatus.UNKNOWN -> "Fee not mapped"
                    },
                )
                tags
                    .optString("opening_hours")
                    .takeIf(String::isNotBlank)
                    ?.let { add("Hours $it") }
                val wheelchair =
                    tags.optString("${prefix}wheelchair").ifBlank {
                        if (dedicated) tags.optString("wheelchair") else ""
                    }
                wheelchair.takeIf(String::isNotBlank)?.let { add("Wheelchair $it") }
                access.takeIf(String::isNotBlank)?.let { add("Access $it") }
                tags.optString("operator").takeIf(String::isNotBlank)?.let { add(it) }
                add("OpenStreetMap")
            }.distinct().joinToString(" · ")
        return RoadFacility(
            id = "osm-restroom-${element.optString("type", "object")}/${element.optLong("id")}",
            roadId = "Autobahn nearby",
            type = RoadFacilityType.RESTROOM,
            coordinate = RoadCoordinate(latitude, longitude),
            title = name?.let { "$feeLabel · $it" } ?: feeLabel,
            subtitle = access,
            detail = detail,
            restroomFeeStatus = feeStatus,
        )
    }

    private fun classify(tags: JSONObject): Classification? {
        val highway = tags.optString("highway")
        if (highway == "motorway_junction") {
            val ref = tags.optString("ref").takeIf(String::isNotBlank)
            val name = tags.optString("name").takeIf(String::isNotBlank)
            val title =
                when {
                    ref != null -> "Exit $ref"
                    name != null -> name
                    else -> "Motorway junction"
                }
            return Classification(RoadInfrastructureType.MOTORWAY_JUNCTION, title, null, exitNumber = ref)
        }
        if (highway == "traffic_signals") {
            return Classification(
                RoadInfrastructureType.TRAFFIC_SIGNAL,
                "Traffic signal",
                null,
            )
        }
        if (highway == "stop") {
            return Classification(RoadInfrastructureType.STOP_SIGN, "Stop sign", "DE:206")
        }
        if (highway == "give_way") {
            return Classification(RoadInfrastructureType.GIVE_WAY_SIGN, "Give way", "DE:205")
        }
        if (highway == "crossing") {
            return Classification(
                RoadInfrastructureType.PEDESTRIAN_CROSSING,
                if (tags.optString("crossing") == "uncontrolled") {
                    "Uncontrolled crossing"
                } else {
                    "Pedestrian crossing"
                },
                null,
            )
        }
        if (tags.optString("railway") in setOf("level_crossing", "crossing")) {
            return Classification(RoadInfrastructureType.RAILWAY_CROSSING, "Railway crossing", null)
        }
        if (tags.has("traffic_calming")) {
            return Classification(
                RoadInfrastructureType.TRAFFIC_CALMING,
                tags.optString("traffic_calming").replace('_', ' ').titleCase(),
                null,
            )
        }
        if (tags.optString("amenity") == "school") {
            return Classification(RoadInfrastructureType.SCHOOL_ZONE, "School zone", null)
        }
        if (tags.optString("tunnel") == "yes") {
            return Classification(RoadInfrastructureType.TUNNEL, "Tunnel", null)
        }
        if (tags.optString("bridge") == "yes") {
            return Classification(RoadInfrastructureType.BRIDGE, "Bridge", null)
        }
        if (DIMENSION_KEYS.any(tags::has)) {
            val restriction = DIMENSION_KEYS.firstOrNull(tags::has).orEmpty()
            return Classification(
                RoadInfrastructureType.DIMENSION_RESTRICTION,
                restriction.replace("max", "Maximum ").replace('_', ' ').titleCase(),
                null,
            )
        }
        if (tags.optString("toll") == "yes") {
            return Classification(RoadInfrastructureType.TOLL, "Toll road", null)
        }
        if (SPEED_LIMIT_KEYS.any(tags::has)) {
            return Classification(
                RoadInfrastructureType.SPEED_LIMIT_SIGN,
                speedLimitTitle(tags),
                null,
            )
        }
        if (tags.has("incline")) {
            return Classification(
                RoadInfrastructureType.STEEP_GRADE,
                inclineTitle(tags.optString("incline")),
                null,
            )
        }
        if (tags.has("smoothness") || tags.optString("surface") in RISKY_SURFACES) {
            return Classification(RoadInfrastructureType.SURFACE_HAZARD, "Road surface", null)
        }
        val sign =
            listOf(
                tags.optString("traffic_sign"),
                tags.optString("traffic_sign:forward"),
                tags.optString("traffic_sign:backward"),
            ).firstOrNull(String::isNotBlank) ?: return null
        val upper = sign.uppercase(Locale.ROOT)
        return when {
            upper.contains("DE:206") || upper.contains("STOP") ->
                Classification(RoadInfrastructureType.STOP_SIGN, "Stop sign", sign)
            upper.contains("DE:205") || upper.contains("GIVE_WAY") ->
                Classification(RoadInfrastructureType.GIVE_WAY_SIGN, "Give way", sign)
            upper.contains("DE:301") ->
                Classification(
                    RoadInfrastructureType.PRIORITY_AT_JUNCTION_SIGN,
                    "Priority at this junction",
                    sign,
                )
            upper.contains("DE:306") || upper.contains("PRIORITY_ROAD") ->
                Classification(
                    RoadInfrastructureType.PRIORITY_ROAD_SIGN,
                    "Priority road",
                    sign,
                )
            ROAD_RULE_END_CODES.any(upper::contains) ->
                Classification(
                    RoadInfrastructureType.ROAD_RULE_END,
                    roadRuleBoundaryTitle(upper, starts = false),
                    sign,
                )
            ROAD_RULE_START_CODES.any(upper::contains) ->
                Classification(
                    RoadInfrastructureType.ROAD_RULE_START,
                    roadRuleBoundaryTitle(upper, starts = true),
                    sign,
                )
            upper.contains("DE:274") || upper.contains("MAXSPEED") -> {
                val speed =
                    SPEED_LIMIT_CODE.find(sign)?.groupValues?.getOrNull(1)
                        ?: if (upper.contains("MAXSPEED")) SPEED_NUMBER.find(sign)?.value else null
                Classification(
                    RoadInfrastructureType.SPEED_LIMIT_SIGN,
                    speed?.let { "Speed limit $it" } ?: "Speed limit",
                    sign,
                )
            }
            upper.contains("DE:250") ||
                upper.contains("DE:251") ||
                upper.contains("DE:253") ||
                upper.contains("DE:260") ->
                Classification(
                    RoadInfrastructureType.TRAFFIC_RESTRICTION,
                    "Traffic restriction",
                    sign,
                )
            else -> Classification(RoadInfrastructureType.OTHER_SIGN, "Road sign", sign)
        }
    }

    private fun roadRuleBoundaryTitle(
        sign: String,
        starts: Boolean,
    ): String {
        val boundary = if (starts) "starts" else "ends"
        return when {
            sign.contains("DE:274") || sign.contains("DE:278") -> "Speed-limit zone $boundary"
            sign.contains("DE:325") -> "Traffic-calmed area $boundary"
            sign.contains("DE:330") -> "Autobahn $boundary"
            sign.contains("DE:331") -> "Expressway $boundary"
            sign.contains("DE:310") || sign.contains("DE:311") -> "Built-up area $boundary"
            else -> "Road restriction $boundary"
        }
    }

    private fun inclineTitle(rawIncline: String): String {
        val normalized = rawIncline.trim().lowercase(Locale.ROOT)
        return when (normalized) {
            "up" -> "Mapped uphill grade"
            "down" -> "Mapped downhill grade"
            "up/down" -> "Mapped rolling grade"
            else -> "Mapped incline $rawIncline"
        }
    }

    private fun speedLimitTitle(tags: JSONObject): String {
        val standard = tags.optString("maxspeed").trim()
        val forward = tags.optString("maxspeed:forward").trim()
        val backward = tags.optString("maxspeed:backward").trim()
        val lanes =
            tags
                .optString("maxspeed:lanes")
                .ifBlank {
                    tags.optString("maxspeed:lanes:forward").ifBlank {
                        tags.optString("maxspeed:lanes:backward")
                    }
                }.trim()
        val implicit =
            tags
                .optString("maxspeed:type")
                .ifBlank {
                    tags.optString("source:maxspeed")
                }.trim()
        val title =
            when {
                standard.isNotBlank() -> speedValueTitle(standard)
                forward.isNotBlank() && forward == backward -> speedValueTitle(forward)
                forward.isNotBlank() || backward.isNotBlank() -> {
                    val values =
                        listOfNotNull(
                            forward.takeIf(String::isNotBlank)?.let { "forward $it" },
                            backward.takeIf(String::isNotBlank)?.let { "backward $it" },
                        ).joinToString(" · ")
                    "Directional limits · $values"
                }
                lanes.isNotBlank() -> "Lane speed limits · ${lanes.replace('|', '/')}"
                implicit.isNotBlank() -> implicitSpeedTitle(implicit)
                else -> "Conditional or variable speed limit"
            }
        val hasConditional = tags.optString("maxspeed:conditional").isNotBlank()
        val isVariable =
            tags.optString("maxspeed:variable").lowercase(Locale.ROOT) in
                setOf("yes", "variable", "signs")
        return buildString {
            append(title)
            if (hasConditional) append(" · conditional")
            if (isVariable) append(" · variable")
        }
    }

    private fun speedValueTitle(raw: String): String =
        when (raw.lowercase(Locale.ROOT)) {
            "none", "unlimited" -> "No fixed speed limit"
            "walk", "walking_speed" -> "Walking-speed limit"
            "signals", "variable" -> "Variable speed limit"
            else -> "Speed limit $raw"
        }

    private fun implicitSpeedTitle(raw: String): String =
        when (raw.uppercase(Locale.ROOT)) {
            "DE:URBAN" -> "Implicit speed limit 50"
            "DE:RURAL" -> "Implicit speed limit 100"
            "DE:MOTORWAY" -> "No fixed speed limit"
            "DE:LIVING_STREET" -> "Walking-speed limit"
            else -> "Implicit speed rule · $raw"
        }

    private fun infrastructureDetail(
        tags: JSONObject,
        classification: Classification,
    ): String {
        val details = mutableListOf<String>()
        classification.code?.let { details += "Code $it" }
        tags.optString("direction").takeIf(String::isNotBlank)?.let { details += "Direction $it" }
        tags.optString("name").takeIf(String::isNotBlank)?.let(details::add)
        tags.optString("description").takeIf(String::isNotBlank)?.let(details::add)
        listOf(
            "crossing",
            "traffic_calming",
            "maxheight",
            "maxwidth",
            "maxlength",
            "maxweight",
            "maxaxleload",
            "incline",
            "surface",
            "smoothness",
            "lit",
            "maxspeed",
            "maxspeed:forward",
            "maxspeed:backward",
            "maxspeed:lanes",
            "maxspeed:lanes:forward",
            "maxspeed:lanes:backward",
            "maxspeed:conditional",
            "maxspeed:variable",
            "maxspeed:advisory",
            "maxspeed:type",
            "source:maxspeed",
        ).forEach { key ->
            tags.optString(key).takeIf(String::isNotBlank)?.let { value ->
                details += "${key.replace('_', ' ')} $value"
            }
        }
        if (classification.type == RoadInfrastructureType.TRAFFIC_SIGNAL) {
            details += "Signal location · live phase unavailable"
        }
        if (classification.type == RoadInfrastructureType.MOTORWAY_JUNCTION) {
            val toward =
                tags.optString("destination").ifBlank {
                    listOfNotNull(
                        tags.optString("destination:ref").takeIf(String::isNotBlank),
                        tags.optString("destination:street").takeIf(String::isNotBlank),
                    ).joinToString(" · ").ifBlank { null }
                }
            toward?.let { details += "toward $it" }
        }
        details += "OpenStreetMap"
        return details.distinct().joinToString(" · ")
    }

    private fun download(query: String): String {
        val payload = "data=${URLEncoder.encode(query, Charsets.UTF_8.name())}"
        var lastFailure: Exception? = null
        OVERPASS_ENDPOINTS.forEach { endpoint ->
            try {
                return downloadFrom(endpoint, payload)
            } catch (error: Exception) {
                lastFailure = error
            }
        }
        throw IllegalStateException("Every OpenStreetMap query endpoint was unavailable", lastFailure)
    }

    private fun downloadFrom(
        endpoint: String,
        payload: String,
    ): String {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty(
                "User-Agent",
                "RoadPulse/0.1 personal Android navigation prototype",
            )
            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded; charset=UTF-8",
            )
            connection.doOutput = true
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) {
                error("OpenStreetMap query returned HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun overpassQuery(bounds: ExpandedBounds): String {
        val bbox =
            String.format(
                Locale.US,
                "%.6f,%.6f,%.6f,%.6f",
                bounds.south,
                bounds.west,
                bounds.north,
                bounds.east,
            )
        return "[out:json][timeout:20];" +
            "way[\"highway\"=\"motorway\"]($bbox)->.motorways;(" +
            "node[\"highway\"=\"traffic_signals\"]($bbox);" +
            "node[\"highway\"~\"^(stop|give_way)$\"]($bbox);" +
            "node[\"traffic_sign\"]($bbox);" +
            "node[\"traffic_sign:forward\"]($bbox);" +
            "node[\"traffic_sign:backward\"]($bbox);" +
            "node[\"highway\"=\"crossing\"]($bbox);" +
            "node[\"railway\"~\"^(level_crossing|crossing)$\"]($bbox);" +
            "node[\"traffic_calming\"]($bbox);" +
            "nwr[\"amenity\"=\"school\"]($bbox);" +
            "way[\"highway\"][\"tunnel\"=\"yes\"]($bbox);" +
            "way[\"highway\"][\"bridge\"=\"yes\"]($bbox);" +
            "nwr[\"maxheight\"]($bbox);" +
            "nwr[\"maxweight\"]($bbox);" +
            "way[\"highway\"][\"toll\"=\"yes\"]($bbox);" +
            "way[\"highway\"][~\"^(maxspeed|maxspeed:forward|maxspeed:backward|maxspeed:conditional|" +
            "maxspeed:lanes(:forward|:backward)?|maxspeed:variable|maxspeed:type|source:maxspeed)$\"" +
            "~\".\"]($bbox);" +
            "way[\"highway\"][\"incline\"]($bbox);" +
            "way[\"highway\"][\"smoothness\"]($bbox);" +
            "node[\"highway\"=\"motorway_junction\"]($bbox);" +
            "way[\"highway\"~\"^(motorway|trunk|primary)$\"][\"turn:lanes\"]($bbox);" +
            "way[\"highway\"~\"^(motorway|trunk|primary)$\"][\"destination:lanes\"]($bbox);" +
            ".motorways;" +
            "nwr(around.motorways:1000)[\"amenity\"=\"toilets\"];" +
            "nwr(around.motorways:1000)[\"toilets\"=\"yes\"];" +
            ");out center tags geom;"
    }

    private fun String.titleCase(): String =
        replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase(Locale.ROOT) else character.toString()
        }

    private fun normaliseAutobahnRefs(raw: String): List<String> =
        raw
            .split(';', ',')
            .map { it.replace(" ", "").uppercase(Locale.ROOT) }
            .filter { AUTOBAHN_REF.matches(it) }

    private fun saveAtomically(
        file: File,
        value: String,
    ) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(value, Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            file.writeText(value, Charsets.UTF_8)
            temporary.delete()
        }
    }

    private fun hasValidatedInternet(): Boolean {
        val manager = appContext?.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun osmDataTimestamp(json: String): Long =
        runCatching {
            Instant
                .parse(
                    JSONObject(json).optJSONObject("osm3s")?.optString("timestamp_osm_base").orEmpty(),
                ).toEpochMilli()
        }.getOrDefault(0L)

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun longitudeIsInside(
        longitude: Double,
        west: Double,
        east: Double,
    ): Boolean = if (west <= east) longitude in west..east else longitude >= west || longitude <= east

    private data class Classification(
        val type: RoadInfrastructureType,
        val title: String,
        val code: String?,
        val exitNumber: String? = null,
    )

    private data class ExpandedBounds(
        val south: Double,
        val west: Double,
        val north: Double,
        val east: Double,
    )

    companion object {
        private val OVERPASS_ENDPOINTS =
            listOf(
                "https://overpass-api.de/api/interpreter",
                "https://overpass.private.coffee/api/interpreter",
            )
        private const val CACHE_DIRECTORY = "road-data/openstreetmap-infrastructure"
        private const val CACHE_GRID_DEGREES = 0.05
        private const val CACHE_MAX_AGE_MILLIS = 6 * 60 * 60 * 1_000L
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 25_000
        private val SPEED_NUMBER = Regex("(?<![0-9])[0-9]{1,3}(?![0-9])")
        private val SPEED_LIMIT_CODE = Regex("(?:DE:)?274(?:[.-])([0-9]{1,3})", RegexOption.IGNORE_CASE)
        private val AUTOBAHN_REF = Regex("A[0-9]{1,3}[A-Z]?")
        private val ROAD_RULE_START_CODES =
            listOf(
                "DE:274.1",
                "DE:310",
                "DE:325.1",
                "DE:330.1",
                "DE:331.1",
            )
        private val ROAD_RULE_END_CODES =
            listOf(
                "DE:274.2",
                "DE:278",
                "DE:282",
                "DE:311",
                "DE:325.2",
                "DE:330.2",
                "DE:331.2",
            )
        private val DIMENSION_KEYS =
            listOf(
                "maxheight",
                "maxwidth",
                "maxlength",
                "maxweight",
                "maxaxleload",
            )
        private val SPEED_LIMIT_KEYS =
            listOf(
                "maxspeed",
                "maxspeed:forward",
                "maxspeed:backward",
                "maxspeed:lanes",
                "maxspeed:lanes:forward",
                "maxspeed:lanes:backward",
                "maxspeed:conditional",
                "maxspeed:variable",
                "maxspeed:type",
                "source:maxspeed",
            )
        private val RISKY_SURFACES =
            setOf(
                "unpaved",
                "gravel",
                "ground",
                "dirt",
                "mud",
                "cobblestone",
            )
        private val QUERY_LOCK = Any()
    }
}
