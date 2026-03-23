//Sensor ultrasonico CPT
const int trigPin = 9;
const int echoPin = 8;
float lastdistance, distance, duration;


//NEW PROTOCOL - for phone

void setup() {
Serial.begin (115200);
pinMode(trigPin, OUTPUT);
pinMode(echoPin, INPUT);
lastdistance = 0.0;
Serial.println();
Serial.println("V;n;0");
}



void loop() {
  

//PRIMERO
digitalWrite(trigPin, LOW); // Added this line
delayMicroseconds(2); // Added this line

digitalWrite(trigPin, HIGH);
delayMicroseconds(10); // Added this line
digitalWrite(trigPin, LOW);

duration = pulseIn(echoPin, HIGH, 10000);
distance = (duration * 0.034) / 2.0;
Serial.print("V;n;");

if (distance > 90 || distance < 2){
Serial.println(lastdistance);
}
else {
Serial.println(distance);
lastdistance = distance;
}
delay(10);
}

