# Third-party notices

This file is RoadPulse's third-party notices file, covering both the data sources used at
runtime (below, under "Third-party data") and the software dependencies compiled into the app.
RoadPulse itself is proprietary — see `LICENSE`.

## Software dependencies

| Dependency | License | Notes |
|---|---|---|
| AndroidX (`androidx.activity`, `androidx.fragment`, `androidx.car.app`, `androidx.core`, `androidx.lifecycle`) | Apache License 2.0 | Google, open source |
| `org.maplibre.gl:android-sdk`, `org.maplibre.gl:android-plugin-annotation-v9` | BSD-2-Clause | Map rendering, replacing Google Maps SDK — see ZERO_COST_ARCHITECTURE.md |
| `com.graphhopper:graphhopper-core` | Apache License 2.0 | On-device routing, replacing Google Navigation SDK — see ZERO_COST_ARCHITECTURE.md |
| `org.apache.commons:commons-compress` | Apache License 2.0 | |
| `com.android.tools:desugar_jdk_libs_nio` | GPLv2 with Classpath Exception (OpenJDK-derived portions) + Apache License 2.0 (Google's wrapper) | Build-time only; not a shipped source dependency in the usual sense |
| `junit:junit` | Eclipse Public License 1.0 | Test-only, not included in the release APK |
| `org.json:json` | The JSON License (permissive, non-OSI-approved; includes the "shall be used for Good, not Evil" clause) | Test-only in this project's declared scope |
| `org.jlleitschuh.gradle.ktlint` (build tooling) | MIT License | Build-time only, not shipped |
| `com.google.android.libraries.mapsplatform.secrets-gradle-plugin` (build tooling) | Apache License 2.0 | Build-time only, not shipped; injects the Tankerkoenig/Open Charge Map API keys from `local.properties` |

As of the free-stack migration (see ZERO_COST_ARCHITECTURE.md), RoadPulse no longer depends on
Google Maps SDK, Google Navigation SDK, or Google Places SDK — the `com.google.android.libraries.*`
proprietary dependencies previously listed here, and the Google Maps Platform Terms of Service
compliance obligations they carried, no longer apply to this app.

## Icon and visual assets

| Asset | Source | Licence | Original/modified | Used in | Local path |
|---|---|---|---|---|---|
| `rp_role_signboard_autobahn_blue`, `rp_role_signboard_direction_yellow`, `rp_role_signboard_lane_recommended`, `rp_role_signboard_lane_muted` | RoadPulse | Proprietary (project asset) | Original | `SignboardRenderer` (mobile signboard panel + Android Auto junction image) | `app/src/main/res/values/colors.xml` |

Everything the signboard/lane feature draws (Autobahn/direction-sign backgrounds, borders, exit
number, destination text, lane strip, arrows) is drawn programmatically by `SignboardRenderer`
from these colour tokens and the live `SignboardGuidance` data — no bitmap or vector asset files
are shipped for it yet. The broader map-marker and icon-system redesign (Material Symbols import
for generic UI actions, original hand-authored vectors for the navigation puck, POI markers,
maneuver/lane arrows, and Autobahn/Bundesstraße road shields) is scoped but not started; this
table will gain a row per asset as that work lands, each with source, licence, original-vs-
modified status, and where it's used, per the project's asset budget (€0 for v1 — Material
Symbols under Apache 2.0, everything else hand-authored).

## Google Maps Platform compliance (retired)

Through the free-stack migration (see ZERO_COST_ARCHITECTURE.md), RoadPulse displayed OpenStreetMap-
sourced markers and signboard/lane enrichment on top of the Google Map/Navigation SDK surface, which
required specific Google Maps Platform Terms of Service compliance: Android Auto projection
permission, a "no use with non-Google maps" reading, visual distinction between Google- and
OSM-sourced content (tracked per-element via `GuidanceDataSource`), and treating Google's own route/
maneuver as authoritative over OSM enrichment. That compliance analysis no longer applies — the
Google Map/Navigation SDK surface has been fully replaced by MapLibre Native (rendering) and
GraphHopper (routing), both open source, and `GuidanceDataSource` no longer has a Google-sourced
case at all. OSM signboard/lane enrichment is unchanged in its own right: it still only adds
supplementary destination/lane-topology text alongside the active maneuver, never altering the
route or turn instruction itself.

## Third-party data

## Open-GATSO-POI

RoadPulse can download the generated `GATSO_ALL.csv` dataset from
[Open-GATSO-POI](https://github.com/1e1/Open-GATSO-POI). It is checked automatically when due
or manually from settings; the dataset is not bundled into the APK.

- Open-GATSO-POI transformation code: MIT License
- European community alert data: Lufop, CC BY-SA 4.0
- French official fixed-camera data: Ministère de l'Intérieur, Licence Ouverte / Open Licence 2.0

The app must keep this attribution visible anywhere the optional dataset is used.
Camera warnings must remain disabled while driving in Germany. This policy control
is independent of whether the data has been downloaded.

## OpenStreetMap enforcement data

When the stationary phone camera layer is open at a local zoom level, RoadPulse queries
the public OpenStreetMap Overpass API for objects tagged as `highway=speed_camera` and
enforcement relations for maximum-speed, average-speed, and traffic-signal enforcement.
Responses are stored in persistent app-private storage and merged with Open-GATSO points.
Saved regions are usable offline and are considered refreshable after two hours. Points
within 30 metres are treated as possible duplicates only when camera type and known speed
agree. Different IDs from the same source are kept separate. Merged points retain every
source record rather than discarding road, direction, operator, installation date, activity
state, raw type, source ID, or source timestamp. Android
may remove this data only if the user clears RoadPulse storage or uninstalls the app.

RoadPulse checks the daily Open-GATSO build when its saved copy is at least 18 hours old
and schedules a persisted daily network refresh. Android controls the exact background-job
execution time. Network refreshes are attempted only on a validated internet connection. If
either remote service is unavailable, RoadPulse keeps using saved data.

OpenStreetMap data is available under the Open Data Commons Open Database License (ODbL):
https://www.openstreetmap.org/copyright

The app must display `© OpenStreetMap contributors` when this layer is used. The public
Overpass services are best-effort and must not be treated as a production SLA. RoadPulse tries
the main German public instance first and the public Private.coffee instance as failover.

## Official national and regional feeds

RoadPulse also downloads and preserves the original source files for these public feeds:

- French Ministry of the Interior, fixed-camera CSV, Open Licence 2.0:
  https://www.data.gouv.fr/datasets/liste-des-radars-fixes-en-france
- Brussels Mobility regional speed cameras, WFS/GeoJSON, CC0:
  https://data.mobility.brussels/en/info/speedcameras/
- Luxembourg Administration des Ponts et Chaussées fixed cameras, GeoJSON, CC0:
  https://data.public.lu/en/datasets/pch-emplacement-des-radars-fixes/

French and Luxembourg snapshots are checked weekly. Brussels is checked daily. Each download
has a strict size limit, schema parsing, geographic validation, and a minimum expected point
count. A new file becomes active only after validation; on any failure the last good offline
copy remains in use. Inactive Brussels records remain present in the original saved source file
but are not presented as active enforcement locations.

These official feeds supplement rather than replace Open-GATSO and OpenStreetMap. A conservative
30-metre semantic merge retains all source attribution and metadata. Source-level timestamps and
counts are visible in RoadPulse settings.

## Road infrastructure and live traffic

At local zoom levels, RoadPulse queries OpenStreetMap for mapped traffic-signal nodes, stop and
give-way nodes, generic `traffic_sign` nodes (including forward/backward tags), crossings,
traffic-calming objects, school zones, tunnels, bridges, tolls, height/width/weight/length limits,
steep grades, risky mapped surfaces, and nearby motorway references. Results are cached in
app-private storage for six hours and remain usable offline. OpenStreetMap coverage is
community-maintained, so a missing marker does not prove that the physical object is absent.

The same visible-road query includes mapped `maxspeed`, forward/backward, per-lane, conditional,
variable, `maxspeed:type`, and `source:maxspeed` metadata. Numeric limits use familiar circular
signs; walking, variable, and no-fixed-limit values use distinct labels. German `DE:urban`,
`DE:rural`, `DE:motorway`, and `DE:living_street` metadata is translated to the corresponding
general passenger-car rule. Conditional text is preserved, not automatically evaluated, because
weather, vehicle, school-day, temporary-sign, and time conditions require context the app may not
have. Missing limits are never guessed.

The same local query loads public toilets mapped within one kilometre of a visible motorway.
`fee=no` is labelled free; `fee=yes`, an explicit `charge`, or customer-only access is labelled
paid. Missing fee metadata is always labelled unknown. Opening hours, wheelchair access, access
restrictions, operator, and an explicit charge are shown when mapped. The result is cached with
the rest of the six-hour OpenStreetMap road-context snapshot and remains available offline.

For nearby German Autobahn references, RoadPulse requests current warning, roadwork, and closure
records from the Autobahn GmbH API at `https://verkehr.autobahn.de/o/autobahn`. Records are cached
for three minutes with saved-data fallback. The app retains direction, delay, source, and event
geometry and presents the first and last geometry points as the start and end. Scheduled future
records are not labelled as live.

RoadPulse also requests the API's webcam, lorry-parking, and electric-charging-station services.
These are cached for 30 minutes with saved-data fallback. Parking counts and charging power are
shown only when explicitly present in the response. Charging-station presence and advertised
power do not imply that a connector is currently free or working, and a webcam marker does not
imply that an image is currently available.
WC amenities explicitly included in Autobahn parking records are displayed too. The price is
labelled only when the response text explicitly says it is free or paid; otherwise it remains
unknown.

## DWD warnings and road weather

RoadPulse downloads the official Deutscher Wetterdienst SWSMOS Open Data forecast. DWD describes
this product as forecasts for roughly 1,700 road-weather stations. RoadPulse selects the nearest
station and retains a 24-hour window containing air/surface/dew-point temperatures,
liquid/solid precipitation, rain/snow probabilities, and DWD's road-condition code. The compressed
CSV is checked at most every 65 minutes and remains available offline as the last good copy.

Visible bounds are also sent to DWD's GeoServer WFS for
`dwd:Warnungen_Gemeinden_vereinigt`. Returned official warning geometries are cached for ten
minutes. A warning marker is an area-centre summary, not the exact warning boundary; users should
consult DWD for authoritative detail. Attribution: `Road weather and warnings: Deutscher
Wetterdienst (DWD)`.

- SWSMOS product: https://www.dwd.de/DE/leistungen/swis_swsmos/swis_swsmos.html
- DWD Open Data forecast: https://opendata.dwd.de/weather/local_forecasts/swsmos/
- DWD geoservices: https://www.dwd.de/EN/ourservices/geoservices/help/populaerelayer.html

BayernInfo documents free one-minute motorway detector data (speed and vehicle volume) through
Mobilithek. Mobilithek delivery requires a subscription ID and authorised machine access, so this
build contains the directional calculation but does not claim that it has detector measurements.
Given measured flow `q` in vehicles/hour and measured average speed `v` in km/h, RoadPulse derives
density `q / v` in vehicles/km and estimates vehicles within a selected horizon by multiplying
that density by horizon kilometres. The result is always an estimate, not a vehicle census.

BayernInfo also documents live traffic-light switching data via a separate OCIT-C interface on
request and subject to a data-transfer contract. RoadPulse therefore shows mapped signal
locations but never guesses red/amber/green phase state.

## Route elevation and estimated slope

During active guidance, RoadPulse sends rounded points from the next two kilometres of route to
Open-Meteo's Elevation API. Open-Meteo serves the worldwide Copernicus DEM 2021 GLO-90 terrain
model at 90-metre resolution and permits up to 100 coordinates per request. Returned terrain cells
are cached in app-private storage for offline reuse and become refreshable after 180 days.

Attribution: `Terrain elevation: Open-Meteo, Copernicus DEM GLO-90`.

The displayed percentage is the net terrain elevation change divided by route distance, not a
surveyed road gradient. It can differ materially on bridges, tunnels, cuttings, embankments, and
multi-level roads. OpenStreetMap's explicit `incline=*` road tags remain separate mapped evidence
and are displayed with their original value or direction when present.

- Open-Meteo Elevation API: https://open-meteo.com/en/docs/elevation-api
- Copernicus DEM: https://spacedata.copernicus.eu/collections/copernicus-digital-elevation-model

## Route stop search (supermarkets and fuel stations)

When the optimized supermarket or fuel stop is enabled, RoadPulse finds candidates via the
public OpenStreetMap Overpass API instead of Google Places "Search Along Route", to avoid the
per-search cost and monthly quota of a paid Google API. The route polyline (downsampled to at
most 120 points) is sent as an Overpass `around` corridor query with a 3 km buffer, tagged for
`shop=supermarket` (matched against the REWE/Kaufland/Netto/Lidl/ALDI brand list) and
`amenity=fuel`. Responses are cached in app-private storage for up to six hours.

RoadPulse evaluates each candidate's mapped `opening_hours` string locally (a bounded parser
covering `24/7`, day ranges/lists, comma-separated time ranges, and overnight wraparound) to
decide whether it will still be open on arrival and for at least 15 minutes after. Syntax it
does not recognise, or a missing `opening_hours` tag, is treated as unknown and the candidate is
excluded rather than guessed open.

Detour distance and arrival time are estimates, not a road-network routing result: OSM has no
router built in, so the added distance is approximated from the candidate's perpendicular offset
from the route (a there-and-back estimate) and arrival time is interpolated from the direct
route's total time and distance. This is less precise than the Google routing summaries the
feature previously used. OSM also has no equivalent of Google's live "temporarily closed"
business status; a store that closed permanently but is still mapped as `shop=supermarket` may
still appear as a candidate.

Attribution: `© OpenStreetMap contributors`.

## Live fuel prices (Tankerkoenig)

When the fuel smart stop finds a candidate, RoadPulse queries the free Tankerkoenig API
(creativecommons.tankerkoenig.de) at a handful of points along the route to attach a live
diesel/E5/E10 price where a station matches by proximity. Germany-only, requires a free API key
supplied by the person running the build (see README), and is inert without one. Responses are
cached in app-private storage for up to 20 minutes given how often pump prices change.

Tankerkoenig's data is licensed CC BY 4.0 and requires attribution naming the source, e.g. a
link to tankerkoenig.de. Attribution: `Fuel prices © Tankerkoenig.de (CC BY 4.0)`.

- Tankerkoenig API: https://creativecommons.tankerkoenig.de/

## EV chargers (Open Charge Map)

RoadPulse queries the free Open Charge Map registry (openchargemap.org) for EV charging
locations within the visible map area (parked) or route corridor (driving), to supplement the
Autobahn GmbH facilities feed, which only covers chargers directly on the Autobahn network.
Requires a free API key supplied by the person running the build (see README), and is inert
without one. Responses are cached in app-private storage for up to six hours.

Open Charge Map's stated terms require attribution to the platform. RoadPulse displays
`Charging data © Open Charge Map contributors` wherever this layer is shown; the exact license
family for a given POI's underlying data can vary by contributor per Open Charge Map's own
terms, so consult https://openchargemap.org/site/about/terms directly before any redistribution
use beyond in-app display.

- Open Charge Map API: https://openchargemap.org/site/develop/api

## Sources not integrated

Google Maps, Apple Maps, and Waze do not expose reusable speed-camera feeds for this app.
Blitzer.de has no public integration feed; its traffic data and private app interfaces are
not extracted. Lufop is already represented through the daily Open-GATSO build. Direct automated
Lufop API use requires an API key and an appropriate Pro or Business plan, so RoadPulse does not
silently use the testing tier. A future Blitzer/SCDB integration requires a written data licence
and a supported feed or export.

## Organic Maps

No Organic Maps source code, UI, or binary map data is currently shipped in RoadPulse.
Organic Maps was evaluated as a future offline map/search/routing engine. Its small
Android API is a deep-link wrapper that opens the separately installed Organic Maps app,
which does not satisfy RoadPulse's integrated-phone-and-Android-Auto design.

Embedding the full engine requires a native fork, visible Organic Maps and OpenStreetMap
attribution, and compliance with the Organic Maps binary data license. RoadPulse must
obtain written permission before any white-label use of Organic Maps-hosted `.mwm` data.
