package org.matthiaszimmermann.location.egm96;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.StringTokenizer;
import java.util.zip.GZIPInputStream;

import org.matthiaszimmermann.location.Location;

/**
 * offline <a href="https://en.wikipedia.org/wiki/Geoid">geoid</a> implementation based on the data provided 
 * by the <a href="http://earth-info.nga.mil/GandG/wgs84/gravitymod/egm96/intpt.html">online caluclator</a>.
 * 
 * @author matthiaszimmermann
 *
 */
public class Geoid {

	public static final double OFFSET_INVALID = -9999.99;
	public static final double OFFSET_MISSING = 9999.99;
	
	public static final String FILE_GEOID_GZ = "/EGM96.complete.txt.gz";

	private static final int ROWS = 719;  // (89.75 + 89.75)/0.25 + 1 = 719
	private static final int COLS = 1440; // 359.75/0.25 + 1 = 1440

	public static final double LATITUDE_MAX = 90.0; 
	public static final double LATITUDE_MAX_GRID = 89.74; 
	public static final double LATITUDE_ROW_FIRST = 89.50; 
	public static final double LATITUDE_ROW_LAST = -89.50; 
	public static final double LATITUDE_MIN_GRID = -89.74; 
	public static final double LATITUDE_MIN = -90.0; 
	public static final double LATITUDE_STEP = 0.25; 

	public static final double LONGITIDE_MIN = 0.0; 
	public static final double LONGITIDE_MIN_GRID = 0.0; 
	public static final double LONGITIDE_MAX_GRID = 359.75; 
	public static final double LONGITIDE_MAX = 360.0; 
	public static final double LONGITIDE_STEP = 0.25; 

	public static final String INVALID_OFFSET = "-9999.99";
	public static final String COMMENT_PREFIX = "//";

	private static double [][] offset = new double[ROWS][COLS];
	private static double offset_north_pole = 0;
	private static double offset_south_pole = 0;
	private static boolean s_model_ok = false;

	public static boolean init() {
		if(s_model_ok) {
			return true;
		}

		try {
			InputStream is = Geoid.class.getResourceAsStream(FILE_GEOID_GZ);
			GZIPInputStream gis = new GZIPInputStream(is);
			InputStreamReader isr = new InputStreamReader(gis);
			BufferedReader br = new BufferedReader(isr);

			s_model_ok = readGeoidOffsets(br);
		} 
		catch (Exception e) {
			s_model_ok = false;
		}

		return s_model_ok;
	}
	
	public static double getOffset(Location location) {
		double lat = location.getLatitude();
		double lng = location.getLongitude();
		
		// special case for exact grid positions
		if(latIsGridPoint(lat) && lngIsGridPoint(lng)) {
			return getGridOffset(lat, lng);
		}
		
		// get four grid locations surrounding the target location
		// used for bilinear interpolation
		Location q11 = getGridFloorLocation(lat, lng);
		Location q12 = getUpperLocation(q11);
		Location q21 = getRightLocation(q11);
		Location q22 = getUpperLocation(q21);
		
		System.out.println("ul=" + q12 + ", ur=" + q22 + ", ll=" + q11 + ", lr=" + q21);
		
		return bilinearInterpolation(location, q11, q12, q21, q22);
	}
	
	/**
	 * bilinearInterpolation according to description on wikipedia
	 * {@link https://en.wikipedia.org/wiki/Bilinear_interpolation}
	 * @return
	 */
	static double bilinearInterpolation(Location target, Location q11, Location q12, Location q21, Location q22) {
		double fq11 = getGridOffset(q11); // lower left
		double fq12 = getGridOffset(q12); // upper left
		double fq21 = getGridOffset(q21); // lower right
		double fq22 = getGridOffset(q22); // upper right
		
		double x1 = q11.getLongitude();
		double x2 = q22.getLongitude();
		double y1 = q22.getLatitude();
		double y2 = q11.getLatitude();
		
		// special case for latitude moving from 359.75 -> 0
		if(x1 == 359.75 && x2 == 0.0) {
			x2 = 360.0;
		}
		
		double x = target.getLongitude();
		double y = target.getLatitude();
		
		double f11 = fq11 * (x2 - x) * (y2 - y);
		double f12 = fq12 * (x2 - x) * (y - y1);
		double f21 = fq21 * (x - x1) * (y2 - y);
		double f22 = fq22 * (x - x1) * (y - y1);
		
		return (f11 + f12 + f21 + f22) / ((x2 - x1) * (y2 - y1));
	}
	
	static Location getUpperLocation(Location location) {
		double lat = location.getLatitude();
		double lng = location.getLongitude();
		
		if(lat == LATITUDE_MAX_GRID) {
			lat = LATITUDE_MAX;
		}
		else if(lat == LATITUDE_ROW_FIRST) {
			lat = LATITUDE_MAX_GRID;
		}
		else if(lat == LATITUDE_MIN) {
			lat = LATITUDE_MIN_GRID;
		}
		else if(lat == LATITUDE_MIN_GRID) {
			lat = LATITUDE_ROW_LAST;
		}
		else {
			lat += LATITUDE_STEP;
		}
		
		return new Location(lat, lng);
	}
	
	static Location getRightLocation(Location location) {
		double lat = location.getLatitude();
		double lng = location.getLongitude();
		
		return new Location(lat, lng + LATITUDE_STEP);
	}
	
	static Location getGridFloorLocation(double lat, double lng) {
		Location floor = (new Location(lat, lng)).floor(LATITUDE_STEP);
		double latFloor = floor.getLatitude();
		
		if(lat >= LATITUDE_MAX_GRID && lat < LATITUDE_MAX) { 
			latFloor = LATITUDE_MAX_GRID; 
		}
		else if(lat < LATITUDE_MIN_GRID) {
			latFloor = LATITUDE_MIN; 
		}
		else if(lat < LATITUDE_ROW_LAST) {
			latFloor = LATITUDE_MIN_GRID; 
		}
		
		return new Location(latFloor, floor.getLongitude());
	}
	
	public static double getGridOffset(Location location) {
		return getGridOffset(location.getLatitude(), location.getLongitude());
	}
	
	public static double getGridOffset(double lat, double lng) {
		if(!s_model_ok) {
			return OFFSET_INVALID;
		}
		
		if(!latIsGridPoint(lat) || !lngIsGridPoint(lng)) {
			return OFFSET_INVALID;
		}
		
		if(latIsPole(lat)) {
			if(lat == LATITUDE_MAX) {
				return offset_north_pole;
			}
			else {
				return offset_south_pole;
			}
		}
		
		int i = latToI(lat);
		int j = lngToJ(lng);
		
		return offset[i][j];
	}
	
	private static File getFileFromResource(String filename) {
		ClassLoader classLoader = Geoid.class.getClassLoader();
		File file = new File(classLoader.getResource(filename).getFile());
		return file;
	}

	private static boolean readGeoidOffsets(BufferedReader br) throws Exception {
		assignMissingOffsets();
		
		String line = br.readLine();
		int l = 1; // line counter

		while(line != null) {
			l++;

			// skip comment lines
			if(lineIsOk(line)) {
				StringTokenizer t = new StringTokenizer(line);
				int c = t.countTokens();

				if(c != 3) {
					System.err.println("error on line " + l + ": found " + c + " tokens (expected 3): '" + line + "'");
				}

				double lat = Double.parseDouble(t.nextToken());
				double lng = Double.parseDouble(t.nextToken());
				double off = Double.parseDouble(t.nextToken());

				if(latLongOk(lat, lng, l)) {					
					int i_lat = 0;
					int j_lng = 0;

					if(lat == LATITUDE_MAX) {
						offset_north_pole = off;
					}
					else if(lat == LATITUDE_MIN) {
						offset_south_pole = off;
					}
					else {
						if(lat == LATITUDE_MAX_GRID) {
							i_lat = 0;
						}
						else if(lat == LATITUDE_MIN_GRID) {
							i_lat = ROWS - 1;
						}
						else {
							i_lat = (int) ((LATITUDE_MAX - lat) / LATITUDE_STEP) - 1;
						}

						j_lng = (int) (lng / LONGITIDE_STEP);

						offset[i_lat][j_lng] = off;
					}
				}
			}

			line = br.readLine();
		}
		
		// somewhat simplistic criteria:
		// test if we have a line for each array field plus the 2 poles
		return !hasMissingOffsets();
	}

	private static boolean lineIsOk(String line) {
		// skip comment lines
		if(line.startsWith(COMMENT_PREFIX)) {
			return false;
		}

		// skip lines with not offset value
		if(line.endsWith(INVALID_OFFSET)) {
			return false;
		}

		return true;
	}

	private static void assignMissingOffsets() {
		offset_north_pole = OFFSET_MISSING;
		offset_south_pole = OFFSET_MISSING;
		
		for(int i = 0; i < ROWS; i++) {
			for(int j = 0; j < COLS; j++) {
				offset[i][j] = OFFSET_MISSING;
			}
		}
	}
	
	private static boolean hasMissingOffsets() {
		if(offset_north_pole == OFFSET_MISSING) { return true; }
		if(offset_south_pole == OFFSET_MISSING) { return true; }
		
		for(int i = 0; i < ROWS; i++) {
			for(int j = 0; j < COLS; j++) {
				if(offset[i][j] == OFFSET_MISSING) {
					return true;
				}
			}
		}
		
		return false;
	}
	
	private static boolean latLongOk(double lat, double lng, int line) {
		if(!latOk(lat)) {
			System.err.println("error on line " + line + ": latitude " + lat + " out or range [" + LATITUDE_MIN + "," + LATITUDE_MAX + "]");
			return false;
		}

		if(!lngOkGrid(lng)) {
			System.err.println("error on line " + line + ": longitude " + lng + " out or range [" + LONGITIDE_MIN_GRID + "," + LONGITIDE_MAX_GRID + "]");
			return false;
		}

		return true;
	}
	
	private static boolean latOk(double lat) {
		boolean lat_in_bounds = lat >= LATITUDE_MIN && lat <= LATITUDE_MAX;
		return lat_in_bounds;
	}
	
	private static boolean lngOk(double lng) {
		boolean lng_in_bounds = lng >= LONGITIDE_MIN && lng <= LONGITIDE_MAX;
		return lng_in_bounds;
	} 
	
	private static boolean lngOkGrid(double lng) {
		boolean lng_in_bounds = lng >= LONGITIDE_MIN_GRID && lng <= LONGITIDE_MAX_GRID;
		return lng_in_bounds;
	}
	
	private static boolean latIsGridPoint(double lat) {
		if(!latOk(lat)) {
			return false;
		}
		
		if(latIsPole(lat)) {
			return true;
		}
		
		if(lat == LATITUDE_MAX_GRID || lat == LATITUDE_MIN_GRID) {
			return true;
		}
		
		if(lat <= LATITUDE_ROW_FIRST && lat >= LATITUDE_ROW_LAST && lat / LATITUDE_STEP == Math.round(lat / LATITUDE_STEP)) {
			return true;
		}
		
		return false;
	}
	
	private static boolean lngIsGridPoint(double lng) {
		if(!lngOkGrid(lng)) {
			return false;
		}
		
		if(lng / LONGITIDE_STEP == Math.round(lng / LONGITIDE_STEP)) {
			return true;
		}
		
		return false;
	}
	
	private static boolean latIsPole(double lat) {
		return lat == LATITUDE_MAX || lat == LATITUDE_MIN; 
	}
	
	private static int latToI(double lat) {
		if(lat == LATITUDE_MAX_GRID) { return 0; }
		if(lat == LATITUDE_MIN_GRID) { return ROWS - 1; }
		else                         { return (int)((LATITUDE_ROW_FIRST - lat) / LATITUDE_STEP) + 1; }
	}
	
	private static int lngToJ(double lng) {
		return (int)(lng / LONGITIDE_STEP);
	}
	
}