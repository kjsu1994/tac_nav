#include <jni.h>
#include <math.h>
void geomag(int *maxdeg);
void geomg1(double alt, double lat, double lon, double year, double *dec, double *dip, double *total, double *gv);
int tac_wmm_failed(void);
JNIEXPORT jdouble JNICALL Java_com_tacnav_MagneticModel_compute(JNIEnv *env, jobject obj, jdouble lat, jdouble lon, jdouble altitude, jdouble year) {
    static int initialized = 0;
    if (!isfinite(lat) || !isfinite(lon) || !isfinite(altitude) || !isfinite(year) || lat < -90 || lat > 90 || lon < -180 || lon > 180 || altitude < -1 || altitude > 850 || year < 2025 || year >= 2030) return NAN;
    if (!initialized) { int degree = 12; geomag(&degree); initialized = 1; }
    if (tac_wmm_failed()) return NAN;
    double dec = NAN, dip, total, gv;
    geomg1(altitude, lat, lon, year, &dec, &dip, &total, &gv);
    return dec;
}
