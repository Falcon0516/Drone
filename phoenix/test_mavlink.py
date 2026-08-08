import time
import sys
from pymavlink import mavutil

def main():
    # Connect to SITL on TCP port 5760
    # In a real setup over Tailscale, this might be a Tailscale IP and port
    connection_string = 'tcp:127.0.0.1:5760'
    print(f"Connecting to MAVLink on {connection_string}...")
    
    try:
        master = mavutil.mavlink_connection(connection_string)
        master.wait_heartbeat()
        print(f"Heartbeat received from system {master.target_system} component {master.target_component}")
    except Exception as e:
        print(f"Failed to connect: {e}")
        sys.exit(1)

    print("Listening for telemetry... (Press Ctrl+C to stop)")
    
    # Request data streams
    # MAV_DATA_STREAM_ALL = 0
    master.mav.request_data_stream_send(
        master.target_system,
        master.target_component,
        0, 
        2, # 2 Hz
        1  # start
    )

    while True:
        try:
            # We want position (GLOBAL_POSITION_INT), heading (VFR_HUD or GLOBAL_POSITION_INT), 
            # altitude (GLOBAL_POSITION_INT relative_alt or alt), battery (SYS_STATUS)
            msg = master.recv_match(type=['GLOBAL_POSITION_INT', 'VFR_HUD', 'SYS_STATUS'], blocking=True, timeout=1.0)
            if not msg:
                continue

            msg_type = msg.get_type()
            
            if msg_type == 'GLOBAL_POSITION_INT':
                lat = msg.lat / 1e7
                lon = msg.lon / 1e7
                alt = msg.relative_alt / 1000.0 # meters
                hdg = msg.hdg / 100.0 # degrees
                print(f"[POS] Lat: {lat:.6f}, Lon: {lon:.6f}, Alt: {alt:.2f}m, Heading: {hdg:.2f}°")
            elif msg_type == 'SYS_STATUS':
                battery_pct = msg.battery_remaining
                if battery_pct != -1:
                    print(f"[BAT] Battery: {battery_pct}%")
            
        except KeyboardInterrupt:
            print("\nStopping...")
            break
        except Exception as e:
            print(f"Error: {e}")
            break

if __name__ == '__main__':
    main()
