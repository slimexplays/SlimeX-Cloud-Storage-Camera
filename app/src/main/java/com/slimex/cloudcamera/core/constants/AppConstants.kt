package com.slimex.cloudcamera.core.constants

/** Static, non-sensitive application constants. No credentials are ever hardcoded. */
object AppConstants {

    const val APP_NAME = "SlimeX Cloud Camera"
    const val DEVELOPER_CREDIT = "Made By Slime Plays"
    const val PACKAGE_NAME = "com.slimex.cloudcamera"

    /** Default bucket / table names created by the SQL in `supabase/`. */
    const val DEFAULT_BUCKET = "slimex-private-media"
    const val MEDIA_TABLE = "media_files"
    const val PROFILES_TABLE = "profiles"
    const val UPLOAD_JOBS_TABLE = "upload_jobs"

    /** Object path layout required by the spec: {user_id}/{year}/{month}/{unique_file_id}. */
    const val OBJECT_EXTENSION = "enc"

    /** App-private staging area (internal storage, NOT the media store / DCIM). */
    const val STAGING_DIR = "pending"
    /** Part 1 wrote captures here; migrated into staging on first run of Part 2. */
    const val LEGACY_MEDIA_DIR = "media"

    /** Evictable private caches (cacheDir). */
    const val THUMBS_DIR = "thumbs"
    const val VIEWER_DIR = "viewer"
    const val SHARE_DIR = "share"
    const val WORK_DIR = "work"

    /** Encrypted preferences + DataStore file names. */
    const val SECURE_PREFS_FILE = "slimex_secure_prefs"
    const val SETTINGS_DATASTORE_FILE = "slimex_settings"

    /** Cache housekeeping. */
    const val SHARE_CACHE_TTL_MINUTES = 15L
    const val THUMBNAIL_MAX_PX = 512
    const val VIEWER_CACHE_MAX_BYTES = 220L * 1024 * 1024
}
