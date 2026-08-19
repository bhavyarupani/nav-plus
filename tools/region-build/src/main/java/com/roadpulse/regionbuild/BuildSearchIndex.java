package com.roadpulse.regionbuild;

import com.graphhopper.reader.ReaderElement;
import com.graphhopper.reader.ReaderNode;
import com.graphhopper.reader.osm.OSMInputFile;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Locale;
import java.util.Map;

/**
 * Builds a region's offline search index: a SQLite FTS4 table of named OSM nodes (POIs, places,
 * and house-numbered addresses), matching exactly the schema {@code OfflineSearchEngine.search()}
 * already queries in the Android app - {@code places(name, subtitle, lat, lon)}.
 *
 * <p>Uses GraphHopper's own {@link OSMInputFile} (built on its {@code reader.osm.pbf.Sink}
 * machinery) to stream every element of a Geofabrik .osm.pbf extract - already a transitive
 * dependency of {@code graphhopper-core}, so no new PBF-parsing library is needed. Only nodes are
 * indexed (not ways/relations), matching the original Bremen index's own node-based scope
 * (documented limitation: a bare street name with no house number won't resolve - that needs
 * way-centroid geometry this doesn't build).
 */
public final class BuildSearchIndex {
    private BuildSearchIndex() {}

    private static final int BATCH_SIZE = 2_000;
    private static final int LOG_INTERVAL = 500_000;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: BuildSearchIndex <osm.pbf path> <output search.db path>");
            System.exit(1);
        }
        String osmPbfPath = args[0];
        String outputPath = args[1];

        File outputFile = new File(outputPath);
        if (outputFile.exists() && !outputFile.delete()) {
            throw new IllegalStateException("Could not remove existing file at " + outputPath);
        }
        File parent = outputFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        long start = System.currentTimeMillis();
        long scanned = 0;
        long indexed = 0;

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + outputFile.getAbsolutePath())) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE VIRTUAL TABLE places USING fts4(name, subtitle, lat, lon)");
            }
            try (
                PreparedStatement insert =
                    connection.prepareStatement("INSERT INTO places (name, subtitle, lat, lon) VALUES (?, ?, ?, ?)");
                OSMInputFile input = new OSMInputFile(new File(osmPbfPath)).setWorkerThreads(2).open()
            ) {
                int pending = 0;
                ReaderElement element;
                while ((element = input.getNext()) != null) {
                    scanned++;
                    if (element instanceof ReaderNode node) {
                        String name = trimmedTag(node, "name");
                        if (name != null) {
                            String subtitle = subtitleFor(node);
                            if (subtitle != null) {
                                insert.setString(1, name);
                                insert.setString(2, subtitle);
                                insert.setDouble(3, node.getLat());
                                insert.setDouble(4, node.getLon());
                                insert.addBatch();
                                pending++;
                                indexed++;
                                if (pending >= BATCH_SIZE) {
                                    insert.executeBatch();
                                    connection.commit();
                                    pending = 0;
                                }
                            }
                        }
                    }
                    if (scanned % LOG_INTERVAL == 0) {
                        System.out.printf(Locale.ROOT, "Scanned %,d elements, indexed %,d places so far%n", scanned, indexed);
                    }
                }
                if (pending > 0) {
                    insert.executeBatch();
                    connection.commit();
                }
            }
            // VACUUM cannot run inside a transaction, and setAutoCommit(false) leaves one open
            // (JDBC auto-begins a new transaction right after each commit()).
            connection.setAutoCommit(true);
            try (var statement = connection.createStatement()) {
                // FTS4 has no separate index step, but VACUUM shrinks the file after the delete
                // above created it fresh - harmless either way, cheap relative to the scan itself.
                statement.execute("VACUUM");
            }
        }

        long elapsedSeconds = (System.currentTimeMillis() - start) / 1000;
        System.out.printf(
            Locale.ROOT,
            "Done in %ds: scanned %,d elements, indexed %,d places -> %s (%,d bytes)%n",
            elapsedSeconds, scanned, indexed, outputPath, outputFile.length());
    }

    private static String trimmedTag(ReaderElement element, String key) {
        String value = element.getTag(key);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Only a node with a recognisable place/POI category or a full house-numbered address is
     * indexed - a name alone (present on plenty of unrelated nodes, e.g. traffic sign refs) isn't
     * enough. Mirrors the category set implied by {@code OfflineSearchEngine}'s own doc comment
     * ("POIs, place names, and address points").
     */
    private static String subtitleFor(ReaderElement node) {
        Map<String, Object> tags = node.getTags();
        String housenumber = trimmedTag(node, "addr:housenumber");
        String street = trimmedTag(node, "addr:street");
        if (housenumber != null && street != null) {
            return street + " " + housenumber;
        }
        for (String key : new String[] {"shop", "amenity", "tourism", "leisure", "office", "craft", "place"}) {
            String value = trimmedTag(node, key);
            if (value != null) {
                return capitalize(key) + ": " + value.replace('_', ' ');
            }
        }
        return null;
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
