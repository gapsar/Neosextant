# src/main/python/astro_navigator.py
import math
import numpy as np
from datetime import datetime, timezone, timedelta
from java import jclass # For type checking if running in Chaquopy environment
import traceback

# -----------------------------------------------------------------------------
# UTILITY FUNCTIONS
# -----------------------------------------------------------------------------
def hms_to_decimal_hours(hours, minutes, seconds):
    return hours + minutes / 60 + seconds / 3600

def dms_to_decimal_degrees(sign, degrees, minutes, seconds):
    return sign * (degrees + minutes / 60 + seconds / 3600)

def get_utc_datetime_from_str(utc_datetime_str):
    try:
        return datetime.strptime(utc_datetime_str, "%Y-%m-%d %H:%M:%S.%f").replace(tzinfo=timezone.utc)
    except ValueError:
        return datetime.strptime(utc_datetime_str, "%Y-%m-%d %H:%M:%S").replace(tzinfo=timezone.utc)

# -----------------------------------------------------------------------------
# ASTRONOMY CALCULATIONS (Julian Day, GMST, LST, Hour Angle, Alt/Az)
# -----------------------------------------------------------------------------
def pc_calculate_julian_day(dt_utc):
    Y = dt_utc.year
    M = dt_utc.month
    D_day = dt_utc.day
    UT_hours = dt_utc.hour + dt_utc.minute / 60 + (dt_utc.second + dt_utc.microsecond / 1e6) / 3600
    if M <= 2: Y -= 1; M += 12
    A = math.floor(Y / 100)
    B = 2 - A + math.floor(A / 4)
    return math.floor(365.25 * (Y + 4716)) + math.floor(30.6001 * (M + 1)) + D_day + UT_hours / 24 + B - 1524.5

def pc_calculate_gmst_deg(jd):
    T_JD = (jd - 2451545.0) / 36525.0
    gmst_deg = (280.46061837 + 360.98564736629 * (jd - 2451545.0) +
                0.000387933 * T_JD**2 - T_JD**3 / 38710000.0) % 360.0
    return gmst_deg if gmst_deg >= 0 else gmst_deg + 360.0

def pc_calculate_lst_deg(gmst_deg, longitude_est_deg):
    lst_deg = (gmst_deg + longitude_est_deg) % 360.0
    return lst_deg if lst_deg >= 0 else lst_deg + 360.0

def pc_calculate_hour_angle_deg(lst_deg, ra_deg):
    ha_deg = (lst_deg - ra_deg) % 360.0
    return ha_deg if ha_deg >= 0 else ha_deg + 360.0

def pc_calculate_pole_angle_deg(hour_angle_deg):
    return hour_angle_deg if hour_angle_deg <= 180 else 360.0 - hour_angle_deg

def pc_calculate_altitude_azimuth_deg(latitude_deg, declination_deg, pole_angle_deg, hour_angle_west_positive_deg):
    latitude_rad = math.radians(latitude_deg)
    declination_rad = math.radians(declination_deg)
    hour_angle_rad_for_hc = math.radians(hour_angle_west_positive_deg)
    sin_hc = (math.sin(latitude_rad) * math.sin(declination_rad) +
              math.cos(latitude_rad) * math.cos(declination_rad) * math.cos(hour_angle_rad_for_hc))
    if sin_hc > 1.0: sin_hc = 1.0
    elif sin_hc < -1.0: sin_hc = -1.0
    hc_rad = math.asin(sin_hc)
    hc_deg = math.degrees(hc_rad)
    y_az = -math.cos(declination_rad) * math.sin(hour_angle_rad_for_hc)
    x_az = (math.sin(declination_rad) * math.cos(latitude_rad) -
            math.cos(declination_rad) * math.sin(latitude_rad) * math.cos(hour_angle_rad_for_hc))
    zc_rad = math.atan2(y_az, x_az)
    zc_deg = math.degrees(zc_rad) % 360.0
    return hc_deg, (zc_deg if zc_deg >=0 else zc_deg + 360.0)

# -----------------------------------------------------------------------------
# NAVIGATION FUNCTION (Intercept)
# -----------------------------------------------------------------------------
def pc_calculate_intercept(h_observed_deg, h_calculated_deg):
    intercept_deg = h_observed_deg - h_calculated_deg
    intercept_nautical_miles = intercept_deg * 60.0
    # direction_intercept_str not directly used by LOP intersection logic
    return intercept_nautical_miles, "N/A"

# Constants
DEG2RAD = math.pi / 180.0
RAD2DEG = 180.0 / math.pi

def normalize_angle_0_360(degrees):
    return degrees % 360.0

def normalize_angle_plus_minus_180(degrees):
    degrees = normalize_angle_0_360(degrees)
    if degrees > 180: degrees -= 360.0
    return degrees

# --- Greenwich Sidereal Time Angle (AHSo or GST) ---
def get_greenwich_sidereal_time_angle(utc_dt_object):
    try:
        jd = pc_calculate_julian_day(utc_dt_object)
        gst_deg = pc_calculate_gmst_deg(jd)
        return normalize_angle_0_360(gst_deg)
    except Exception as e_gst:
        print(f"Python (astro_navigator.py): Error calculating GST: {e_gst}")
        raise

# --- Line of Position Elements (Marcq Saint-Hilaire) ---
def calculate_line_of_position_elements(
    utc_dt_object,
    img_center_ra_deg,
    img_center_dec_deg,
    estimated_lat_deg,
    estimated_lon_ouest_pos_deg,
    observed_camera_hv_deg # This is the calibrated phone_pitch_deg
):
    try:
        lon_est_east_pos_deg = -estimated_lon_ouest_pos_deg
        lon_est_east_pos_deg = normalize_angle_plus_minus_180(lon_est_east_pos_deg)

        jd = pc_calculate_julian_day(utc_dt_object)
        ahso_deg = pc_calculate_gmst_deg(jd) # GST
        ahsg_e_deg = pc_calculate_lst_deg(ahso_deg, lon_est_east_pos_deg) # LST
        ahag_image_e_deg = pc_calculate_hour_angle_deg(ahsg_e_deg, img_center_ra_deg) # LHA
        P_e_deg = pc_calculate_pole_angle_deg(ahag_image_e_deg) # Pole Angle P

        hc_image_deg, Zc_image_deg = pc_calculate_altitude_azimuth_deg(
            estimated_lat_deg, img_center_dec_deg, P_e_deg, ahag_image_e_deg
        )
        intercept_nm, _ = pc_calculate_intercept(observed_camera_hv_deg, hc_image_deg)

        return {
            'intercept_nm': intercept_nm,
            'azimuth_deg': Zc_image_deg, # This is True Azimuth Zn
            'estimated_hv_deg': hc_image_deg,
            'pole_angle_p_deg': P_e_deg,
            'local_hour_angle_ahag_deg': ahag_image_e_deg,
            'error': None
        }
    except Exception as e:
        error_msg = f"Error in LOP calculation: {str(e)}\n{traceback.format_exc()}"
        print(f"Python (astro_navigator.py): {error_msg}")
        return {'error': error_msg}

# --- Intersection of Lines of Position ---
def get_position_from_three_lops(lop1_elements, lop2_elements, lop3_elements,
                                 initial_estimated_lat_deg, initial_estimated_lon_ouest_pos_deg):
    lops_data = [lop1_elements, lop2_elements, lop3_elements]
    for i, lop in enumerate(lops_data):
        if lop.get('error'):
            return {'error': f"Error in input LOP {i+1}: {lop['error']}"}
        if lop.get('intercept_nm') is None or lop.get('azimuth_deg') is None:
            return {'error': f"Missing intercept or azimuth for LOP {i+1}"}

    current_lat_deg = initial_estimated_lat_deg
    current_lon_ouest_pos_deg = initial_estimated_lon_ouest_pos_deg

    iterations = 20; learning_rate = 0.1; min_improvement_deg = 1e-7 / 60.0 # Approx 0.006 arcsec
    history = []

    for iter_count in range(iterations):
        sum_delta_lat_deg = 0.0; sum_delta_lon_deg_factor = 0.0
        total_abs_dist_from_cops_nm = 0.0
        current_lat_rad = math.radians(current_lat_deg)

        for lop in lops_data:
            intercept_at_initial_ep_nm = lop['intercept_nm']
            azimuth_rad = math.radians(lop['azimuth_deg'])
            
            delta_lat_from_initial_ep_deg = current_lat_deg - initial_estimated_lat_deg
            delta_lon_W_pos_from_initial_ep_deg = normalize_angle_plus_minus_180(current_lon_ouest_pos_deg - initial_estimated_lon_ouest_pos_deg)
            
            delta_lat_from_initial_ep_nm = delta_lat_from_initial_ep_deg * 60.0
            delta_lon_E_from_initial_ep_nm = (-delta_lon_W_pos_from_initial_ep_deg) * 60.0 * math.cos(math.radians(initial_estimated_lat_deg)) # Use initial_estimated_lat_deg for G calculation consistency
            
            change_in_hc_nm = (delta_lat_from_initial_ep_nm * math.cos(azimuth_rad) + 
                               delta_lon_E_from_initial_ep_nm * math.sin(azimuth_rad))
            
            intercept_at_current_ep_nm = intercept_at_initial_ep_nm - change_in_hc_nm
            signed_dist_to_move_nm = intercept_at_current_ep_nm
            total_abs_dist_from_cops_nm += abs(signed_dist_to_move_nm)
            
            move_lat_deg = (signed_dist_to_move_nm / 60.0) * math.cos(azimuth_rad)
            move_lon_E_deg_unscaled = (signed_dist_to_move_nm / 60.0) * math.sin(azimuth_rad)
            
            sum_delta_lat_deg += move_lat_deg
            if abs(math.cos(current_lat_rad)) > 1e-9: # Avoid division by zero near poles for longitude factor
                sum_delta_lon_deg_factor += (-move_lon_E_deg_unscaled / math.cos(current_lat_rad))
        
        avg_move_lat_deg = (sum_delta_lat_deg / len(lops_data)) * learning_rate
        avg_move_lon_W_pos_deg = (sum_delta_lon_deg_factor / len(lops_data)) * learning_rate
        
        current_lat_deg += avg_move_lat_deg
        current_lon_ouest_pos_deg += avg_move_lon_W_pos_deg
        current_lon_ouest_pos_deg = normalize_angle_plus_minus_180(current_lon_ouest_pos_deg)
        current_lat_deg = max(-90.0, min(90.0, current_lat_deg)) # Clamp latitude
        
        history.append({'iter': iter_count, 'lat': current_lat_deg, 'lonW+': current_lon_ouest_pos_deg, 'avg_move_lat_deg': avg_move_lat_deg, 'avg_move_lon_deg': avg_move_lon_W_pos_deg, 'total_abs_dist_nm_to_cops': total_abs_dist_from_cops_nm})
        
        if abs(avg_move_lat_deg) < min_improvement_deg and abs(avg_move_lon_W_pos_deg) < min_improvement_deg:
            break
    
    final_distances_to_cops_nm = []
    for lop in lops_data:
        intercept_at_initial_ep_nm = lop['intercept_nm']; azimuth_rad = math.radians(lop['azimuth_deg'])
        delta_lat_from_initial_ep_deg = current_lat_deg - initial_estimated_lat_deg
        delta_lon_W_pos_from_initial_ep_deg = normalize_angle_plus_minus_180(current_lon_ouest_pos_deg - initial_estimated_lon_ouest_pos_deg)
        delta_lat_from_initial_ep_nm = delta_lat_from_initial_ep_deg * 60.0
        delta_lon_E_from_initial_ep_nm = (-delta_lon_W_pos_from_initial_ep_deg) * 60.0 * math.cos(math.radians(initial_estimated_lat_deg))
        change_in_hc_nm = (delta_lat_from_initial_ep_nm * math.cos(azimuth_rad) + delta_lon_E_from_initial_ep_nm * math.sin(azimuth_rad))
        intercept_at_final_ep_nm = intercept_at_initial_ep_nm - change_in_hc_nm
        final_distances_to_cops_nm.append(abs(intercept_at_final_ep_nm))
    spread_nm = max(final_distances_to_cops_nm) if final_distances_to_cops_nm else 0.0
    
    return {'latitude_deg': current_lat_deg, 'longitude_deg': current_lon_ouest_pos_deg, 'spread_nm': spread_nm, 'iterations_info': history, 'error': None}

# --- Main Entry Point Called from Kotlin ---
def get_final_position(
    image_observations_data_list,
    initial_estimated_lat_deg_str,
    initial_estimated_lon_str,
    lon_convention_is_east_positive
    ):
    print(f"Python (astro_navigator.py): get_final_position called. Lon convention East+ from Kotlin: {lon_convention_is_east_positive}")
    print(f"Python (astro_navigator.py): Received initial_estimated_lat_deg_str: '{initial_estimated_lat_deg_str}'")
    print(f"Python (astro_navigator.py): Received initial_estimated_lon_str: '{initial_estimated_lon_str}'")
    
    processed_observations_for_debug = []
    try:
        if not initial_estimated_lat_deg_str or not initial_estimated_lon_str:
            error_msg = "Initial estimated latitude or longitude string is empty."
            print(f"Python (astro_navigator.py): ERROR - {error_msg}")
            return {'error': error_msg, 'processed_observations_for_debug': processed_observations_for_debug}
        initial_estimated_lat_deg = float(initial_estimated_lat_deg_str)
        initial_estimated_lon_input_deg = float(initial_estimated_lon_str)
    except ValueError as ve:
        error_msg = f"Initial estimated latitude or longitude is not a valid number. Lat: '{initial_estimated_lat_deg_str}', Lon: '{initial_estimated_lon_str}'. Error: {ve}"
        print(f"Python (astro_navigator.py): ERROR - {error_msg}")
        return {'error': error_msg, 'processed_observations_for_debug': processed_observations_for_debug}

    if not (-90 <= initial_estimated_lat_deg <= 90):
        error_msg = f"Initial estimated latitude out of range (-90 to 90): {initial_estimated_lat_deg}"
        print(f"Python (astro_navigator.py): ERROR - {error_msg}")
        return {'error': error_msg, 'processed_observations_for_debug': processed_observations_for_debug}
    
    initial_estimated_lon_input_deg = normalize_angle_plus_minus_180(initial_estimated_lon_input_deg)
    initial_estimated_lon_ouest_pos_deg = -initial_estimated_lon_input_deg if lon_convention_is_east_positive else initial_estimated_lon_input_deg
    initial_estimated_lon_ouest_pos_deg = normalize_angle_plus_minus_180(initial_estimated_lon_ouest_pos_deg)
    print(f"Python (astro_navigator.py): Parsed EP: Lat={initial_estimated_lat_deg:.5f}, Lon(W+)={initial_estimated_lon_ouest_pos_deg:.5f}")

    num_observations = 0
    py_image_observations_data_list = []
    JavaList = jclass("java.util.List")
    JavaMap = jclass("java.util.Map")

    if isinstance(image_observations_data_list, JavaList):
        num_observations = image_observations_data_list.size()
        print(f"Python (astro_navigator.py): Input is JavaList with size: {num_observations}")
        try:
            for i in range(num_observations):
                java_map_obj = image_observations_data_list.get(i)
                if isinstance(java_map_obj, JavaMap):
                    python_dict = {}
                    java_map_entries = java_map_obj.entrySet().toArray()
                    for entry in java_map_entries:
                        python_dict[str(entry.getKey())] = entry.getValue()
                    py_image_observations_data_list.append(python_dict)
                elif isinstance(java_map_obj, dict): # Should not happen if top level is JavaList from Chaquopy
                    py_image_observations_data_list.append(java_map_obj)
                else:
                    error_msg = f"Python: Element at index {i} is not a Map/dict (type: {type(java_map_obj)})."
                    print(f"Python (astro_navigator.py): ERROR - {error_msg}")
                    return {'error': error_msg, 'processed_observations_for_debug': processed_observations_for_debug}
            image_observations_data_list = py_image_observations_data_list # Replace JavaList with Python list of dicts
        except Exception as e_conv:
            error_msg = f"Python: Error converting Java List/Map: {e_conv}\n{traceback.format_exc()}"
            print(f"Python (astro_navigator.py): ERROR - {error_msg}")
            return {'error': error_msg, 'processed_observations_for_debug': processed_observations_for_debug}
    elif hasattr(image_observations_data_list, '__len__') and all(isinstance(item, dict) for item in image_observations_data_list):
        num_observations = len(image_observations_data_list)
        print(f"Python (astro_navigator.py): Input is Python list of dicts with size: {num_observations}")
    else:
        err_type_msg = f"Unsupported type or structure for image_observations_data_list: {type(image_observations_data_list)}"
        if hasattr(image_observations_data_list, '__len__'):
            err_type_msg += f", item types: {[type(item) for item in image_observations_data_list[:2]]}"
        print(f"Python (astro_navigator.py): ERROR - {err_type_msg}")
        return {'error': err_type_msg, 'processed_observations_for_debug': processed_observations_for_debug}

    print(f"Python (astro_navigator.py): Number of observations: {num_observations}")
    if num_observations not in [1, 3]:
        error_msg = f"Requires 1 or 3 observations, got {num_observations}."
        print(f"Python (astro_navigator.py): ERROR - {error_msg}")
        return {'error': error_msg, 'processed_observations_for_debug': processed_observations_for_debug}
    
    lops_elements_list = []
    for i, obs_data in enumerate(image_observations_data_list):
        print(f"Python (astro_navigator.py): Processing observation {i+1}/{num_observations}: {obs_data}")
        try:
            utc_str = str(obs_data.get('utc'))
            img_ra_deg = float(obs_data.get('ra_deg'))
            img_dec_deg = float(obs_data.get('dec_deg'))
            phone_pitch_deg = float(obs_data.get('phone_pitch_deg')) # This is the calibrated pitch from Kotlin
        except (TypeError, ValueError) as e:
            error_msg = f"Invalid or missing numerical data for obs {i+1}: {e}. Data: {obs_data}"
            print(f"Python (astro_navigator.py): ERROR - {error_msg}")
            # Add partial data to debug if possible
            partial_debug_obs_data = dict(obs_data)
            partial_debug_obs_data['lop_calculation_details'] = {'error': f"Data parsing error: {e}"}
            processed_observations_for_debug.append(partial_debug_obs_data)
            return {'error': error_msg, 'processed_observations_for_debug': processed_observations_for_debug}
        
        required_keys = ['utc', 'ra_deg', 'dec_deg', 'phone_pitch_deg']
        missing_keys = [key for key in required_keys if obs_data.get(key) is None]
        if missing_keys:
            error_msg = f"Missing data fields for obs {i+1}: {', '.join(missing_keys)}. Data: {obs_data}"
            print(f"Python (astro_navigator.py): ERROR - {error_msg}")
            partial_debug_obs_data = dict(obs_data)
            partial_debug_obs_data['lop_calculation_details'] = {'error': f"Missing keys: {', '.join(missing_keys)}"}
            processed_observations_for_debug.append(partial_debug_obs_data)
            return {'error': error_msg, 'processed_observations_for_debug': processed_observations_for_debug}
        
        try:
            utc_dt = get_utc_datetime_from_str(utc_str)
        except ValueError as e_date:
            error_msg = f"Invalid UTC format for obs {i+1}: '{utc_str}'. Error: {e_date}"
            print(f"Python (astro_navigator.py): ERROR - {error_msg}")
            partial_debug_obs_data = dict(obs_data)
            partial_debug_obs_data['lop_calculation_details'] = {'error': f"UTC format error: {e_date}"}
            processed_observations_for_debug.append(partial_debug_obs_data)
            return {'error': error_msg, 'processed_observations_for_debug': processed_observations_for_debug}
        
        observed_hv_deg = phone_pitch_deg # Use calibrated phone pitch directly as Ho
        
        print(f"Python (astro_navigator.py): Calculating LOP elements for obs {i+1}...")
        lop_elems = calculate_line_of_position_elements(utc_dt, img_ra_deg, img_dec_deg, initial_estimated_lat_deg, initial_estimated_lon_ouest_pos_deg, observed_hv_deg)
        
        debug_obs_data = dict(obs_data) 
        debug_obs_data['observed_camera_hv_deg'] = observed_hv_deg 
        debug_obs_data['lop_calculation_details'] = lop_elems
        processed_observations_for_debug.append(debug_obs_data)
        
        if lop_elems.get('error'):
            error_msg = f"Error calculating LOP for obs {i+1}: {lop_elems['error']}"
            print(f"Python (astro_navigator.py): ERROR - {error_msg}")
            # Return now, processed_observations_for_debug contains the error details for this LOP
            return {'error': error_msg, 'processed_observations_for_debug': processed_observations_for_debug}
        
        print(f"Python (astro_navigator.py): LOP elements for obs {i+1}: {lop_elems}")
        lops_elements_list.append(lop_elems)

    if num_observations == 1:
        print("Python (astro_navigator.py): Single observation processed. Returning LOP details.")
        return {
            'processed_observations_for_debug': processed_observations_for_debug,
            'error': None
        }
    elif num_observations == 3:
        print("Python (astro_navigator.py): Three observations processed. Calculating fix...")
        final_position_result = get_position_from_three_lops(lops_elements_list[0], lops_elements_list[1], lops_elements_list[2], initial_estimated_lat_deg, initial_estimated_lon_ouest_pos_deg)
        # Ensure processed_observations_for_debug is part of the final result for 3 LOPs too.
        final_position_result['processed_observations_for_debug'] = processed_observations_for_debug
        print(f"Python (astro_navigator.py): Fix calculation result: {final_position_result}")
        return final_position_result
    else: # Should not be reached due to earlier checks
        error_msg = "Internal logic error: Unexpected number of observations after LOP calculation."
        print(f"Python (astro_navigator.py): ERROR - {error_msg}")
        return {'error': error_msg, 'processed_observations_for_debug': processed_observations_for_debug}


def cleanup_resources():
    print("Python (astro_navigator.py): cleanup_resources called.")
    pass

if __name__ == '__main__':
    print("--- astro_navigator.py self-test ---")
    dt_test_gst = datetime(2024, 1, 1, 0, 0, 0, tzinfo=timezone.utc)
    gst_deg_test = get_greenwich_sidereal_time_angle(dt_test_gst)
    print(f"Test GST for {dt_test_gst}: {gst_deg_test:.4f} degrees")

    print("\nTesting LOP Calculation...")
    test_utc_dt_lop = datetime(2025, 5, 17, 21, 59, 31, tzinfo=timezone.utc)
    test_ra_lop, test_dec_lop = (1 + 12) * 15 + 59 * (15/60) + 26.16 * (15/3600), +(4 + 58/60 + 5.52/3600)
    test_lat_ep_lop, test_lon_ep_ouest_pos_lop = 49.5, -0.1 # Lon West Positive
    test_ho_lop_from_pitch = 35.22
    lop_results_test = calculate_line_of_position_elements(test_utc_dt_lop, test_ra_lop, test_dec_lop, test_lat_ep_lop, test_lon_ep_ouest_pos_lop, test_ho_lop_from_pitch)
    if lop_results_test.get('error'): print(f"LOP Test Error: {lop_results_test['error']}")
    else: print(f"LOP Test Results: Intercept: {lop_results_test.get('intercept_nm'):.2f} NM, Azimuth: {lop_results_test.get('azimuth_deg'):.2f} deg, Hc: {lop_results_test.get('estimated_hv_deg'):.4f} deg")

    print("\nTesting Single LOP through get_final_position...")
    single_obs_data = [{
        'utc': "2025-05-17 21:59:31.000",
        'ra_deg': test_ra_lop,
        'dec_deg': test_dec_lop,
        'phone_pitch_deg': test_ho_lop_from_pitch,
        'phone_azimuth_deg': 180.0, 
        'phone_roll_deg': 0.0      
    }]
    # Test with lon_east_positive = True, so input lon should be East positive.
    # test_lon_ep_ouest_pos_lop is -0.1 (W+), so East positive is +0.1
    single_lop_final_pos_result = get_final_position(single_obs_data, str(test_lat_ep_lop), str(-test_lon_ep_ouest_pos_lop), True) 
    if single_lop_final_pos_result.get('error'):
        print(f"Single LOP Test Error: {single_lop_final_pos_result['error']}")
        if 'processed_observations_for_debug' in single_lop_final_pos_result:
             print(f"Debug info: {single_lop_final_pos_result['processed_observations_for_debug']}")
    else:
        debug_info = single_lop_final_pos_result.get('processed_observations_for_debug', [])
        if debug_info and isinstance(debug_info, list) and len(debug_info) > 0 and debug_info[0].get('lop_calculation_details'):
            single_lop_details = debug_info[0]['lop_calculation_details']
            print(f"Single LOP Test via get_final_position Results: Intercept: {single_lop_details.get('intercept_nm'):.2f} NM, Azimuth: {single_lop_details.get('azimuth_deg'):.2f} deg, Hc: {single_lop_details.get('estimated_hv_deg'):.4f} deg")
        else:
            print(f"Single LOP Test via get_final_position: Incomplete or unexpected debug info. Raw: {single_lop_final_pos_result}")

    print("\nTesting 3-LOP Intersection...")
    ep_lat_3lop, ep_lon_w_pos_3lop = 50.0, 5.0
    lop1_mock = {'intercept_nm': 10.0, 'azimuth_deg': 45.0, 'error': None}
    lop2_mock = {'intercept_nm': -5.0, 'azimuth_deg': 150.0, 'error': None}
    lop3_mock = {'intercept_nm': 0.0, 'azimuth_deg': 270.0, 'error': None}
    fix_result_test = get_position_from_three_lops(lop1_mock, lop2_mock, lop3_mock, ep_lat_3lop, ep_lon_w_pos_3lop)
    if fix_result_test.get('error'): print(f"3-LOP Fix Test Error: {fix_result_test['error']}")
    else: print(f"3-LOP Fix Test Results: Lat={fix_result_test.get('latitude_deg'):.5f}, Lon(W+)={fix_result_test.get('longitude_deg'):.5f}, Spread={fix_result_test.get('spread_nm'):.2f} NM")
    
    print("\nTesting get_final_position with invalid Lat/Lon strings from Kotlin...")
    invalid_lat_lon_obs = single_obs_data 
    # Test empty latitude string
    result_empty_lat = get_final_position(invalid_lat_lon_obs, "", "10.0", True)
    print(f"Test Empty Lat: {result_empty_lat}")
    # Test non-numeric longitude string
    result_invalid_lon = get_final_position(invalid_lat_lon_obs, "50.0", "abc", True)
    print(f"Test Invalid Lon: {result_invalid_lon}")


    print("\n--- astro_navigator.py self-test complete ---")
