/*
  Project: LilyPad Monitor (Health Alert + Stress Edition)
  Status: Multi-LED Enabled + Alerts
*/

#include <Wire.h>
#include "MAX30105.h" 
#include "heartRate.h" 
#include <SoftwareSerial.h>
#include "U8glib.h"

// --- BITMAPS ---
const uint8_t heart_bitmap[] U8G_PROGMEM = {
  0x00, 0x00, 0x1C, 0x38, 0x3E, 0x7C, 0x7F, 0xFE, 
  0x7F, 0xFE, 0x7F, 0xFE, 0x3F, 0xFC, 0x1F, 0xF8, 
  0x0F, 0xF0, 0x07, 0xE0, 0x03, 0xC0, 0x01, 0x80, 0x00, 0x00 
};

const uint8_t smile_bitmap[] U8G_PROGMEM = {
  0x03, 0xC0, 0x0C, 0x30, 0x10, 0x08, 0x20, 0x04, 
  0x40, 0x02, 0x46, 0x62, 0x46, 0x62, 0x40, 0x02, 
  0x40, 0x02, 0x48, 0x12, 0x24, 0x24, 0x13, 0xC8, 
  0x0C, 0x30, 0x03, 0xC0, 0x00, 0x00, 0x00, 0x00  
};

const uint8_t pulse_bitmap[] U8G_PROGMEM = {
  0x00, 0x00, 0x00, 0x0f, 0xc1, 0xe0, 0x1f, 0xf7, 0xf8, 0x3f, 0xff, 0xfc, 0x7f, 0xff, 0xfe, 0x7f, 
  0xff, 0xfe, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xe7, 0xff, 0xff, 0xc7, 0x0f, 0xf8, 0x12, 
  0x0f, 0x78, 0x3a, 0x7e, 0x7f, 0xf8, 0xfe, 0x3f, 0xfc, 0xfc, 0x1f, 0xff, 0xf8, 0x0f, 0xff, 0xf0, 
  0x07, 0xff, 0xe0, 0x03, 0xff, 0xc0, 0x01, 0xff, 0x80, 0x00, 0xff, 0x00, 0x00, 0x7e, 0x00, 0x00, 
  0x3c, 0x00, 0x00, 0x18, 0x00, 0x00, 0x00, 0x00
};

// --- Configuration ---
const int PIN_BT_RX = 6;
const int PIN_BT_TX = 7;
const int PIN_BUTTON = 9;

// --- Objects ---
SoftwareSerial bluetooth(PIN_BT_RX, PIN_BT_TX);
MAX30105 particleSensor;
U8GLIB_SSD1306_128X64 u8g(U8G_I2C_OPT_FAST); 

// --- Variables ---
boolean systemActive = false;       
boolean lastButtonState = LOW;      
unsigned long lastBeatTime = 0;     

int beatsPerMinute; 
int beatAvg;
long irValue;
long currentRedValue = 0;
int currentSpO2 = 98;

const byte RATE_SIZE = 4; 
byte rates[RATE_SIZE]; 
byte rateSpot = 0;

// --- Stress Variables ---
long beatIntervals[RATE_SIZE]; // Store time diffs
byte intervalSpot = 0;
String stressLevel = "Low";

unsigned long lastScreenUpdate = 0;
unsigned long lastBluetoothSend = 0; 

boolean heartBeatDetected = false; 
unsigned long beatDisplayTimer = 0;
boolean showMerhaba = false;
unsigned long merhabaTimer = 0;

void restartSensor() {
  particleSensor.setup(0x1F, 4, 2, 400, 411, 4096); 
  particleSensor.setPulseAmplitudeRed(0x24); 
  particleSensor.setPulseAmplitudeIR(0x24); 
  particleSensor.setPulseAmplitudeGreen(0);
}

// --- DRAWING ---
void drawIntroFrame(int frame) {
  u8g.setFont(u8g_font_unifont); 
  const char* text = "iclothes";
  for(int i=0; i<8; i++) {
    int x = 15 + (i * 12);
    int y;
    int targetY = 40;
    if(i % 2 == 0) { 
       y = 0 + (frame * 10);
       if(y > targetY) y = targetY;
    } else { 
       y = 64 - (frame * 10);
       if(y < targetY) y = targetY;
    }
    char letterStr[2] = {text[i], '\0'};
    u8g.drawStr(x, y, letterStr);
  }
}

void drawMainScreen() {
  u8g.setFont(u8g_font_6x10); // Using smaller font for alerts

  if (showMerhaba) {
    u8g.drawStr(10, 35, "\\ Merhaba /");
    u8g.drawBitmapP(100, 22, 2, 14, smile_bitmap); 
    return; 
  }

  // Check for Health Alerts FIRST
  if (beatAvg > 100) {
    u8g.drawStr(2, 10, "HR HIGH! REST");
    u8g.drawStr(2, 22, "Please rest.");
  } else if (beatAvg > 20 && beatAvg < 50) {
    u8g.drawStr(2, 10, "HR LOW! WALK");
    u8g.drawStr(2, 22, "Please stand up.");
  } else {
    u8g.drawStr(2, 12, "SpO2:"); 
    u8g.setPrintPos(33, 12);
    u8g.print(currentSpO2); u8g.print("%");
    
    u8g.drawStr(60, 12, "Stress:");
    u8g.setPrintPos(105, 12);
    u8g.print(stressLevel);
  }

  u8g.drawBitmapP(2, 32, 3, 24, pulse_bitmap); 
  u8g.setFont(u8g_font_unifont);
  u8g.setPrintPos(32, 50); 
  u8g.print(beatAvg);
  u8g.print(" BPM");

  if (heartBeatDetected) {
     u8g.drawBitmapP(105, 35, 2, 13, heart_bitmap); 
  }
}

void setup() {
  bluetooth.begin(4800); 
  pinMode(PIN_BUTTON, INPUT); 

  u8g.setColorIndex(1);         

  for(int f=0; f<6; f++) {
     u8g.firstPage();
     do {
       drawIntroFrame(f);
     } while( u8g.nextPage() );
     delay(200);
  }
  delay(1000);

  if (!particleSensor.begin(Wire, I2C_SPEED_FAST)) {
    while (1); 
  }
  restartSensor(); 
  Wire.setClock(400000);
}

void loop() {
  int currentButtonState = digitalRead(PIN_BUTTON);
  if (currentButtonState == HIGH && lastButtonState == LOW) {
    delay(50); 
    systemActive = !systemActive; 
    
    if (systemActive) {
      bluetooth.println(F("START"));
      particleSensor.wakeUp();
      restartSensor(); 
      showMerhaba = true;
      merhabaTimer = millis();
    } else {
      bluetooth.println(F("STOP"));
      particleSensor.shutDown(); 
      beatAvg = 0; 
      u8g.firstPage();
      do {
        u8g.setFont(u8g_font_unifont);
        u8g.drawStr(30, 40, "PAUSED");
      } while( u8g.nextPage() );
    }
  }
  lastButtonState = currentButtonState;

  if (systemActive) {
    particleSensor.check(); 

    while(particleSensor.available()) {
        long redVal = particleSensor.getFIFORed();
        long irVal = particleSensor.getFIFOIR(); 
        irValue = irVal;
        currentRedValue = redVal;

        if (redVal < 50000) { 
            beatAvg = 0;
            currentSpO2 = 0;
        } 
        else {
            float ratio = (float)redVal / (float)irVal;
            if (ratio > 1.0) currentSpO2 = 99;
            else if (ratio > 0.95) currentSpO2 = 98;
            else if (ratio > 0.9) currentSpO2 = 96;
            else currentSpO2 = 92;

            if (checkForBeat(redVal) == true) {
                long delta = millis() - lastBeatTime;
                lastBeatTime = millis();
                
                beatsPerMinute = 120000 / delta;

                if (beatsPerMinute < 200 && beatsPerMinute > 30) {
                    // Save Rates
                    rates[rateSpot++] = (byte)beatsPerMinute;
                    rateSpot %= RATE_SIZE;
                    
                    // Save Time Diffs (Intervals) for Stress
                    beatIntervals[intervalSpot++] = delta;
                    intervalSpot %= RATE_SIZE;

                    // Calculate Average BPM
                    beatAvg = 0;
                    for (byte x = 0 ; x < RATE_SIZE ; x++) beatAvg += rates[x];
                    beatAvg /= RATE_SIZE;

                    // --- BASIC STRESS CALCULATION ---
                    // High variance in intervals = Relaxed (Low Stress)
                    // Low variance = Tense (High Stress)
                    long diff = abs(beatIntervals[0] - beatIntervals[1]);
                    if (diff > 50) stressLevel = "Low"; // Low
                    else if (diff > 20) stressLevel = "Med"; // Med
                    else stressLevel = "High"; // High
                }
                heartBeatDetected = true;
                beatDisplayTimer = millis();
            }
        }
        particleSensor.nextSample(); 
    }

    unsigned long currentMillis = millis();
    
    if (heartBeatDetected && (currentMillis - beatDisplayTimer > 750)) {
       heartBeatDetected = false;
    }

    if (showMerhaba && (currentMillis - merhabaTimer > 3000)) {
       showMerhaba = false;
    }

    if (currentMillis - lastBluetoothSend > 200) { 
       bluetooth.print(irValue);
       bluetooth.print(F(","));
       bluetooth.print(beatsPerMinute);
       bluetooth.print(F(","));
       bluetooth.println(beatAvg);
       lastBluetoothSend = currentMillis;
    }
    
    if (currentMillis - lastScreenUpdate > 750) {
       u8g.firstPage();  
       do {
         drawMainScreen();
       } while( u8g.nextPage() );
       lastScreenUpdate = currentMillis;
    }
  }
}