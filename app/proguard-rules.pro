# Project-specific R8 rules (release build: minification and resource shrinking are on).
# Room, Hilt, WorkManager, kotlinx.serialization and PdfBox-Android ship consumer rules.

# PdfBox-Android's JPEG 2000 filter refers to an optional decoder (jp2-android) that the app does
# not include. JPX images were already unsupported; without these lines R8 stops on the missing classes.
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder
