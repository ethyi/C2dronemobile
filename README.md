# C2 Drone Mobile

Android ground control app for ArduPilot drones. Connects via USB serial (SiK radio) to upload waypoint missions and control flight modes.

## Features

- Satellite map with long-press waypoint placement
- Mission upload with visual progress tracking
- Flight control: Pause (LOITER), Resume (AUTO), Land, Abort
- Live telemetry: heading, altitude, battery
- Waypoint completion tracking with visual feedback

## Setup

1. Add your Google Maps API key to `local.properties`:
   ```
   MAPS_API_KEY=your_key_here
   ```
2. Connect SiK radio via USB OTG
3. Build and run

## Hardware

- Pixhawk flight controller running ArduCopter
- SiK telemetry radio (57600 baud)


# Demo
https://github.com/user-attachments/assets/07a676e2-c9d7-42c3-9ab9-bf30229064ce

<img src="https://github.com/user-attachments/assets/387735ae-0f74-4b5c-aab7-50a6d3327aa3" alt="drawing" width="200"/>
