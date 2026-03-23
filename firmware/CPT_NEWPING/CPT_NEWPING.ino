//Sensor ultrasonico CPT -NEWPING

#include <NewPing.h>

#define TRIGGER_PIN  12  // Arduino pin tied to trigger pin on the ultrasonic sensor.
#define ECHO_PIN     11 // Arduino pin tied to echo pin on the ultrasonic sensor.
#define MAX_DISTANCE 150 // Maximum distance we want to ping for (in centimeters). Maximum sensor distance is rated at 400-500cm.

NewPing sonar(TRIGGER_PIN, ECHO_PIN, MAX_DISTANCE); // NewPing setup of pins and maximum distance.
float duration, distance, lastdistance;

void setup() {
  Serial.begin(115200); // Open serial monitor at 115200 baud to see ping results.
  Serial.println("V;n;0");
  distance = 0;
  lastdistance = 0;
  duration = 0;
}

void loop() {
  //delay(1);                     // Wait 50ms between pings (about 20 pings/sec). 29ms should be the shortest delay between pings.
  duration = sonar.ping_median(3);
  Serial.print("V;n;");
  distance = sonar.convert_cm(duration);
  float deldist = (abs (distance - lastdistance));
  if (deldist > 4)  
  { 
    if (distance > lastdistance) { distance = lastdistance + (deldist / 2.0); }
    else {distance = lastdistance - (deldist / 2.0);}
  }
  distance = (distance + lastdistance ) / 2.0;
  Serial.println(distance); // Send ping, get distance in cm and print result (0 = outside set distance range)
  //Serial.println("cm");
  lastdistance = distance;
}


