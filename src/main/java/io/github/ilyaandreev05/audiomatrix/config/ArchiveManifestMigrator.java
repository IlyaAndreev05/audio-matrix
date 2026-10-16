package io.github.ilyaandreev05.audiomatrix.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** Upgrades known Audio Matrix archive manifests to the current explicit metadata shape. */
final class ArchiveManifestMigrator {
    static final String FORMAT = "audio-matrix-archive";
    static final String APP = "audio-matrix";
    static final int CURRENT_VERSION = 3;

    private ArchiveManifestMigrator() {}

    static Map<String, Object> migrate(Map<String, Object> source, int configSchemaVersion)
            throws ConfigArchive.ArchiveException {
        if (!FORMAT.equals(source.get("format")))
            throw new ConfigArchive.ArchiveException("Archive manifest has an unsupported format");
        Integer version = integer(source.get("version"));
        if (version == null || version < 1)
            throw new ConfigArchive.ArchiveException("Archive manifest has an invalid version");
        if (version > CURRENT_VERSION)
            throw new ConfigArchive.ArchiveException("Archive was created by a newer Audio Matrix version");
        if (configSchemaVersion < 1)
            throw new ConfigArchive.ArchiveException("Archive config has an unsupported schema version");

        Map<String, Object> migrated = new LinkedHashMap<>(source);
        while (version < CURRENT_VERSION) {
            if (version == 1) {
                migrateV1ToV2(migrated, configSchemaVersion);
                version = 2;
            } else if (version == 2) {
                migrateV2ToV3(migrated, configSchemaVersion);
                version = 3;
            } else {
                throw new ConfigArchive.ArchiveException("No migration is available for archive version " + version);
            }
        }

        if (!APP.equals(migrated.get("app")))
            throw new ConfigArchive.ArchiveException("Archive manifest belongs to another application");
        Integer declaredSchema = integer(migrated.get("config_schema_version"));
        if (declaredSchema == null || declaredSchema != configSchemaVersion)
            throw new ConfigArchive.ArchiveException("Archive manifest config schema does not match its config document");
        if (!"full".equals(migrated.get("kind")))
            throw new ConfigArchive.ArchiveException("Archive manifest has an unsupported kind");
        migrated.put("version", CURRENT_VERSION);
        return migrated;
    }

    private static void migrateV2ToV3(Map<String,Object> manifest,int configSchemaVersion)throws ConfigArchive.ArchiveException{
        if(!APP.equals(manifest.get("app")))throw new ConfigArchive.ArchiveException("Archive manifest belongs to another application");
        Integer declared=integer(manifest.get("config_schema_version"));if(declared==null||declared!=configSchemaVersion)throw new ConfigArchive.ArchiveException("Archive manifest config schema does not match its config document");
        manifest.put("config_entry","config.yaml");manifest.put("version",3);
    }

    private static void migrateV1ToV2(Map<String, Object> manifest, int configSchemaVersion)
            throws ConfigArchive.ArchiveException {
        Object oldApp = manifest.get("app");
        if (oldApp != null && !APP.equals(oldApp))
            throw new ConfigArchive.ArchiveException("Archive manifest belongs to another application");
        Object oldSchema = manifest.get("config_schema_version");
        Integer declaredSchema = oldSchema == null ? null : integer(oldSchema);
        if (oldSchema != null && declaredSchema == null)
            throw new ConfigArchive.ArchiveException("Archive manifest has an invalid config schema version");
        if (declaredSchema != null && declaredSchema != configSchemaVersion)
            throw new ConfigArchive.ArchiveException("Archive manifest config schema does not match config.yaml");
        Object oldKind = manifest.get("kind");
        if (oldKind != null && !"full".equals(oldKind))
            throw new ConfigArchive.ArchiveException("Archive manifest has an unsupported kind");
        manifest.put("app", APP);
        manifest.put("config_schema_version", configSchemaVersion);
        manifest.put("kind", "full");
        manifest.put("version", 2);
    }

    private static Integer integer(Object value) {
        if (!(value instanceof Number number)) return null;
        double raw = number.doubleValue();
        int parsed = number.intValue();
        return Double.isFinite(raw) && raw == parsed ? parsed : null;
    }
}
