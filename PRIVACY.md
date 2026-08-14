# RoadPulse privacy notice

RoadPulse is currently a private, locally installed test application.

- RoadPulse stores the selected destination, monthly API usage counters, camera-test state,
  and downloaded Open-GATSO, French, Brussels, Luxembourg, and OpenStreetMap camera data
  locally on the Android device.
- RoadPulse does not operate a backend server and does not sell personal data.
- Google Maps Platform processes destination searches, device location, routes, traffic,
  and navigation data when Google-powered features are used. Google's own Navigation terms
  and privacy disclosures are shown before first use.
- When an optimized supermarket or fuel stop is enabled, a corridor around the remaining route
  (points along the route plus a 3 km buffer) is sent to the public OpenStreetMap Overpass
  service to find supermarkets and fuel stations, rather than to Google Places. RoadPulse
  evaluates each candidate's mapped `opening_hours` locally to choose a confirmed-open
  minimum-detour stop, and estimates the detour from the route geometry it already has instead
  of requesting a route from Google. The toggle state is stored locally; Overpass responses are
  cached in app-private storage for up to six hours and are not otherwise retained.
- Open-GATSO and official government camera files are downloaded directly from their public
  sources for parked planning. The files and their source timestamps remain in private app storage.
- At local map zoom levels, the visible map bounds are sent to the public OpenStreetMap
  Overpass service to request mapped enforcement points. Retrieved regions are stored in
  persistent app-private storage for offline use and become eligible for refresh after two
  hours; RoadPulse does not send an account identifier to that service.
- When the road-context layer is enabled, visible map bounds are also sent to Overpass for mapped
  road-sign, traffic-signal, and nearby motorway-reference data. Those regions are stored locally
  for offline use and become eligible for refresh after six hours.
- During navigation, the bounding area around up to 15 km of route ahead may also be sent to
  Overpass to retrieve mapped maxspeed road geometry. RoadPulse compares that geometry locally
  with the active route and current GPS speed to estimate the next limit-change distance and time.
- Nearby motorway references—not the user's identity—are sent to the public Autobahn GmbH API to
  retrieve warnings, queues, roadworks, closures, event geometry, webcams, parking and charging
  locations. Responses are cached locally.
- The visible bounds are sent to DWD's public WFS to retrieve official weather warnings. The app
  also downloads DWD's national compressed road-weather forecast and locally selects the nearest
  station to the queried map centre. DWD responses are retained in private offline storage.
- During active navigation, rounded points along the next two kilometres of route are sent to
  Open-Meteo's Elevation API. Returned Copernicus terrain elevations are retained in app-private
  storage for offline reuse; no RoadPulse account identifier is sent.
- When a fuel smart stop is enabled and a Tankerkoenig API key is configured, a handful of points
  along the route are sent to the free Tankerkoenig API to attach a live price to matching
  stations. Germany-only; inert without a configured key. Responses are cached locally for up to
  20 minutes; no RoadPulse account identifier is sent.
- When an Open Charge Map API key is configured, the visible map bounds (parked) or route
  corridor (driving) are sent to the free Open Charge Map registry to show EV chargers beyond
  the Autobahn network. Inert without a configured key. Responses are cached locally for up to
  six hours; no RoadPulse account identifier is sent.
- Real German enforcement locations are not sent to or displayed in Android Auto driving mode.
- During active guidance, RoadPulse resolves the current country using Android's geocoder,
  falling back to the free OpenStreetMap Nominatim service if the device's geocoder is
  unavailable or fails, before enabling route-camera features. Unknown countries and Germany fail
  closed. When permitted, the app combines saved Open-GATSO/official data with cached or
  refreshed OpenStreetMap camera data and retains only cameras matched to the active route for
  the driving UI.

- **Speed compliance.** The active-navigation speed ring turns red when current GPS speed exceeds
  the mapped speed limit for the current road (speed-vs-limit only, no camera data involved).
  Separately, an amber breathing ring and a generic "Check speed" label appear whenever a mapped
  enforcement camera is within 3 km of the route ahead - by explicit product decision this runs
  in every country, including Germany, and does not consult the country-based policy gate
  described above for the camera list/panel. No camera location, type, or countdown is shown
  anywhere in this feature - only the generic nudge. This is a **known, unresolved compliance
  risk**: Germany's StVO Section 23 restricts using or carrying a ready-for-use device that warns
  of traffic enforcement while driving, and this feature's factual behavior (a phone signal tied
  to camera proximity while driving) may fall within that restriction regardless of how generic
  the on-screen text is. This has not had legal review. Do not treat its existence in this build
  as a compliance conclusion.

This notice must be reviewed and expanded before RoadPulse is shared or distributed.
