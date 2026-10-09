# Project-specific R8 rules (release build: minification and resource shrinking are on).
# Room, Hilt, WorkManager, kotlinx.serialization and PdfBox-Android ship consumer rules.

# PdfBox-Android's JPEG 2000 filter refers to an optional decoder (jp2-android) that the app does
# not include. JPX images were already unsupported; without these lines R8 stops on the missing classes.
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder

# ML Kit (Document Scanner, plan 9) finds its components through Firebase's ComponentDiscovery, which
# instantiates each ComponentRegistrar named in the manifest by reflection. firebase-components' own rule,
# `-keep class * implements ComponentRegistrar`, has no member list, and R8 full mode (AGP default) does not
# keep the default constructor for such rules: CommonComponentRegistrar.<init>() was removed, the registrar
# was skipped, SharedPrefManager was missing and GmsDocumentScanning.getClient() threw a
# NullPointerException in release builds only.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
