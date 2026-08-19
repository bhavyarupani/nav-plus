// Dev-machine-only tools that build a region's GraphHopper routing graph and search index - the
// counterpart to tools/planetiler/ (which builds the map tiles). Not part of the Android app.
plugins {
    application
    java
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Same pinned version as the Android app (app/build.gradle.kts) - the graph this produces
    // must load with GraphHopperRoutingEngine's `GraphHopper().load()` on-device, which uses the
    // same 7.0 API and file format.
    implementation("com.graphhopper:graphhopper-core:7.0")
    // Build-time only, not shipped in the app - writes the FTS4 SQLite search index.
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")
}

tasks.register<JavaExec>("importGraphHopper") {
    group = "region-build"
    description = "Imports one region's .osm.pbf into a GraphHopper routing graph. " +
        "Args: <osm.pbf path> <output graph dir>"
    mainClass.set("com.roadpulse.regionbuild.GraphHopperImporter")
    classpath = sourceSets["main"].runtimeClasspath
    args = (project.findProperty("args") as String? ?: "").split(" ").filter { it.isNotBlank() }
    // No heap was set at all before this - JavaExec fell back to the JVM's default (roughly a
    // quarter of physical RAM), which OOM'd importing Italy's ~2.1GB extract on this 8GB dev
    // machine. GraphHopper's OSM import holds node-location and way-processing buffers in heap
    // regardless of the target graph's own on-disk storage type, so this scales with input size,
    // not output size.
    maxHeapSize = "6g"
}

tasks.register<JavaExec>("buildSearchIndex") {
    group = "region-build"
    description = "Builds a region's SQLite FTS4 search index from its .osm.pbf. " +
        "Args: <osm.pbf path> <output search.db path>"
    mainClass.set("com.roadpulse.regionbuild.BuildSearchIndex")
    classpath = sourceSets["main"].runtimeClasspath
    args = (project.findProperty("args") as String? ?: "").split(" ").filter { it.isNotBlank() }
    maxHeapSize = "6g"
}

application {
    mainClass.set("com.roadpulse.regionbuild.GraphHopperImporter")
}
