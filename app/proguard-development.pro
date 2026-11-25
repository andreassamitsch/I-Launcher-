# First optimized development variant: preserve reflection-based data contracts,
# including unannotated Gson fields and externally addressed diagnostic classes.
# Consumer rules from Room, Retrofit, Gson, Media3 and Coil remain active.
-dontobfuscate
-keep,allowoptimization class com.andreassamitsch.ilauncher.data.** { *; }
-keep,allowoptimization class com.andreassamitsch.ilauncher.model.** { *; }
-keep,allowoptimization class com.andreassamitsch.ilauncher.diagnostics.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
