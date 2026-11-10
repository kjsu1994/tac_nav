# Third-party components

- MapLibre Native Android — BSD 2-Clause; https://github.com/maplibre/maplibre-native/blob/main/LICENSE.md
- NGA MGRS Java and grid dependencies — MIT; https://github.com/ngageoint/mgrs-java and https://github.com/ngageoint/grid-java
- GeographicLib Java — MIT; https://github.com/geographiclib/geographiclib-java
- AndroidX / Jetpack / Room / DataStore / Compose — Apache-2.0; https://android.googlesource.com/platform/frameworks/support/
- Kotlin — Apache-2.0; https://github.com/JetBrains/kotlin
- Gradle wrapper — Apache-2.0; https://github.com/gradle/gradle

## NOAA World Magnetic Model

`app/src/main/cpp/geomag.c` derives from the NOAA/NGA WMM Legacy C implementation at https://www.ngdc.noaa.gov/geomag/WMM/data/WMM2020/WMM2020LegacyC.zip. Original author/contact notices are preserved. The console driver was removed, the official WMM2025 coefficient data was embedded, and process-exiting errors return a failure flag. Numerical algorithm retained.

WMM2025 coefficients: https://www.ncei.noaa.gov/sites/default/files/2024-12/WMM2025COF.zip

Reference tests: https://www.ncei.noaa.gov/sites/default/files/2025-02/WMM2025_TEST_VALUES.txt

NOAA/NGA scientific data and implementation are provided without warranty; use does not imply government endorsement. Refer to https://www.ncei.noaa.gov/products/world-magnetic-model for model limitations and updates. No satellite imagery is bundled. Every imported map must carry its actual source attribution and usage terms.

## Whole Korean peninsula imagery

ESA WorldCover 2021 annual Sentinel-2 RGBNIR composites, https://esa-worldcover.org/en/data-access . Source COGs are publicly hosted at https://esa-worldcover-s2.s3.eu-central-1.amazonaws.com/ . License: CC BY 4.0, https://creativecommons.org/licenses/by/4.0/ . Derived RGB imagery is stretched, reprojected and JPEG-compressed; it is distributed separately from the APK.

© ESA WorldCover project 2021 / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium.

The Seoul regression fixture separately uses Copernicus Sentinel-2 / Element 84 Earth Search imagery captured on 2025-11-12. Contains modified Copernicus Sentinel data 2025. See its JSON provenance.


## Lightweight 2D basemap and fonts

Protomaps v4.15.2 basemap, OSM replication timestamp 2026-11-09T04:00:00Z.
© OpenStreetMap contributors; Natural Earth. The map is an ODbL produced work. Attribution is available through the map information button and Settings → map source/licenses.
https://www.openstreetmap.org/copyright
https://docs.protomaps.com/basemaps/downloads

Noto SDF glyphs are bundled from MapLibre demotiles, pinned to commit 601ae60796ceceda2cbd2ed3d2ea92d17a84be4b:
https://github.com/maplibre/demotiles/tree/601ae60796ceceda2cbd2ed3d2ea92d17a84be4b/font/Noto%20Sans%20Regular
Noto fonts use SIL OFL 1.1; complete licenses are bundled at assets/fonts/OFL.txt and assets/fonts/NotoCJK-OFL.txt and in the app notices. Hashes and source revision are recorded at assets/fonts/sources.json. Android local ideograph generation is disabled; the APK includes Hangul, CJK, Kana, Latin and Cyrillic glyph data and does not fetch fonts at runtime.
