package com.roadpulse.regionbuild;

import com.graphhopper.GraphHopper;
import com.graphhopper.config.CHProfile;
import com.graphhopper.config.Profile;

/**
 * Imports a Geofabrik .osm.pbf extract into a contraction-hierarchy-prepared GraphHopper routing
 * graph, ready to be copied into a region's {@code graphhopper/} directory and loaded on-device by
 * {@code GraphHopperRoutingEngine}.
 *
 * <p>The GraphHopper configuration here is deliberately identical to
 * {@code GraphHopperRoutingEngine.ensureLoaded()} in the Android app (same {@code Profile}/
 * {@code CHProfile} setup, same "car" profile name) - that method only ever calls {@code load()}
 * on a pre-built graph, never imports one, so this importer is the one place that configuration
 * actually needs to match exactly for the resulting graph to load correctly on-device. Verified
 * against the real, currently-bundled Bremen graph's own {@code properties} file (which records
 * {@code car_access}/{@code car_average_speed} encoded values and a {@code car} CH profile) before
 * writing this - no {@code setEncodedValuesString} call is needed since {@code setVehicle("car")}
 * registers those automatically at import time.
 */
public final class GraphHopperImporter {
    private GraphHopperImporter() {}

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: GraphHopperImporter <osm.pbf path> <output graph dir>");
            System.exit(1);
        }
        String osmPbfPath = args[0];
        String outputDir = args[1];

        System.out.println("Importing " + osmPbfPath + " -> " + outputDir);
        long start = System.currentTimeMillis();

        GraphHopper hopper = new GraphHopper();
        hopper.setOSMFile(osmPbfPath);
        hopper.setGraphHopperLocation(outputDir);
        hopper.setProfiles(new Profile("car").setVehicle("car").setWeighting("fastest"));
        hopper.getCHPreparationHandler().setCHProfiles(new CHProfile("car"));
        hopper.importOrLoad();

        long elapsedSeconds = (System.currentTimeMillis() - start) / 1000;
        System.out.println("Import complete in " + elapsedSeconds + "s: " + outputDir);
    }
}
