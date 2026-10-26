# R8 keep rules for release builds.
#
# None of our own code needs keeping: every JSON model and navigation route is @Serializable with
# its serializer resolved at compile time, and nothing is looked up by reflection. The libraries
# that do need rules (kotlinx.serialization, Ktor, OkHttp, coroutines, Navigation) ship them as
# consumer rules, which R8 picks up on its own. Check a release build on a device before adding
# a rule here.

# Keep line numbers so crash reports in Play Console point at real lines once deobfuscated with
# the mapping file that ships inside the app bundle. The file name itself is hidden.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
