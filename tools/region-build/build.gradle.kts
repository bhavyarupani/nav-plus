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
}

tasks.register<JavaExec>("buildSearchIndex") {
    group = "region-build"
    description = "Builds a region's SQLite FTS4 search index from its .osm.pbf. " +
        "Args: <osm.pbf path> <output search.db path>"
    mainClass.set("com.roadpulse.regionbuild.BuildSearchIndex")
    classpath = sourceSets["main"].runtimeClasspath
    args = (project.findProperty("args") as String? ?: "").split(" ").filter { it.isNotBlank() }
}

application {
    mainClass.set("com.roadpulse.regionbuild.GraphHopperImporter")
}
