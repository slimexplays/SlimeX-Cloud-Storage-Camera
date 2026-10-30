package com.slimex.cloudcamera.core.constants

/** Static, non-sensitive application constants. No credentials are ever hardcoded. */
object AppConstants {

    const val APP_NAME = "SlimeX Cloud Camera"
    const val DEVELOPER_CREDIT = "Made By Slime Plays"
    const val PACKAGE_NAME = "com.slimex.cloudcamera"

    /** Default bucket / table names created by the SQL in `supabase/`. */
    const val DEFAULT_BUCKET = "slimex-media"
    const val MEDIA_TABLE = "media_assets"

    /** Where captures are written inside app-private internal storage. */
    const val MEDIA_ROOT_DIR = "media"
    const val PHOTOS_DIR = "photos"
    const val VIDEOS_DIR = "videos"
    const val THUMBS_DIR = "thumbs"

    /** Encrypted preferences file + datastore file names. */
    const val SECURE_PREFS_FILE = "slimex_secure_prefs"
    const val SETTINGS_DATASTORE_FILE = "slimex_settings"

    /** Signed URL lifetime used by the cloud gallery. */
    const val SIGNED_URL_EXPIRY_SECONDS = 3_600L
}
