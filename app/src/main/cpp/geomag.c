/* PROGRAM MAGPOINT (GEOMAG DRIVER) */
/************************************************************************
     Contact Information

     Software and Model Support
     	National Geophysical Data Center
     	NOAA EGC/2
     	325 Broadway
     	Boulder, CO 80303 USA
		Attn: Manoj Nair or Stefan Maus
		Phone:  (303) 497-4642 or -6522
		Email:  Manoj.C.Nair@Noaa.gov or Stefan.Maus@noaa.gov
		Web: http://www.ngdc.noaa.gov/geomag/WMM/

	 Sponsoring Government Agency
	   National Geospatial-Intelligence Agency
    	   PRG / CSAT, M.S. L-41
    	   3838 Vogel Road
    	   Arnold, MO 63010
    	   Attn: Craig Rollins
    	   Phone:  (314) 263-4186
    	   Email:  Craig.M.Rollins@Nga.Mil

      Original Program By:
        Dr. John Quinn
        FLEET PRODUCTS DIVISION, CODE N342
        NAVAL OCEANOGRAPHIC OFFICE (NAVOCEANO)
        STENNIS SPACE CENTER (SSC), MS 39522-5001

		3/25/05 Version 2.0 Stefan Maus corrected 2 bugs:
         - use %c instead of %s for character read
		 - help text: positive inclination is downward
		1/29/2010 Version 3.0 Manoj Nair
		Converted floating variables from single precision to double
		Changed : height above AMSL (WGS84) to Height above WGS84 Ellipsoid
		Removed the NaN forcing at the geographic poles
		A new function "my_isnan" for improved portablility

*/

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>


/* Tac_Nav: console driver removed; embedded WMM2025 coefficients; failures return. */
static int wmm_failed = 0;
static const char wmm_coefficients[] =
"    2025.0            WMM-2025     11/13/2024\n"
"  1  0  -29351.8       0.0       12.0        0.0\n"
"  1  1   -1410.8    4545.4        9.7      -21.5\n"
"  2  0   -2556.6       0.0      -11.6        0.0\n"
"  2  1    2951.1   -3133.6       -5.2      -27.7\n"
"  2  2    1649.3    -815.1       -8.0      -12.1\n"
"  3  0    1361.0       0.0       -1.3        0.0\n"
"  3  1   -2404.1     -56.6       -4.2        4.0\n"
"  3  2    1243.8     237.5        0.4       -0.3\n"
"  3  3     453.6    -549.5      -15.6       -4.1\n"
"  4  0     895.0       0.0       -1.6        0.0\n"
"  4  1     799.5     278.6       -2.4       -1.1\n"
"  4  2      55.7    -133.9       -6.0        4.1\n"
"  4  3    -281.1     212.0        5.6        1.6\n"
"  4  4      12.1    -375.6       -7.0       -4.4\n"
"  5  0    -233.2       0.0        0.6        0.0\n"
"  5  1     368.9      45.4        1.4       -0.5\n"
"  5  2     187.2     220.2        0.0        2.2\n"
"  5  3    -138.7    -122.9        0.6        0.4\n"
"  5  4    -142.0      43.0        2.2        1.7\n"
"  5  5      20.9     106.1        0.9        1.9\n"
"  6  0      64.4       0.0       -0.2        0.0\n"
"  6  1      63.8     -18.4       -0.4        0.3\n"
"  6  2      76.9      16.8        0.9       -1.6\n"
"  6  3    -115.7      48.8        1.2       -0.4\n"
"  6  4     -40.9     -59.8       -0.9        0.9\n"
"  6  5      14.9      10.9        0.3        0.7\n"
"  6  6     -60.7      72.7        0.9        0.9\n"
"  7  0      79.5       0.0       -0.0        0.0\n"
"  7  1     -77.0     -48.9       -0.1        0.6\n"
"  7  2      -8.8     -14.4       -0.1        0.5\n"
"  7  3      59.3      -1.0        0.5       -0.8\n"
"  7  4      15.8      23.4       -0.1        0.0\n"
"  7  5       2.5      -7.4       -0.8       -1.0\n"
"  7  6     -11.1     -25.1       -0.8        0.6\n"
"  7  7      14.2      -2.3        0.8       -0.2\n"
"  8  0      23.2       0.0       -0.1        0.0\n"
"  8  1      10.8       7.1        0.2       -0.2\n"
"  8  2     -17.5     -12.6        0.0        0.5\n"
"  8  3       2.0      11.4        0.5       -0.4\n"
"  8  4     -21.7      -9.7       -0.1        0.4\n"
"  8  5      16.9      12.7        0.3       -0.5\n"
"  8  6      15.0       0.7        0.2       -0.6\n"
"  8  7     -16.8      -5.2       -0.0        0.3\n"
"  8  8       0.9       3.9        0.2        0.2\n"
"  9  0       4.6       0.0       -0.0        0.0\n"
"  9  1       7.8     -24.8       -0.1       -0.3\n"
"  9  2       3.0      12.2        0.1        0.3\n"
"  9  3      -0.2       8.3        0.3       -0.3\n"
"  9  4      -2.5      -3.3       -0.3        0.3\n"
"  9  5     -13.1      -5.2        0.0        0.2\n"
"  9  6       2.4       7.2        0.3       -0.1\n"
"  9  7       8.6      -0.6       -0.1       -0.2\n"
"  9  8      -8.7       0.8        0.1        0.4\n"
"  9  9     -12.9      10.0       -0.1        0.1\n"
" 10  0      -1.3       0.0        0.1        0.0\n"
" 10  1      -6.4       3.3        0.0        0.0\n"
" 10  2       0.2       0.0        0.1       -0.0\n"
" 10  3       2.0       2.4        0.1       -0.2\n"
" 10  4      -1.0       5.3       -0.0        0.1\n"
" 10  5      -0.6      -9.1       -0.3       -0.1\n"
" 10  6      -0.9       0.4        0.0        0.1\n"
" 10  7       1.5      -4.2       -0.1        0.0\n"
" 10  8       0.9      -3.8       -0.1       -0.1\n"
" 10  9      -2.7       0.9       -0.0        0.2\n"
" 10 10      -3.9      -9.1       -0.0       -0.0\n"
" 11  0       2.9       0.0        0.0        0.0\n"
" 11  1      -1.5       0.0       -0.0       -0.0\n"
" 11  2      -2.5       2.9        0.0        0.1\n"
" 11  3       2.4      -0.6        0.0       -0.0\n"
" 11  4      -0.6       0.2        0.0        0.1\n"
" 11  5      -0.1       0.5       -0.1       -0.0\n"
" 11  6      -0.6      -0.3        0.0       -0.0\n"
" 11  7      -0.1      -1.2       -0.0        0.1\n"
" 11  8       1.1      -1.7       -0.1       -0.0\n"
" 11  9      -1.0      -2.9       -0.1        0.0\n"
" 11 10      -0.2      -1.8       -0.1        0.0\n"
" 11 11       2.6      -2.3       -0.1        0.0\n"
" 12  0      -2.0       0.0        0.0        0.0\n"
" 12  1      -0.2      -1.3        0.0       -0.0\n"
" 12  2       0.3       0.7       -0.0        0.0\n"
" 12  3       1.2       1.0       -0.0       -0.1\n"
" 12  4      -1.3      -1.4       -0.0        0.1\n"
" 12  5       0.6      -0.0       -0.0       -0.0\n"
" 12  6       0.6       0.6        0.1       -0.0\n"
" 12  7       0.5      -0.1       -0.0       -0.0\n"
" 12  8      -0.1       0.8        0.0        0.0\n"
" 12  9      -0.4       0.1        0.0       -0.0\n"
" 12 10      -0.2      -1.0       -0.1       -0.0\n"
" 12 11      -1.3       0.1       -0.0        0.0\n"
" 12 12      -0.7       0.2       -0.1       -0.1\n"
"999999999999999999999999999999999999999999999999\n"
"999999999999999999999999999999999999999999999999\n";
static void E0000(int IENTRY, int *maxdeg, double alt, double glat, double glon, double time, double *dec, double *dip, double *ti, double *gv)
{
  static int maxord,i,icomp,n,m,j,D1,D2,D3,D4;
  static double c[13][13],cd[13][13],tc[13][13],dp[13][13],snorm[169],
    sp[13],cp[13],fn[13],fm[13],pp[13],k[13][13],pi,dtr,a,b,re,
    a2,b2,c2,a4,b4,c4,epoch,gnm,hnm,dgnm,dhnm,flnmj,otime,oalt,
    olat,olon,dt,rlon,rlat,srlon,srlat,crlon,crlat,srlat2,
    crlat2,q,q1,q2,ct,st,r2,r,d,ca,sa,aor,ar,br,bt,bp,bpp,
    par,temp1,temp2,parp,bx,by,bz,bh;
  static char model[20], c_str[81], c_new[5];
  static double *p = snorm;
  char answer;
  
  FILE *wmmdat;

  switch(IENTRY){case 0: goto GEOMAG; case 1: goto GEOMG1;}
  
 GEOMAG:
  wmmdat = fmemopen((void *)wmm_coefficients, sizeof(wmm_coefficients) - 1, "r");
  if (wmmdat == NULL) 
    {
      fprintf(stderr, "Error opening model file WMM.COF\n");
      wmm_failed = 1; return;
    }
  
/* INITIALIZE CONSTANTS */
  maxord = *maxdeg;
  sp[0] = 0.0;
  cp[0] = *p = pp[0] = 1.0;
  dp[0][0] = 0.0;
  a = 6378.137;
  b = 6356.7523142;
  re = 6371.2;
  a2 = a*a;
  b2 = b*b;
  c2 = a2-b2;
  a4 = a2*a2;
  b4 = b2*b2;
  c4 = a4 - b4;
  
/* READ WORLD MAGNETIC MODEL SPHERICAL HARMONIC COEFFICIENTS */
  c[0][0] = 0.0;
  cd[0][0] = 0.0;
  
  fgets(c_str, 80, wmmdat);
  if (sscanf(c_str,"%lf%s",&epoch,model) < 2) 
   {
       fprintf(stderr, "Invalid header in model file WMM.COF\n");
       wmm_failed = 1; return;
   }

 S3:
  if (fgets(c_str, 80, wmmdat) == NULL) goto S4;

/* CHECK FOR LAST LINE IN FILE */
  for (i=0; i<4 && (c_str[i] != '\0'); i++)
    {
      c_new[i] = c_str[i];
      c_new[i+1] = '\0';
    }
  icomp = strcmp("9999", c_new);
  if (icomp == 0) goto S4;
/* END OF FILE NOT ENCOUNTERED, GET VALUES */
  sscanf(c_str,"%d%d%lf%lf%lf%lf",&n,&m,&gnm,&hnm,&dgnm,&dhnm);

  if (n > maxord) goto S4;
  if (m > n || m < 0.0) 
    {
      fprintf(stderr, "Corrupt record in model file WMM.COF\n");
      wmm_failed = 1; return;
    }

  if (m <= n)
    {
      c[m][n] = gnm;
      cd[m][n] = dgnm;
      if (m != 0)
        {
          c[n][m-1] = hnm;
          cd[n][m-1] = dhnm;
        }
    }
  goto S3;

/* CONVERT SCHMIDT NORMALIZED GAUSS COEFFICIENTS TO UNNORMALIZED */
 S4:
  *snorm = 1.0;
  fm[0] = 0.0;
  for (n=1; n<=maxord; n++)
    {
      *(snorm+n) = *(snorm+n-1)*(double)(2*n-1)/(double)n;
      j = 2;
      for (m=0,D1=1,D2=(n-m+D1)/D1; D2>0; D2--,m+=D1)
        {
          k[m][n] = (double)(((n-1)*(n-1))-(m*m))/(double)((2*n-1)*(2*n-3));
          if (m > 0)
            {
              flnmj = (double)((n-m+1)*j)/(double)(n+m);
              *(snorm+n+m*13) = *(snorm+n+(m-1)*13)*sqrt(flnmj);
              j = 1;
              c[n][m-1] = *(snorm+n+m*13)*c[n][m-1];
              cd[n][m-1] = *(snorm+n+m*13)*cd[n][m-1];
            }
          c[m][n] = *(snorm+n+m*13)*c[m][n];
          cd[m][n] = *(snorm+n+m*13)*cd[m][n];
        }
      fn[n] = (double)(n+1);
      fm[n] = (double)n;
    }
  k[1][1] = 0.0;
  
  otime = oalt = olat = olon = -1000.0;
  fclose(wmmdat);
  return;
  
/*************************************************************************/

 GEOMG1:
  
  dt = time - epoch;
  if (otime < 0.0 && (dt < 0.0 || dt > 5.0))
    {      
      printf("\n\n WARNING - TIME EXTENDS BEYOND MODEL 5-YEAR LIFE SPAN");
      printf("\n CONTACT NGDC FOR PRODUCT UPDATES:");
      printf("\n         National Geophysical Data Center");
      printf("\n         NOAA EGC/2");
      printf("\n         325 Broadway");
      printf("\n         Boulder, CO 80303 USA");
	  printf("\n         Attn: Manoj Nair or Stefan Maus");
	  printf("\n         Phone:  (303) 497-4642 or -6522");
	  printf("\n         Email:  Manoj.C.Nair@Noaa.Gov");
      printf("\n         or");
      printf("\n         Stefan.Maus@noaa.gov");
	  printf("\n         Web: http://www.ngdc.noaa.gov/geomag/WMM/");
      printf("\n\n EPOCH  = %.3lf",epoch);
      printf("\n TIME   = %.3lf",time);
      printf("\n Do you wish to continue? (y or n) ");
      scanf("%c%*[^\n]",&answer);
      getchar();
      if ((answer == 'n') || (answer == 'N'))
        {
          printf("\n Do you wish to enter more point data? (y or n) ");
          scanf("%c%*[^\n]",&answer);
          getchar();
          if ((answer == 'y')||(answer == 'Y')) goto GEOMG1;
          else exit (0);
        }
    }

  pi = 3.14159265359;
  dtr = pi/180.0;
  rlon = glon*dtr;
  rlat = glat*dtr;
  srlon = sin(rlon);
  srlat = sin(rlat);
  crlon = cos(rlon);
  crlat = cos(rlat);
  srlat2 = srlat*srlat;
  crlat2 = crlat*crlat;
  sp[1] = srlon;
  cp[1] = crlon;

/* CONVERT FROM GEODETIC COORDS. TO SPHERICAL COORDS. */
  if (alt != oalt || glat != olat)
    {
      q = sqrt(a2-c2*srlat2);
      q1 = alt*q;
      q2 = ((q1+a2)/(q1+b2))*((q1+a2)/(q1+b2));
      ct = srlat/sqrt(q2*crlat2+srlat2);
      st = sqrt(1.0-(ct*ct));
      r2 = (alt*alt)+2.0*q1+(a4-c4*srlat2)/(q*q);
      r = sqrt(r2);
      d = sqrt(a2*crlat2+b2*srlat2);
      ca = (alt+d)/r;
      sa = c2*crlat*srlat/(r*d);
    }
  if (glon != olon)
    {
      for (m=2; m<=maxord; m++)
        {
          sp[m] = sp[1]*cp[m-1]+cp[1]*sp[m-1];
          cp[m] = cp[1]*cp[m-1]-sp[1]*sp[m-1];
        }
    }
  aor = re/r;
  ar = aor*aor;
  br = bt = bp = bpp = 0.0;
  for (n=1; n<=maxord; n++)
    {
      ar = ar*aor;
      for (m=0,D3=1,D4=(n+m+D3)/D3; D4>0; D4--,m+=D3)
        {
/*
   COMPUTE UNNORMALIZED ASSOCIATED LEGENDRE POLYNOMIALS
   AND DERIVATIVES VIA RECURSION RELATIONS
*/
          if (alt != oalt || glat != olat)
            {
              if (n == m)
                {
                  *(p+n+m*13) = st**(p+n-1+(m-1)*13);
                  dp[m][n] = st*dp[m-1][n-1]+ct**(p+n-1+(m-1)*13);
                  goto S50;
                }
              if (n == 1 && m == 0)
                {
                  *(p+n+m*13) = ct**(p+n-1+m*13);
                  dp[m][n] = ct*dp[m][n-1]-st**(p+n-1+m*13);
                  goto S50;
                }
              if (n > 1 && n != m)
                {
                  if (m > n-2) *(p+n-2+m*13) = 0.0;
                  if (m > n-2) dp[m][n-2] = 0.0;
                  *(p+n+m*13) = ct**(p+n-1+m*13)-k[m][n]**(p+n-2+m*13);
                  dp[m][n] = ct*dp[m][n-1] - st**(p+n-1+m*13)-k[m][n]*dp[m][n-2];
                }
            }
        S50:
/*
    TIME ADJUST THE GAUSS COEFFICIENTS
*/
          if (time != otime)
            {
              tc[m][n] = c[m][n]+dt*cd[m][n];
              if (m != 0) tc[n][m-1] = c[n][m-1]+dt*cd[n][m-1];
            }
/*
    ACCUMULATE TERMS OF THE SPHERICAL HARMONIC EXPANSIONS
*/
          par = ar**(p+n+m*13);
          if (m == 0)
            {
              temp1 = tc[m][n]*cp[m];
              temp2 = tc[m][n]*sp[m];
            }
          else
            {
              temp1 = tc[m][n]*cp[m]+tc[n][m-1]*sp[m];
              temp2 = tc[m][n]*sp[m]-tc[n][m-1]*cp[m];
            }
          bt = bt-ar*temp1*dp[m][n];
          bp += (fm[m]*temp2*par);
          br += (fn[n]*temp1*par);
/*
    SPECIAL CASE:  NORTH/SOUTH GEOGRAPHIC POLES
*/
          if (st == 0.0 && m == 1)
            {
              if (n == 1) pp[n] = pp[n-1];
              else pp[n] = ct*pp[n-1]-k[m][n]*pp[n-2];
              parp = ar*pp[n];
              bpp += (fm[m]*temp2*parp);
            }
        }
    }
  if (st == 0.0) bp = bpp;
  else bp /= st;
/*
    ROTATE MAGNETIC VECTOR COMPONENTS FROM SPHERICAL TO
    GEODETIC COORDINATES
*/
  bx = -bt*ca-br*sa;
  by = bp;
  bz = bt*sa-br*ca;
/*
    COMPUTE DECLINATION (DEC), INCLINATION (DIP) AND
    TOTAL INTENSITY (TI)
*/
  bh = sqrt((bx*bx)+(by*by));
  *ti = sqrt((bh*bh)+(bz*bz));
  *dec = atan2(by,bx)/dtr;
  *dip = atan2(bz,bh)/dtr;
/*
    COMPUTE MAGNETIC GRID VARIATION IF THE CURRENT
    GEODETIC POSITION IS IN THE ARCTIC OR ANTARCTIC
    (I.E. GLAT > +55 DEGREES OR GLAT < -55 DEGREES)

    OTHERWISE, SET MAGNETIC GRID VARIATION TO -999.0
*/
  *gv = -999.0;
  if (fabs(glat) >= 55.)
    {
      if (glat > 0.0 && glon >= 0.0) *gv = *dec-glon;
      if (glat > 0.0 && glon < 0.0) *gv = *dec+fabs(glon);
      if (glat < 0.0 && glon >= 0.0) *gv = *dec+glon;
      if (glat < 0.0 && glon < 0.0) *gv = *dec-fabs(glon);
      if (*gv > +180.0) *gv -= 360.0;
      if (*gv < -180.0) *gv += 360.0;
    }
  otime = time;
  oalt = alt;
  olat = glat;
  olon = glon;
  return;
}

/*************************************************************************/

void geomag(int *maxdeg)
{
  E0000(0,maxdeg,0.0,0.0,0.0,0.0,NULL,NULL,NULL,NULL);
}

/*************************************************************************/

void geomg1(double alt, double glat, double glon, double time, double *dec, double *dip, double *ti, double *gv)
{
  E0000(1,NULL,alt,glat,glon,time,dec,dip,ti,gv);
}

/*************************************************************************/


int tac_wmm_failed(void) { return wmm_failed; }
